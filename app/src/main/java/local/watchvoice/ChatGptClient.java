package local.watchvoice;

import android.util.Log;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixed destinations, no redirects, no credential or response-body logging. */
final class ChatGptClient {
    private static final HttpRateLimits RATE_LIMITS=new HttpRateLimits();
    static long retrySeconds(String kind){return RATE_LIMITS.remaining(kind,TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));}
    static long voiceRetrySeconds(){return Math.max(retrySeconds("detail"),retrySeconds("voice"));}
    private static void checkRateLimit(String kind)throws IOException{if(retrySeconds(kind)>0)throw new IOException(kind+"_http_429");}
    private void rateLimited(HttpsURLConnection connection,String kind){
        RATE_LIMITS.record(kind,connection.getHeaderField("Retry-After"),connection.getHeaderField("Date"),System.currentTimeMillis(),TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
        String category="unavailable";try{category=HttpRateLimits.category(activeIo.readErrorLimited(8192));}catch(IOException ignored){}
        Log.i("WatchVoice","rate_limit operation="+kind+" retry_after_seconds="+retrySeconds(kind)+" category="+category);
    }
    private final CredentialStore store;
    private static final ReentrantLock AUTH_LOCK = new ReentrantLock();
    private HttpsURLConnection active;
    private HttpIo activeIo;
    private volatile boolean cancelled;
    private long deadline;
    ChatGptClient(CredentialStore store) { this.store = store; }
    /** Internal reuse of the serialized session refresh for the dictation client. */
    JSONObject authenticateForOperation(long deadlineNanos) throws Exception {
        deadline=deadlineNanos;
        return authentication();
    }

    synchronized void cancel() {
        cancelled = true;
        if (activeIo != null) activeIo.cancel();
    }
    private synchronized HttpsURLConnection open(String path) throws Exception {
        check();
        URL url = new URL(VoiceProtocol.ORIGIN + path);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setRequestProperty("User-Agent", VoiceProtocol.UA);
        connection.setRequestProperty("Origin", VoiceProtocol.ORIGIN);
        connection.setRequestProperty("Referer", VoiceProtocol.ORIGIN + "/");
        active = connection;
        activeIo = new HttpIo(connection, deadline);
        return connection;
    }
    private synchronized void release(HttpsURLConnection c) {
        if (active == c) {
            activeIo.close(); activeIo = null; active = null;
        } else c.disconnect();
    }
    private byte[] readBody(HttpsURLConnection c, int limit) throws IOException {
        HttpIo operation;
        synchronized (this) {
            check(); if (active != c) throw new IOException("cancelled");
            operation = activeIo;
        }
        return operation.readLimited(limit);
    }
    private JSONObject authentication() throws Exception {
        while (!AUTH_LOCK.tryLock(100, TimeUnit.MILLISECONDS)) check();
        try { check(); return lockedAuthentication(); } finally { AUTH_LOCK.unlock(); }
    }
    private JSONObject lockedAuthentication() throws Exception {
        JSONObject auth = store.load();
        Log.i("WatchVoice", "auth_cached_seconds=" + (CredentialStore.tokenExpiry(auth) - System.currentTimeMillis() / 1000));
        if (CredentialStore.tokenExpiry(auth) > System.currentTimeMillis() / 1000 + 120) return auth;
        Map<String, String> cookies = CredentialStore.cookies(auth);
        if (cookies.isEmpty()) throw new IOException("session_expired");
        HttpsURLConnection c = open("/api/auth/session?refresh=true&reason=token_expired");
        try {
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("X-OpenAI-Target-Path", "/api/auth/session");
            c.setRequestProperty("X-OpenAI-Target-Route", "/api/auth/session");
            long issuedAt = CredentialStore.tokenIssuedAt(auth);
            if (issuedAt > 0) c.setRequestProperty("X-OpenAI-Failed-Access-Token-Iat", Long.toString(issuedAt));
            c.setRequestProperty("Cookie", VoiceProtocol.cookieHeader(cookies, auth.getString("deviceId")));
            c.setRequestProperty("oai-device-id", auth.getString("deviceId"));
            int code = activeIo.responseCode(); Log.i("WatchVoice", "auth_http=" + code);
            if (code != 200) throw new IOException("auth_http_" + code);
            JSONObject body = new JSONObject(new String(readBody(c, 65536), StandardCharsets.UTF_8));
            Log.i("WatchVoice", "auth_response_token_present=" + (body.opt("accessToken") instanceof String)
                + " refresh_rejected=" + "RefreshAccessTokenError".equals(body.optString("error"))
                + " response_date_seconds=" + c.getDate() / 1000
                + " response_age_seconds=" + c.getHeaderFieldInt("Age", -1));
            auth.put("accessToken", SessionRefresh.accessToken(body));
            Log.i("WatchVoice", "auth_returned_seconds=" + (CredentialStore.tokenExpiry(auth) - System.currentTimeMillis() / 1000));
            if (body.optJSONObject("account") != null) auth.put("accountId", body.getJSONObject("account").getString("id"));
            if (CredentialStore.tokenExpiry(auth) < System.currentTimeMillis() / 1000 + 60) throw new IOException("auth_token_invalid");
            Map<String, String> rotated = new TreeMap<>();
            for (var entry : c.getHeaderFields().entrySet()) {
                if (!"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
                for (String line : entry.getValue()) {
                    String first = line.split(";", 2)[0]; int equals = first.indexOf('=');
                    if (equals <= 0) continue;
                    String name = first.substring(0, equals), value = first.substring(equals + 1);
                    if (VoiceProtocol.sessionCookieName(name) && !value.isEmpty()) rotated.put(name, value);
                }
            }
            if (!rotated.isEmpty()) {
                if (rotated.keySet().stream().anyMatch(n -> n.startsWith(VoiceProtocol.COOKIE + "."))) rotated.remove(VoiceProtocol.COOKIE);
                VoiceProtocol.cookieHeader(rotated, auth.getString("deviceId"));
                auth.put("sessionCookies", new JSONObject(rotated));
            }
            synchronized (this) {
                // The server may already have rotated its cookie. Keep the validated
                // credentials even when the caller cancelled the remaining operation.
                store.save(auth);
                check();
            }
            return auth;
        } finally { release(c); }
    }
    String answer(String sdp, String conversationId, String parentMessageId, VoiceOptions options) throws Exception {
        checkRateLimit("voice");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        JSONObject auth = authentication();
        String boundary = "WatchVoice-" + UUID.randomUUID();
        TimeZone zone = TimeZone.getTimeZone("Europe/Rome");
        byte[] body = VoiceProtocol.multipart(boundary, sdp, VoiceProtocol.session(UUID.randomUUID().toString(), zone.getID(), -zone.getOffset(System.currentTimeMillis()) / 60000, conversationId, parentMessageId, options));
        HttpsURLConnection c = open(VoiceProtocol.PATH + "?dcid=0");
        try {
            c.setRequestMethod("POST"); c.setDoOutput(true);
            c.setRequestProperty("Authorization", "Bearer " + VoiceProtocol.headerValue(auth.getString("accessToken")));
            if (!auth.optString("accountId").isEmpty()) c.setRequestProperty("chatgpt-account-id", VoiceProtocol.headerValue(auth.getString("accountId")));
            c.setRequestProperty("Accept", "application/sdp");
            c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            c.setRequestProperty("OAI-Language", options.language);
            c.setRequestProperty("OAI-Device-Id", auth.getString("deviceId"));
            c.setRequestProperty("OAI-Client-Version", VoiceProtocol.CLIENT_VERSION);
            c.setRequestProperty("OAI-Client-Build-Number", VoiceProtocol.CLIENT_BUILD);
            c.setRequestProperty("OAI-Session-Id", UUID.randomUUID().toString());
            c.setRequestProperty("X-OpenAI-Target-Path", VoiceProtocol.PATH);
            c.setRequestProperty("X-OpenAI-Target-Route", VoiceProtocol.PATH);
            c.setFixedLengthStreamingMode(body.length);
            activeIo.send(body);
            int code = activeIo.responseCode();
            Log.i("WatchVoice", "voice_http=" + code + " edge_challenge=" + "challenge".equals(c.getHeaderField("cf-mitigated")));
            if(code==429)rateLimited(c,"voice");
            if (code < 200 || code >= 300) throw new IOException("voice_http_" + code);
            String answer = new String(readBody(c, 128000), StandardCharsets.UTF_8);
            if (!answer.stripLeading().startsWith("v=0")) throw new IOException("answer_invalid");
            return answer;
        } finally { release(c); }
    }
    ConversationData.Page list(int offset) throws Exception {
        if (offset < 0) throw new IllegalArgumentException("offset_invalid");
        return ConversationData.parsePage(get("/backend-api/conversations?exclude_conversation_origin=tpp&expand=false&hide_snorlax=false&is_archived=false&is_starred=false&limit=20&order=updated&offset=" + offset, "list"));
    }
    ConversationData.Detail detail(String id) throws Exception {
        ConversationData.identifier(id);
        return ConversationData.parseDetail(get("/backend-api/conversations/" + id + "?num_turns=10&include_has_versions=true", "detail"), id);
    }
    private void check() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new IOException("cancelled");
        if (deadline != 0 && System.nanoTime() > deadline) throw new IOException("operation_timeout");
    }
    private JSONObject get(String path, String kind) throws Exception {
        checkRateLimit(kind);
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
            JSONObject auth = authentication();
            HttpsURLConnection c = open(path);
            try {
                c.setRequestMethod("GET"); c.setRequestProperty("Accept", "application/json");
                c.setRequestProperty("Authorization", "Bearer " + VoiceProtocol.headerValue(auth.getString("accessToken")));
                if (!auth.optString("accountId").isEmpty()) c.setRequestProperty("chatgpt-account-id", VoiceProtocol.headerValue(auth.getString("accountId")));
                Map<String, String> cookies = CredentialStore.cookies(auth);
                if (!cookies.isEmpty()) c.setRequestProperty("Cookie", VoiceProtocol.cookieHeader(cookies, auth.getString("deviceId")));
                c.setRequestProperty("OAI-Language", "it-IT"); c.setRequestProperty("OAI-Device-Id", VoiceProtocol.headerValue(auth.getString("deviceId")));
                c.setRequestProperty("OAI-Client-Version", VoiceProtocol.CLIENT_VERSION); c.setRequestProperty("OAI-Client-Build-Number", VoiceProtocol.CLIENT_BUILD);
                int code = activeIo.responseCode(); Log.i("WatchVoice", kind + "_http=" + code + " edge_challenge=" + "challenge".equals(c.getHeaderField("cf-mitigated")));
                if(code==429)rateLimited(c,kind);
                if (code != 200) throw new IOException(kind + "_http_" + code);
                JSONObject value = new JSONObject(new String(readBody(c, kind.equals("list") ? 256000 : 2000000), StandardCharsets.UTF_8));
                check(); return value;
            } finally { release(c); }
    }
}
