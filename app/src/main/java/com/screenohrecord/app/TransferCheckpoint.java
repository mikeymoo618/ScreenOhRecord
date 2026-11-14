package com.screenohrecord.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import java.io.IOException;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.json.JSONException;
import org.json.JSONObject;

/** Private SHA-256 continuation state, valid only while the remote size and timestamp match. */
final class TransferCheckpoint {
    private final SharedPreferences preferences;
    private final String key;
    private final String fingerprint;
    SHA256Digest digest = new SHA256Digest();
    long offset;

    TransferCheckpoint(Context context, String key, String fingerprint, long size) {
        preferences = context.getSharedPreferences("transfer_verification", Context.MODE_PRIVATE);
        this.key = key;
        this.fingerprint = fingerprint;
        String saved = preferences.getString(key, "");
        if (saved.isEmpty() || fingerprint.isEmpty()) return;
        try {
            JSONObject state = new JSONObject(saved);
            long count = state.getLong("offset");
            if (!fingerprint.equals(state.getString("fingerprint")) || count < 0 || count > size) return;
            byte[] encoded = Base64.decode(state.getString("digest"), Base64.NO_WRAP);
            if (encoded.length < 16 || java.nio.ByteBuffer.wrap(encoded, 8, 8).getLong() != count) return;
            digest = new SHA256Digest(encoded);
            offset = count;
        } catch (JSONException | RuntimeException error) {
            preferences.edit().remove(key).apply();
        }
    }

    void save() throws IOException {
        if (fingerprint.isEmpty()) return;
        try {
            JSONObject state = new JSONObject().put("offset", offset).put("fingerprint", fingerprint)
                    .put("digest", Base64.encodeToString(digest.getEncodedState(), Base64.NO_WRAP));
            if (!preferences.edit().putString(key, state.toString()).commit())
                throw new IOException("Could not save verification progress");
        } catch (JSONException error) { throw new IOException("Could not encode verification progress", error); }
    }

    void clear() { preferences.edit().remove(key).apply(); }

    String checksum() {
        byte[] result = new byte[32];
        new SHA256Digest(digest).doFinal(result, 0);
        StringBuilder text = new StringBuilder(64);
        for (byte value : result) text.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return text.toString();
    }
}
