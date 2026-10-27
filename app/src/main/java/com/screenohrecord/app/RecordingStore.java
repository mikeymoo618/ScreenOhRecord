package com.screenohrecord.app;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Owns only files created by this app. No media-reading permission is needed. */
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
        Entry(Uri uri, String name, long bytes, long duration) {
            this.uri = uri; this.name = name; this.bytes = bytes; this.duration = duration;
        }
    }

    RecordingStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = this.context.getSharedPreferences("local_recordings", Context.MODE_PRIVATE);
    }

    Output create() throws IOException {
        String name = "ScreenOhRecord_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT)
                .format(new Date()) + ".mp4";
        String folder = RecordingOptions.get(context).getString("folder_uri", null);
        if (folder != null) return createInFolder(Uri.parse(folder), name);
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
        if ("media".equals(output.uri.getAuthority())) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.IS_PENDING, 0);
            if (context.getContentResolver().update(output.uri, values, null, null) != 1) {
                throw new IOException("Cannot publish the saved recording");
            }
        } else if (!preferences.edit().putString("finalized_document", output.uri.toString()).commit()) {
            throw new IOException("Video saved, but its recovery state could not be stored");
        }
        try {
            JSONObject item = new JSONObject();
            item.put("uri", output.uri.toString());
            item.put("name", output.name);
            item.put("bytes", Math.max(0, bytes));
            item.put("duration", Math.max(0, duration));
            synchronized (HISTORY_LOCK) {
                JSONArray old = history();
                JSONArray updated = new JSONArray();
                updated.put(item);
                // Display history is bounded; older video files are never deleted here.
                for (int i = 0; i < Math.min(old.length(), 49); i++) updated.put(old.get(i));
                if (!preferences.edit().putString("history", updated.toString())
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
            else context.getContentResolver().delete(uri, null, null);
            return preferences.edit().remove("unfinished").remove("finalized_document").commit();
        } catch (IOException | RuntimeException error) {
            // Keep marker for the next launch. Never claim cleanup succeeded on failure.
            return false;
        }
    }

    void recoverInterrupted() {
        String value = preferences.getString("unfinished", null);
        if (value == null) return;
        Uri uri = Uri.parse(value);
        if (SettingsActivity.LOCAL_AUTHORITY.equals(uri.getAuthority()) && DocumentsContract.isDocumentUri(context, uri)) {
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
                    new String[]{MediaStore.Video.Media._ID},
                    null, null, null)) {
                if (cursor == null) return Availability.UNAVAILABLE;
                return cursor.moveToFirst() ? Availability.PRESENT : Availability.MISSING;
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
            preferences.edit().putString("history", updated.toString()).apply();
        }
    }
}
