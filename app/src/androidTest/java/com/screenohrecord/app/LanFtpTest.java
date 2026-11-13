package com.screenohrecord.app;

import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.UUID;
import org.json.JSONObject;

/** Explicit LAN-server test. Credentials are consumed from a disposable local handoff file. */
final class LanFtpTest {
    private final Instrumentation instrumentation;
    LanFtpTest(Instrumentation instrumentation) { this.instrumentation = instrumentation; }

    void run() throws Exception {
        Context context = instrumentation.getTargetContext();
        File handoff = new File(context.getExternalFilesDir(null), "lan-ftp-test.json");
        JSONObject credentials;
        try { credentials = new JSONObject(new String(Files.readAllBytes(handoff.toPath()), StandardCharsets.UTF_8).trim()); }
        finally { if (!handoff.delete()) throw new java.io.IOException("Could not remove temporary test credentials"); }
        TransferSettings.Config config = new TransferSettings.Config("ftp", credentials.getString("host"), 21, "", "", "",
                credentials.getString("username"), credentials.getString("password"), 60, false, "");
        try (android.os.ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand("svc wifi enable");
                InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor)) {
            byte[] buffer = new byte[4096];
            while (input.read(buffer) != -1) { }
        }
        long deadline = SystemClock.elapsedRealtime() + 45000;
        while (TransferConnection.wifi(context) == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100);
        TransferEngine.test(context, config, () -> false);
        pass("LAN FTP login, small upload, checksum, rename and cleanup");
        String name = "ScreenOhRecord-test-" + UUID.randomUUID() + ".uploading";
        String finalName = name + ".verified";
        byte[] payload = new byte[1048576];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        byte[] expected = MessageDigest.getInstance("SHA-256").digest(payload);
        try (TransferConnection remote = new TransferConnection(context, config, () -> false)) {
            try {
                try (OutputStream output = remote.write(name)) { output.write(payload); }
                remote.finishStream();
                for (int attempt = 0; attempt < 5; attempt++) {
                    long count = 0;
                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    try (InputStream input = remote.read(name)) {
                        byte[] buffer = new byte[65536];
                        int read;
                        while ((read = input.read(buffer)) != -1) { remote.check(); digest.update(buffer, 0, read); count += read; }
                    }
                    remote.finishStream();
                    if (count != payload.length || !Arrays.equals(expected, digest.digest()))
                        throw new java.io.IOException("LAN FTP payload mismatch. Received " + count + " of " + payload.length + " bytes");
                }
                remote.rename(name, finalName);
                if (remote.size(finalName) != payload.length) throw new java.io.IOException("LAN renamed file size mismatch");
                pass("LAN FTP 1 MiB upload and five exact checksum read-backs plus rename");
            } finally {
                if (remote.exists(name)) remote.remove(name);
                if (remote.exists(finalName)) remote.remove(finalName);
            }
        }
        pass("LAN FTP test files removed");
    }

    private void pass(String value) {
        Bundle message = new Bundle(); message.putString("stream", "PASS: " + value + "\n"); instrumentation.sendStatus(0, message);
    }
}
