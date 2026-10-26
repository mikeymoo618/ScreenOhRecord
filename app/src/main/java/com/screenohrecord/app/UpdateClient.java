package com.screenohrecord.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import javax.net.ssl.HttpsURLConnection;

/** Sole network client: fixed public GitHub release source, no access to recordings. */
final class UpdateClient {
    static final String REPOSITORY = "https://github.com/mikeymoo618/ScreenOhRecord";
    static final String MANIFEST = REPOSITORY + "/releases/latest/download/update.json";
    static final long MAX_APK = 100L * 1024 * 1024;
    private static boolean busy;
    private UpdateClient() { }

    // Calls originate on Android's main thread, as do capture/service state transitions.
    static synchronized boolean begin() {
        if (busy || RecordingService.status().active() || CaptureActivity.isRequestActive()) return false;
        busy = true; return true;
    }
    static synchronized void end() { busy = false; }
    static synchronized boolean isBusy() { return busy; }

    static final class Release {
        final int versionCode;
        final String versionName, apkUrl, sha256, notes;
        final long size;
        Release(int code, String name, String url, String hash, long size, String notes) {
            this.versionCode = code; this.versionName = name; this.apkUrl = url;
            this.sha256 = hash; this.size = size; this.notes = notes;
        }
    }
    static Release parse(String json) throws IOException {
        try {
            JSONObject object = new JSONObject(json.startsWith("\uFEFF") ? json.substring(1) : json);
            if (object.getInt("schema") != 1) throw new IOException("Unsupported update metadata format");
            int code = object.getInt("versionCode");
            String name = object.getString("versionName");
            String url = object.getString("apkUrl");
            String hash = object.getString("sha256").toLowerCase(Locale.ROOT);
            long size = object.getLong("size");
            String notes = object.optString("notes", "");
            if (code <= 0 || !name.matches("[0-9]+\\.[0-9]+\\.[0-9]+")
                    || !hash.matches("[0-9a-f]{64}") || size <= 0 || size > MAX_APK || notes.length() > 16000)
                throw new IOException("Invalid update metadata");
            String expected = REPOSITORY + "/releases/download/v" + name + "/ScreenOhRecord-" + name + ".apk";
            if (!expected.equals(url)) throw new IOException("Update APK is outside the configured release repository");
            return new Release(code, name, url, hash, size, notes);
        } catch (org.json.JSONException error) { throw new IOException("Invalid update metadata", error); }
    }
    static Release check(Context context, BooleanSupplier cancelled) throws IOException {
        return check(context, cancelled, false);
    }
    static Release check(Context context, BooleanSupplier cancelled, boolean wifiOnly) throws IOException {
        ensureAllowed(cancelled);
        Network wifi = null;
        if (wifiOnly) {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            wifi = manager.getActiveNetwork();
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(wifi);
            if (wifi == null || capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                throw new IOException("Weekly checks require unmetered Wi-Fi");
        }
        String json = new String(readSmall(MANIFEST, 64 * 1024, cancelled, wifi), StandardCharsets.UTF_8);
        Release release = parse(json);
        context.getSharedPreferences("updates", Context.MODE_PRIVATE).edit()
                .putLong("last_check", System.currentTimeMillis()).putString("cached_release", json)
                .putString("last_result", release.versionCode > BuildConfig.VERSION_CODE
                        ? "Update available: " + release.versionName : "You have the latest published version.").apply();
        return release;
    }
    static Release cached(Context context) {
        String json = context.getSharedPreferences("updates", Context.MODE_PRIVATE).getString("cached_release", null);
        if (json == null) return null;
        try { return parse(json); } catch (IOException error) { return null; }
    }
    static boolean unmeteredWifi(Context context) {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(manager.getActiveNetwork());
        return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private static void ensureAllowed(BooleanSupplier cancelled) throws IOException {
        if (!isBusy() || cancelled.getAsBoolean()) throw new IOException("Update request cancelled");
        if (RecordingService.status().active() || CaptureActivity.isRequestActive())
            throw new IOException("Updates are paused while recording");
    }
    static boolean allowedHost(URL url) {
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) return false;
        String host = url.getHost();
        return "github.com".equals(host) || "release-assets.githubusercontent.com".equals(host)
                || "objects.githubusercontent.com".equals(host);
    }
    private static HttpsURLConnection open(String address, BooleanSupplier cancelled) throws IOException {
        return open(address, cancelled, null);
    }
    private static HttpsURLConnection open(String address, BooleanSupplier cancelled, Network network) throws IOException {
        URL url = new URL(address);
        for (int redirects = 0; redirects < 6; redirects++) {
            ensureAllowed(cancelled);
            if (!allowedHost(url)) throw new IOException("Update redirected outside approved GitHub hosts");
            HttpsURLConnection connection = (HttpsURLConnection) (network == null ? url.openConnection() : network.openConnection(url));
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", "ScreenOhRecord/" + BuildConfig.VERSION_NAME);
            connection.setRequestProperty("Accept-Encoding", "identity");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("Invalid GitHub redirect");
                    url = new URL(url, location); connection.disconnect(); continue;
                }
                if (code == 404) throw new IOException("No published update file was found on GitHub.");
                if (code != 200) throw new IOException("GitHub update request failed (HTTP " + code + ")");
                return connection;
            } catch (IOException | RuntimeException error) { connection.disconnect(); throw error; }
        }
        throw new IOException("Too many GitHub redirects");
    }
    private static byte[] readSmall(String url, int limit, BooleanSupplier cancelled, Network network) throws IOException {
        HttpsURLConnection connection = open(url, cancelled, network);
        try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                ensureAllowed(cancelled);
                if (output.size() + count > limit) throw new IOException("Update metadata is too large");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        } finally { connection.disconnect(); }
    }
    static File download(Context context, Release release, BooleanSupplier cancelled) throws IOException {
        ensureAllowed(cancelled);
        if (release.versionCode <= BuildConfig.VERSION_CODE) throw new IOException("This update is not newer than the installed app");
        File folder = new File(context.getFilesDir(), "updates");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create private update storage");
        File partial = new File(folder, "download.part");
        File ready = new File(folder, "verified.apk");
        HttpsURLConnection connection = open(release.apkUrl, cancelled);
        boolean success = false;
        try {
            long announced = connection.getContentLengthLong();
            if (announced > MAX_APK || (announced >= 0 && announced != release.size))
                throw new IOException("APK download size does not match release metadata");
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768]; int count; long received = 0;
                while ((count = input.read(buffer)) != -1) {
                    ensureAllowed(cancelled); received += count;
                    if (received > release.size || received > MAX_APK) throw new IOException("APK download exceeded expected size");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            verify(context, partial, release, true);
            if (ready.exists() && !ready.delete()) throw new IOException("Cannot replace old update download");
            if (!partial.renameTo(ready)) throw new IOException("Cannot retain verified update download");
            success = true;
            return ready;
        } finally {
            connection.disconnect();
            if (!success && partial.exists() && !partial.delete()) android.util.Log.w("ScreenOhRecord", "Could not remove incomplete APK download");
        }
    }
    static String hash(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[32768]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return hex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
    private static Set<String> signers(PackageInfo info) throws IOException {
        if (info == null || info.signingInfo == null) throw new IOException("APK has no verifiable signing identity");
        Set<String> result = new HashSet<>();
        for (Signature signature : info.signingInfo.getApkContentsSigners()) {
            try { result.add(hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()))); }
            catch (java.security.NoSuchAlgorithmException error) { throw new IOException(error); }
        }
        if (result.isEmpty()) throw new IOException("APK has no signing certificates");
        return result;
    }
    static void verify(Context context, File file, Release release, boolean requireNewer) throws IOException {
        if (file.length() != release.size || !hash(file).equals(release.sha256)) throw new IOException("APK size or SHA-256 verification failed");
        PackageManager manager = context.getPackageManager();
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        try {
            PackageInfo installed = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (archive == null || !context.getPackageName().equals(archive.packageName)
                    || archive.getLongVersionCode() != release.versionCode || !release.versionName.equals(archive.versionName)
                    || archive.applicationInfo == null || archive.applicationInfo.minSdkVersion > android.os.Build.VERSION.SDK_INT
                    || (requireNewer && archive.getLongVersionCode() <= installed.getLongVersionCode()))
                throw new IOException("APK package or version does not match the requested upgrade");
            if (!signers(installed).equals(signers(archive))) throw new IOException("APK signing certificate does not match this app");
        } catch (PackageManager.NameNotFoundException error) { throw new IOException("Installed package could not be verified", error); }
    }
}
