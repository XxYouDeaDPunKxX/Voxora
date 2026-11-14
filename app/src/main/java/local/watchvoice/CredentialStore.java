package local.watchvoice;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class CredentialStore {
    private static final String ALIAS = "watch-voice-session";
    private static final Object FILE_LOCK = new Object();
    final Context context;
    CredentialStore(Context context) { this.context = context; }

    static Map<String, String> cookies(JSONObject value) throws Exception {
        Map<String, String> result = new TreeMap<>();
        JSONObject items = value.optJSONObject("sessionCookies");
        if (items != null) for (Iterator<String> it = items.keys(); it.hasNext();) {
            String key = it.next(); result.put(key, items.getString(key));
        }
        VoiceProtocol.cookieHeader(result, value.getString("deviceId"));
        return result;
    }
    static long tokenExpiry(JSONObject value) {
        return tokenTimestamp(value, "exp");
    }
    static long tokenIssuedAt(JSONObject value) {
        return tokenTimestamp(value, "iat");
    }
    private static long tokenTimestamp(JSONObject value, String claim) {
        try {
            String[] parts = value.getString("accessToken").split("\\.");
            return new JSONObject(new String(Base64.decode(parts[1], Base64.URL_SAFE), StandardCharsets.UTF_8)).getLong(claim);
        } catch (Exception ignored) { return 0; }
    }
    private static void validate(JSONObject value) throws Exception {
        String token = value.optString("accessToken");
        Map<String, String> sessionCookies = cookies(value);
        if (token.isEmpty() && sessionCookies.isEmpty()) throw new IOException("credential_missing");
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))
            throw new IOException("credential_format");
        if (!value.optString("accountId").isEmpty()) VoiceProtocol.headerValue(value.getString("accountId"));
    }
    synchronized JSONObject load() throws Exception {
        synchronized (FILE_LOCK) {
        File incoming = new File(context.getFilesDir(), "provision.json");
        if (incoming.exists()) {
            try {
                JSONObject data = new JSONObject(new String(readLimited(new FileInputStream(incoming), 65536), StandardCharsets.UTF_8));
                validate(data); save(data);
            } finally {
                if (!incoming.delete()) throw new IOException("provision_cleanup");
            }
        }
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "session.enc"));
        byte[] bytes = readLimited(file.openRead(), 70000);
        if (bytes.length < 29) throw new IOException("credential_invalid");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOfRange(bytes, 0, 12)));
        JSONObject data = new JSONObject(new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8));
        validate(data);
        return data;
        }
    }
    synchronized void save(JSONObject data) throws Exception {
        synchronized (FILE_LOCK) {
        validate(data);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        AtomicFile file = new AtomicFile(new File(context.getNoBackupFilesDir(), "session.enc"));
        FileOutputStream out = file.startWrite();
        try {
            out.write(cipher.getIV());
            out.write(cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
            file.finishWrite(out);
        } catch (Exception e) { file.failWrite(out); throw e; }
        }
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    static byte[] readLimited(InputStream stream, int limit) throws IOException {
        try (InputStream input = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) != -1) {
                if (out.size() + read > limit) throw new IOException("response_size");
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }
}
