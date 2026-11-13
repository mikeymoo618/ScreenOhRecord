package com.screenohrecord.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Explicit, device-local transfer preferences. Passwords use the Android Keystore. */
final class TransferSettings {
    private static final String KEY_ALIAS = "ScreenOhRecord.transfer.v1";
    private TransferSettings() { }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("transfers", Context.MODE_PRIVATE);
    }

    static final class Config {
        final String protocol, host, share, folder, domain, username, password, revision;
        final int port, delayMinutes;
        final boolean enabled;

        Config(String protocol, String host, int port, String share, String folder, String domain,
                String username, String password, int delayMinutes, boolean enabled, String revision) throws IOException {
            this.protocol = protocol;
            this.host = host.trim();
            this.port = port;
            this.share = share.trim();
            this.folder = folder.trim().replace('\\', '/');
            this.domain = domain.trim();
            this.username = username;
            this.password = password;
            this.delayMinutes = delayMinutes;
            this.enabled = enabled;
            this.revision = revision;
            if (!"smb".equals(protocol) && !"ftp".equals(protocol)) throw new IOException("Choose SMB or FTP");
            if (this.host.isEmpty() || !this.host.matches("[A-Za-z0-9._-]+"))
                throw new IOException("Enter a server hostname or IPv4 address, without a path");
            if (port < 1 || port > 65535) throw new IOException("Port must be between 1 and 65535");
            if (delayMinutes < 1) throw new IOException("Delay must be at least 1 minute");
            if ("smb".equals(protocol) && (this.share.isEmpty() || this.share.matches(".*[/\\\\].*")))
                throw new IOException("Enter the SMB share name separately from the subfolder");
            if ("smb".equals(protocol) && (this.share.contains("#") || this.share.contains("?")
                    || this.folder.contains("#") || this.folder.contains("?")))
                throw new IOException("SMB share and folder paths cannot contain # or ?");
            for (String value : new String[]{this.folder, this.share, this.username, this.password, this.domain}) {
                if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\0') >= 0)
                    throw new IOException("Connection fields cannot contain control characters");
            }
            for (String part : this.folder.split("/")) {
                if ("..".equals(part) || ".".equals(part)) throw new IOException("Use a folder path without dot segments");
            }
        }
    }

    static Config load(Context context) throws IOException {
        SharedPreferences p = preferences(context);
        return new Config(p.getString("protocol", "smb"), p.getString("host", ""), p.getInt("port", 445),
                p.getString("share", ""), p.getString("folder", ""), p.getString("domain", ""),
                p.getString("username", ""), decrypt(p.getString("password", "")),
                p.getInt("delay", 60), p.getBoolean("enabled", false), p.getString("revision", ""));
    }

    static String password(Context context) throws IOException {
        return decrypt(preferences(context).getString("password", ""));
    }

    static void save(Context context, Config config) throws IOException {
        String encrypted = encrypt(config.password);
        boolean saved = preferences(context).edit().putString("protocol", config.protocol)
                .putString("host", config.host).putInt("port", config.port).putString("share", config.share)
                .putString("folder", config.folder).putString("domain", config.domain)
                .putString("username", config.username).putString("password", encrypted)
                .putInt("delay", config.delayMinutes).putBoolean("enabled", config.enabled)
                .putString("revision", UUID.randomUUID().toString()).commit();
        if (!saved) throw new IOException("Could not save transfer settings");
        TransferScheduler.schedule(context);
    }

    private static SecretKey key() throws GeneralSecurityException, IOException {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) return (SecretKey) store.getKey(KEY_ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static String encrypt(String password) throws IOException {
        if (password.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(cipher.doFinal(password.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        } catch (GeneralSecurityException error) { throw new IOException("Could not protect the saved password", error); }
    }

    private static String decrypt(String encrypted) throws IOException {
        if (encrypted.isEmpty()) return "";
        try {
            String[] parts = encrypted.split(":", -1);
            if (parts.length != 2) throw new IOException("Re-enter the transfer password");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new IOException("Re-enter the transfer password", error);
        }
    }
}
