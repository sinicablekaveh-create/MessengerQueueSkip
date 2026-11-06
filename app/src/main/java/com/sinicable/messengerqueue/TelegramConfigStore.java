package com.sinicable.messengerqueue;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Device-local encrypted storage for Telegram API credentials and the TDLib database key. */
final class TelegramConfigStore {
    private static final String PREFS = "telegram_secure_config";
    private static final String ALIAS = "three_ib_tdlib_aes_v1";

    static final class Credentials {
        final int apiId;
        final String apiHash;
        Credentials(int id, String hash) { apiId=id; apiHash=hash; }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean save(Context c, String rawId, String rawHash) {
        try {
            int id = Integer.parseInt(rawId == null ? "" : rawId.trim());
            String hash = rawHash == null ? "" : rawHash.trim();
            if (id <= 0 || !hash.matches("(?i)[0-9a-f]{32}")) return false;
            SecretKey key = getKey();
            return prefs(c).edit()
                    .putString("api_id", encrypt(String.valueOf(id), key))
                    .putString("api_hash", encrypt(hash, key))
                    .commit();
        } catch (Exception e) {
            return false;
        }
    }

    static Credentials read(Context c) {
        try {
            SharedPreferences p = prefs(c);
            String id = p.getString("api_id", "");
            String hash = p.getString("api_hash", "");
            if (id.isEmpty() || hash.isEmpty()) return null;
            int apiId = Integer.parseInt(decrypt(id, getKey()));
            String apiHash = decrypt(hash, getKey());
            if (apiId <= 0 || !apiHash.matches("(?i)[0-9a-f]{32}")) return null;
            return new Credentials(apiId, apiHash);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean hasCredentials(Context c) { return read(c) != null; }

    static byte[] databaseKey(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        SecretKey key = getKey();
        String saved = p.getString("database_key", "");
        if (!saved.isEmpty()) {
            byte[] decoded = Base64.decode(decrypt(saved, key), Base64.NO_WRAP);
            if (decoded.length == 32) return decoded;
        }
        byte[] fresh = new byte[32];
        new SecureRandom().nextBytes(fresh);
        String encoded = Base64.encodeToString(fresh, Base64.NO_WRAP);
        if (!p.edit().putString("database_key", encrypt(encoded, key)).commit())
            throw new IllegalStateException("Could not persist TDLib database key");
        return fresh;
    }

    private static SecretKey getKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) {
            java.security.Key existing = store.getKey(ALIAS, null);
            if (existing instanceof SecretKey) return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build();
        generator.init(spec);
        return generator.generateKey();
    }

    private static String encrypt(String value, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] iv = cipher.getIV();
        byte[] combined = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, combined, 0, iv.length);
        System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);
        return Base64.encodeToString(combined, Base64.NO_WRAP);
    }

    private static String decrypt(String value, SecretKey key) throws Exception {
        byte[] combined = Base64.decode(value, Base64.NO_WRAP);
        if (combined.length < 13) throw new IllegalArgumentException("Invalid encrypted value");
        byte[] iv = Arrays.copyOfRange(combined, 0, 12);
        byte[] encrypted = Arrays.copyOfRange(combined, 12, combined.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }

    private TelegramConfigStore() {}
}
