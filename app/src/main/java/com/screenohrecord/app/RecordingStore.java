package com.screenohrecord.app;

import android.annotation.SuppressLint;
import android.Manifest;
import android.app.AppOpsManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.Build;
import android.os.Process;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.DocumentsContract;
import android.system.Os;
import android.system.OsConstants;
import android.system.ErrnoException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Owns recording outputs. Android 9 public storage requires legacy read/write access. */
@SuppressLint("ApplySharedPref") // Recovery markers must reach disk before capture/process exit.
final class RecordingStore {
    private static final Object HISTORY_LOCK = new Object();
    enum Availability { PRESENT, MISSING, UNAVAILABLE }
    static final String FOLDER = Environment.DIRECTORY_MOVIES + "/ScreenOhRecord/";
    private final Context context;
    private final SharedPreferences preferences;

    static final class Output {
        final Uri uri;
        final String name;
        final ParcelFileDescriptor descriptor;
        Output(Uri uri, String name, ParcelFileDescriptor descriptor) {
            this.uri = uri;
            this.name = name;
            this.descriptor = descriptor;
        }
    }

    static final class Entry {
        final Uri uri;
        final String name;
        final long bytes;
        final long duration;
        final long completedAt;
        final String destination;
        Entry(Uri uri, String name, long bytes, long duration) {
            this(uri, name, bytes, duration, 0, "");
        }
        Entry(Uri uri, String name, long bytes, long duration, long completedAt, String destination) {
            this.uri = uri; this.name = name; this.bytes = bytes; this.duration = duration;
            this.completedAt = completedAt; this.destination = destination;
        }
    }

    RecordingStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
    }

    static boolean needsLegacyStoragePermission(Context context) {
        return Build.VERSION.SDK_INT < 29 && RecordingOptions.get(context).getString("folder_uri", null) == null
                && (!hasLegacyPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE)
                || !hasLegacyPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE, AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE));
    }

    private static boolean hasLegacyPermission(Context context, String permission, String operation) {
        if (context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            int mode = context.getSystemService(AppOpsManager.class).checkOpNoThrow(operation, Process.myUid(), context.getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT;
        } catch (RuntimeException error) { return false; }
    }

    Output create() throws IOException {
        String name = "ScreenOhRecord_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                .format(new Date()) + ".mp4";
        String folder = RecordingOptions.get(context).getString("folder_uri", null);
        if (folder != null) return createInFolder(Uri.parse(folder), name);
        if (Build.VERSION.SDK_INT < 29) return createLegacyPublic(name);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        values.put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(collection, values);
        if (uri == null) throw new IOException("Cannot create a video in local storage");
        // commit, not apply: retain a cleanup marker before creating encoded content.
        if (!preferences.edit().putString("unfinished", uri.toString()).commit()) {
            context.getContentResolver().delete(uri, null, null);
            throw new IOException("Cannot retain the recording recovery marker");
        }
        try {
            ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "w");
            if (descriptor == null) throw new IOException("Cannot open the local video file");
            return new Output(uri, name, descriptor);
        } catch (IOException | RuntimeException error) {
            discard(uri);
            throw error;
        }
    }

    @SuppressWarnings("deprecation")
    private Output createLegacyPublic(String name) throws IOException {
        if (needsLegacyStoragePermission(context)) throw new IOException("Allow storage access or choose a local folder in Settings");
        recoverInterrupted();
        if (preferences.contains("unfinished")) throw new IOException("Restore access to the previous incomplete recording before starting another");
        File directory = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ScreenOhRecord");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create the Movies/ScreenOhRecord folder");
        File file = new File(directory, name);
        if (!file.createNewFile()) throw new IOException("A recording with this filename already exists");
        Uri uri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
            values.put(MediaStore.Video.Media.DATA, file.getAbsolutePath());
            values.put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000);
            uri = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Cannot register the local recording");
            if (!preferences.edit().putString("unfinished", uri.toString()).commit())
                throw new IOException("Cannot retain the recording recovery marker");
            ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "w");
            if (descriptor == null) throw new IOException("Cannot open the local video file");
            return new Output(uri, name, descriptor);
        } catch (IOException | RuntimeException error) {
            if (uri != null) discard(uri);
            else if (!file.delete()) android.util.Log.w("ScreenOhRecord", "Could not remove empty legacy recording file");
            throw error;
        }
    }

    private Output createInFolder(Uri tree, String name) throws IOException {
        if (!SettingsActivity.isLocalTree(tree)) throw new IOException("Choose a local save folder in Settings");
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri uri;
        try { uri = DocumentsContract.createDocument(context.getContentResolver(), parent, "video/mp4", name); }
        catch (IOException | RuntimeException error) {
            throw new IOException("Cannot access selected folder. Choose it again in Settings", error);
        }
        if (uri == null) throw new IOException("Cannot create video in selected folder. Choose another folder in Settings");
        if (!preferences.edit().putString("unfinished", uri.toString()).commit()) {
            DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            throw new IOException("Cannot retain recording recovery marker");
        }
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = context.getContentResolver().openFileDescriptor(uri, "rw");
            if (descriptor == null) throw new IOException("Cannot open selected folder's video file");
            Os.lseek(descriptor.getFileDescriptor(), 0, OsConstants.SEEK_SET);
            return new Output(uri, name, descriptor);
        } catch (IOException | RuntimeException | ErrnoException error) {
            if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
            discard(uri);
            throw new IOException("Cannot record to selected folder. Choose another local folder in Settings", error);
        }
    }

    void complete(Output output, long bytes, long duration) throws IOException {
        if ((Build.VERSION.SDK_INT < 29 || !"media".equals(output.uri.getAuthority()))
                && !preferences.edit().putString("finalized_document", output.uri.toString()).commit()) {
            throw new IOException("Video saved, but its recovery state could not be stored");
        }
        if ("media".equals(output.uri.getAuthority())) {
            ContentValues values = new ContentValues();
            if (Build.VERSION.SDK_INT >= 29) values.put(MediaStore.Video.Media.IS_PENDING, 0);
            else {
                values.put(MediaStore.Video.Media.SIZE, Math.max(0, bytes));
                values.put(MediaStore.Video.Media.DURATION, Math.max(0, duration));
                values.put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000);
            }
            if (context.getContentResolver().update(output.uri, values, null, null) != 1) {
                throw new IOException("Cannot publish the saved recording");
            }
        }
        try {
            JSONObject item = new JSONObject();
            item.put("uri", output.uri.toString());
            item.put("name", output.name);
            item.put("bytes", Math.max(0, bytes));
            item.put("duration", Math.max(0, duration));
            item.put("completed_at", System.currentTimeMillis());
            item.put("destination", destination(output.uri));
            synchronized (HISTORY_LOCK) {
                JSONArray old = history();
                JSONArray updated = new JSONArray();
                updated.put(item);
                // Display history is bounded; older video files are never deleted here.
                for (int i = 0; i < Math.min(old.length(), 49); i++) updated.put(old.get(i));
                if (!preferences.edit().putString("history", updated.toString())
                        .putString("transfer_pending", appendPending(item).toString())
                        .remove("unfinished").remove("finalized_document").commit()) {
                    throw new IOException("Video saved, but its recent-recordings entry could not be stored");
                }
            }
        } catch (JSONException error) {
            throw new IOException("Video saved, but its recent-recordings entry could not be stored", error);
        }
    }

    boolean discard(Uri uri) {
        if (uri == null) return true;
        try {
            if (SettingsActivity.LOCAL_AUTHORITY.equals(uri.getAuthority())) {
                if (!DocumentsContract.deleteDocument(context.getContentResolver(), uri)) return false;
            }
            else if (context.getContentResolver().delete(uri, null, null) == 0
                    && availability(new Entry(uri, "", 0, 0)) != Availability.MISSING) return false;
        } catch (IOException | RuntimeException error) {
            if (availability(new Entry(uri, "", 0, 0)) != Availability.MISSING) return false;
        }
        return preferences.edit().remove("unfinished").remove("finalized_document").commit();
    }

    void recoverInterrupted() {
        String value = preferences.getString("unfinished", null);
        if (value == null) return;
        Uri uri = Uri.parse(value);
        boolean legacyMedia = Build.VERSION.SDK_INT < 29 && "media".equals(uri.getAuthority());
        if (legacyMedia || (SettingsActivity.LOCAL_AUTHORITY.equals(uri.getAuthority()) && DocumentsContract.isDocumentUri(context, uri))) {
            if (value.equals(preferences.getString("finalized_document", null))) {
                preferences.edit().remove("unfinished").remove("finalized_document").commit();
                return;
            }
            discard(uri); return;
        }
        if (!"content".equals(uri.getScheme()) || !"media".equals(uri.getAuthority())) return;
        try {
            try (Cursor cursor = context.getContentResolver().query(uri,
                    new String[]{MediaStore.Video.Media.IS_PENDING}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst() && cursor.getInt(0) == 0) {
                    // Publishing completed before a process exit: preserve the video.
                    preferences.edit().remove("unfinished").commit();
                    return;
                }
            }
            discard(uri);
        } catch (RuntimeException error) {
            // Storage can be temporarily unavailable; retain the recovery marker.
        }
    }

    private JSONArray history() {
        try { return new JSONArray(preferences.getString("history", "[]")); }
        catch (JSONException error) { return new JSONArray(); }
    }

    private JSONArray pending() throws JSONException {
        return new JSONArray(preferences.getString("transfer_pending", "[]"));
    }

    private JSONArray appendPending(JSONObject item) throws JSONException {
        JSONArray queue = pending();
        for (int i = 0; i < queue.length(); i++) {
            if (item.getString("uri").equals(queue.getJSONObject(i).getString("uri"))) return queue;
        }
        queue.put(item);
        return queue;
    }

    /** Durable queue is independent of the 50-row recent-recordings display limit. */
    List<Entry> transferEntries() throws IOException {
        synchronized (HISTORY_LOCK) {
            try {
                if (!preferences.getBoolean("transfer_migrated", false)) {
                    JSONArray queue = pending();
                    JSONArray previous = history();
                    for (int i = 0; i < previous.length(); i++) {
                        JSONObject item = previous.getJSONObject(i);
                        boolean duplicate = false;
                        for (int j = 0; j < queue.length(); j++) {
                            if (item.getString("uri").equals(queue.getJSONObject(j).getString("uri"))) duplicate = true;
                        }
                        if (duplicate) continue;
                        Uri uri = Uri.parse(item.getString("uri"));
                        item.put("destination", destination(uri));
                        item.put("completed_at", modifiedAt(uri));
                        queue.put(item);
                    }
                    if (!preferences.edit().putString("transfer_pending", queue.toString())
                            .putBoolean("transfer_migrated", true).commit()) throw new IOException("Cannot retain transfer queue");
                }
                String destination = RecordingOptions.get(context).getString("folder_uri", "");
                if (!destination.equals(preferences.getString("transfer_scanned", null))) {
                    JSONArray queue = pending();
                    discoverCompleted(queue, destination);
                    if (!preferences.edit().putString("transfer_pending", queue.toString())
                            .putString("transfer_scanned", destination).commit()) throw new IOException("Cannot retain directory scan");
                }
                List<Entry> result = new ArrayList<>();
                JSONArray queue = pending();
                for (int i = 0; i < queue.length(); i++) {
                    JSONObject item = queue.getJSONObject(i);
                    result.add(new Entry(Uri.parse(item.getString("uri")), item.getString("name"),
                            item.optLong("bytes"), item.optLong("duration"), item.getLong("completed_at"),
                            item.getString("destination")));
                }
                return result;
            } catch (JSONException | RuntimeException error) { throw new IOException("Could not read transfer queue", error); }
        }
    }

    /** Import older recordings even when they have fallen out of the recent-recordings list. */
    @SuppressWarnings("deprecation")
    private void discoverCompleted(JSONArray queue, String destination) throws IOException, JSONException {
        if (destination.isEmpty()) {
            boolean modern = Build.VERSION.SDK_INT >= 29;
            Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            String selection;
            String[] arguments;
            if (modern) {
                collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                selection = MediaStore.Video.Media.RELATIVE_PATH + "=? AND " + MediaStore.Video.Media.OWNER_PACKAGE_NAME
                        + "=? AND " + MediaStore.Video.Media.IS_PENDING + "=0";
                arguments = new String[]{FOLDER, context.getPackageName()};
            } else {
                if (needsLegacyStoragePermission(context)) throw new IOException("Allow storage access to scan saved recordings");
                selection = MediaStore.Video.Media.DATA + " LIKE ?";
                arguments = new String[]{new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                        "ScreenOhRecord").getAbsolutePath() + "/%"};
            }
            try (Cursor cursor = context.getContentResolver().query(collection, new String[]{MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_MODIFIED}, selection, arguments, null)) {
                if (cursor == null) throw new IOException("Recording directory is unavailable");
                while (cursor.moveToNext()) {
                    Uri uri = android.content.ContentUris.withAppendedId(collection, cursor.getLong(0));
                    if (cursor.getLong(3) <= 0) continue;
                    addDiscovered(queue, uri, cursor.getString(1), cursor.getLong(2), cursor.getLong(3),
                            cursor.getLong(4) * 1000, destination);
                }
            }
        } else {
            Uri tree = Uri.parse(destination);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor cursor = context.getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
                if (cursor == null) throw new IOException("Recording folder is unavailable");
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    if (!recordingName(name)) continue;
                    Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                    if (queuedOrUnfinished(queue, uri)) continue;
                    android.media.MediaMetadataRetriever metadata = new android.media.MediaMetadataRetriever();
                    try {
                        metadata.setDataSource(context, uri);
                        long duration = Long.parseLong(metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION));
                        if (duration > 0) addDiscovered(queue, uri, name, cursor.getLong(2), duration, cursor.getLong(3), destination);
                    } catch (RuntimeException ignored) { /* Incomplete or unreadable MP4 files stay local. */ }
                    finally { try { metadata.release(); } catch (IOException ignored) { } }
                }
            }
        }
    }

    private boolean recordingName(String name) {
        return name != null && name.matches("ScreenOhRecord_[0-9]{8}_[0-9]{6}_[0-9]{3}\\.mp4");
    }

    private String destination(Uri uri) {
        if (!SettingsActivity.LOCAL_AUTHORITY.equals(uri.getAuthority())) return "";
        return DocumentsContract.buildTreeDocumentUri(uri.getAuthority(), DocumentsContract.getTreeDocumentId(uri)).toString();
    }

    private boolean queuedOrUnfinished(JSONArray queue, Uri uri) throws JSONException {
        String value = uri.toString();
        if (value.equals(preferences.getString("unfinished", null))) return true;
        for (int i = 0; i < queue.length(); i++) {
            if (value.equals(queue.getJSONObject(i).getString("uri"))) return true;
        }
        return false;
    }

    private void addDiscovered(JSONArray queue, Uri uri, String name, long bytes, long duration,
            long modified, String destination) throws JSONException {
        if (!recordingName(name) || bytes <= 0 || queuedOrUnfinished(queue, uri)) return;
        JSONObject item = new JSONObject();
        item.put("uri", uri.toString()); item.put("name", name); item.put("bytes", bytes); item.put("duration", duration);
        item.put("destination", destination);
        item.put("completed_at", modified > 0 && modified <= System.currentTimeMillis() ? modified : System.currentTimeMillis());
        queue.put(item);
    }

    private long modifiedAt(Uri uri) {
        boolean media = "media".equals(uri.getAuthority());
        String column = media ? MediaStore.Video.Media.DATE_MODIFIED : DocumentsContract.Document.COLUMN_LAST_MODIFIED;
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{column}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long modified = cursor.getLong(0) * (media ? 1000 : 1);
                if (modified > 0 && modified <= System.currentTimeMillis()) return modified;
            }
        } catch (RuntimeException ignored) { /* Start the delay now if older metadata is inaccessible. */ }
        return System.currentTimeMillis();
    }

    List<Entry> entries() {
        List<Entry> result = new ArrayList<>();
        JSONArray history = history();
        for (int i = 0; i < history.length(); i++) {
            try {
                JSONObject item = history.getJSONObject(i);
                result.add(new Entry(Uri.parse(item.getString("uri")), item.getString("name"),
                        item.optLong("bytes"), item.optLong("duration")));
            } catch (JSONException ignored) { /* Ignore only the malformed history entry. */ }
        }
        return result;
    }

    /** Empty results only count as missing while the local storage/provider is accessible. */
    Availability availability(Entry entry) {
        String state = Environment.getExternalStorageState();
        if (!Environment.MEDIA_MOUNTED.equals(state) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(state))
            return Availability.UNAVAILABLE;
        boolean document = SettingsActivity.LOCAL_AUTHORITY.equals(entry.uri.getAuthority());
        if (!document && !"media".equals(entry.uri.getAuthority())) return Availability.UNAVAILABLE;
        boolean legacyMedia = !document && Build.VERSION.SDK_INT < 29;
        if (legacyMedia && !hasLegacyPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE, AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE))
            return Availability.UNAVAILABLE;
        try {
            if (document) {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(entry.uri,
                        DocumentsContract.getTreeDocumentId(entry.uri));
                try (Cursor cursor = context.getContentResolver().query(parent,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                    if (cursor == null || !cursor.moveToFirst()) return Availability.UNAVAILABLE;
                }
                // Enumerating the accessible destination distinguishes a missing document from
                // providers that return null/throw for an individual missing-document query.
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(entry.uri,
                        DocumentsContract.getTreeDocumentId(entry.uri));
                String id = DocumentsContract.getDocumentId(entry.uri);
                try (Cursor cursor = context.getContentResolver().query(children,
                        new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                    if (cursor == null) return Availability.UNAVAILABLE;
                    while (cursor.moveToNext()) {
                        if (id.equals(cursor.getString(0))) return Availability.PRESENT;
                    }
                    return Availability.MISSING;
                }
            }
            try (Cursor cursor = context.getContentResolver().query(entry.uri,
                    legacyMedia ? new String[]{MediaStore.Video.Media._ID, MediaStore.Video.Media.DATA}
                            : new String[]{MediaStore.Video.Media._ID},
                    null, null, null)) {
                if (cursor == null) return Availability.UNAVAILABLE;
                if (!cursor.moveToFirst()) return Availability.MISSING;
                if (legacyMedia) {
                    String path = cursor.getString(1);
                    if (path == null) return Availability.UNAVAILABLE;
                    File file = new File(path);
                    File parent = file.getParentFile();
                    if (parent == null || !parent.canRead()) return Availability.UNAVAILABLE;
                    return file.isFile() ? Availability.PRESENT : Availability.MISSING;
                }
                return Availability.PRESENT;
            }
        } catch (RuntimeException error) {
            // Revoked access and provider failures do not prove that a recording was deleted.
            return Availability.UNAVAILABLE;
        }
    }

    List<Uri> pruneMissing() {
        List<Uri> removed = new ArrayList<>();
        for (Entry entry : entries()) {
            if (availability(entry) == Availability.MISSING) {
                forget(entry);
                removed.add(entry.uri);
            }
        }
        return removed;
    }

    void delete(Entry entry) {
        if (availability(entry) == Availability.MISSING) { forget(entry); return; }
        try {
            if (SettingsActivity.LOCAL_AUTHORITY.equals(entry.uri.getAuthority())) {
                try {
                    if (!DocumentsContract.deleteDocument(context.getContentResolver(), entry.uri))
                        throw new IllegalStateException("Cannot delete recording");
                } catch (IOException error) { throw new IllegalStateException("Cannot delete recording", error); }
            } else if (context.getContentResolver().delete(entry.uri, null, null) == 0
                    && availability(entry) != Availability.MISSING) {
                throw new IllegalStateException("Cannot delete recording");
            }
        } catch (RuntimeException error) {
            if (availability(entry) != Availability.MISSING) throw error;
        }
        forget(entry);
    }

    private void forget(Entry entry) {
        synchronized (HISTORY_LOCK) {
            JSONArray old = history();
            JSONArray updated = new JSONArray();
            for (int i = 0; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && !entry.uri.toString().equals(item.optString("uri"))) updated.put(item);
            }
            try {
                JSONArray queue = pending();
                JSONArray kept = new JSONArray();
                for (int i = 0; i < queue.length(); i++) {
                    JSONObject item = queue.getJSONObject(i);
                    if (!entry.uri.toString().equals(item.getString("uri"))) kept.put(item);
                }
                if (!preferences.edit().putString("history", updated.toString())
                        .putString("transfer_pending", kept.toString()).commit())
                    throw new IllegalStateException("Could not update the recording list");
            } catch (JSONException error) { throw new IllegalStateException("Could not update the transfer queue", error); }
        }
    }
}
