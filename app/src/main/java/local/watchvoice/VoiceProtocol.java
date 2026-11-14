package local.watchvoice;

import org.json.JSONObject;
import org.json.JSONArray;
import org.json.JSONTokener;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/** Wire fields ported from the locally verified web-voice-spike, MIT attribution in NOTICE. */
final class VoiceProtocol {
    static final class Caption {
        final String kind, text; final boolean fresh;
        Caption(String kind, String text, boolean fresh) { this.kind = kind; this.text = text; this.fresh = fresh; }
    }
    static final class LiveCaptions {
        private String messageId = "", activeRole = "", activeText = "", lastOperation = "";
        private void discardContext() { messageId = ""; activeRole = ""; activeText = ""; }
        private boolean invalidatesContext(String path) {
            if (path.isEmpty() || path.equals("/") || path.equals("/message") || path.equals("/message/metadata")) return true;
            for (String field : new String[]{"/message/id", "/message/author", "/message/channel", "/message/recipient", "/message/content", "/message/metadata/is_visually_hidden_from_conversation"})
                if (path.equals(field) || path.startsWith(field + "/")) return true;
            return false;
        }
        Caption accept(JSONObject event) {
            if (event == null || !event.optString("type").equals("chat_message_delta")) return null;
            JSONObject payload = event.optJSONObject("payload");
            JSONObject delta = payload == null ? null : payload.optJSONObject("delta");
            if (delta == null) return null;
            String operation = delta.optString("o", lastOperation); lastOperation = operation;
            if (operation.equals("patch")) {
                JSONArray patches = delta.optJSONArray("v");
                if (patches == null || patches.length() > 32) { discardContext(); return null; }
                if (activeRole.isEmpty()) return null;
                boolean changed = false;
                for (int i = 0; i < patches.length(); i++) {
                    JSONObject patch = patches.optJSONObject(i);
                    if (patch == null) { discardContext(); return null; }
                    if (!patch.optString("p").equals("/message/content/parts/0/text") || !patch.optString("o").equals("append") || !(patch.opt("v") instanceof String)) {
                        if (invalidatesContext(patch.optString("p"))) { discardContext(); return null; }
                        continue;
                    }
                    String addition = patch.optString("v");
                    activeText += addition.substring(0, Math.min(addition.length(), 2000 - activeText.length())); changed = true;
                }
                return changed ? new Caption(activeRole, activeText, false) : null;
            }
            if (!operation.equals("add") || !"".equals(delta.opt("p"))) {
                if (invalidatesContext(delta.optString("p"))) discardContext();
                return null;
            }
            activeRole = ""; activeText = "";
            JSONObject value = delta.optJSONObject("v"), message = value == null ? null : value.optJSONObject("message");
            if (message == null) return null;
            JSONObject author = message.optJSONObject("author"), metadata = message.optJSONObject("metadata"), content = message.optJSONObject("content");
            String role = author == null ? "" : author.optString("role");
            if ((!role.equals("user") && !role.equals("assistant")) || !"all".equals(message.opt("recipient")) || !message.has("channel") || (metadata != null && metadata.optBoolean("is_visually_hidden_from_conversation"))) return null;
            if (role.equals("user") ? !message.isNull("channel") : !"final".equals(message.opt("channel"))) return null;
            if (content == null || !content.optString("content_type").equals("multimodal_text")) return null;
            JSONArray parts = content.optJSONArray("parts"); if (parts == null || parts.length() != 1) return null;
            JSONObject part = parts.optJSONObject(0);
            if (part == null || !part.optString("content_type").equals("audio_transcription") || !(part.opt("text") instanceof String)) return null;
            String visible = part.optString("text"), text = visible.substring(0, Math.min(visible.length(), 2000));
            try {
                String id = ConversationData.identifier(message.optString("id"));
                boolean fresh = !id.equals(messageId); messageId = id;
                activeRole = role; activeText = text;
                return new Caption(role, text, fresh);
            } catch (IllegalArgumentException ignored) { return null; }
        }
    }
    static final String ORIGIN = "https://chatgpt.com";
    static final String PATH = "/realtime/wm";
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36 Edg/150.0.0.0";
    static final String CLIENT_VERSION = "prod-e8f67765f24e1c6773b4b61b4310d5ff9297439a";
    static final String CLIENT_BUILD = "8338682";
    static final String COOKIE = "__Secure-next-auth.session-token";

    static JSONObject session(String id, String timezone, int offset) throws Exception {
        return session(id, timezone, offset, null, null, VoiceOptions.defaults());
    }
    static JSONObject session(String id, String timezone, int offset, String conversationId, String parentMessageId, VoiceOptions options) throws Exception {
        if (conversationId == null && parentMessageId != null) throw new IllegalArgumentException("parent_without_conversation");
        if (conversationId != null) { ConversationData.identifier(conversationId); ConversationData.identifier(parentMessageId); }
        JSONObject value = new JSONObject().put("conversation_id", conversationId == null ? JSONObject.NULL : conversationId)
            .put("language_code", options.language).put("requested_default_model", "")
            .put("voice", options.voice).put("voice_session_id", id).put("voice_status_request_id", id)
            .put("timezone_offset_min", offset).put("timezone", timezone)
            .put("voice_mode", "wingman").put("model_slug", "").put("client_tools", new JSONArray())
            .put("history_and_training_disabled", false)
            .put("conversation_mode", new JSONObject().put("kind", "primary_assistant"))
            .put("model_slug_advanced", "").put("enable_message_streaming", true)
            .put("backend_reasoning_effort", "high").put("chat_mode", "chat");
        if (parentMessageId != null) value.put("parent_message_id", parentMessageId);
        return value;
    }
    static byte[] multipart(String boundary, String sdp, JSONObject session) {
        if (!boundary.matches("[a-zA-Z0-9-]{1,100}")) throw new IllegalArgumentException("boundary");
        String wire = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"sdp\"\r\n\r\n"
            + sdp + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"session\"\r\n\r\n"
            + session + "\r\n--" + boundary + "--\r\n";
        return wire.getBytes(StandardCharsets.UTF_8);
    }
    static String headerValue(String value) {
        if (value == null || value.isEmpty() || value.length() > 40000 || value.chars().anyMatch(c -> c < 32 || c >= 127))
            throw new IllegalArgumentException("header_invalid");
        return value;
    }
    static boolean sessionCookieName(String name) {
        return name.equals(COOKIE) || name.matches("__Secure-next-auth\\.session-token\\.\\d+");
    }
    static String cookieHeader(Map<String, String> cookies, String device) {
        if (cookies.size() > 8) throw new IllegalArgumentException("cookie_count");
        StringBuilder result = new StringBuilder();
        for (var item : new TreeMap<>(cookies).entrySet()) {
            if (!sessionCookieName(item.getKey()) || headerValue(item.getValue()).contains(";"))
                throw new IllegalArgumentException("cookie_invalid");
            result.append(item.getKey()).append('=').append(item.getValue()).append("; ");
        }
        if (!device.matches("[a-zA-Z0-9-]{1,128}")) throw new IllegalArgumentException("device_invalid");
        return result.append("oai-did=").append(device).toString();
    }
    static JSONObject event(String wire) {
        try {
            Object value = wire;
            for (int depth = 0; depth < 8; depth++) {
                if (value instanceof String) { value = new JSONTokener((String) value).nextValue(); continue; }
                if (value instanceof JSONArray array) {
                    byte[] bytes = new byte[array.length()];
                    for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) array.getInt(i);
                    value = new String(bytes, StandardCharsets.UTF_8); continue;
                }
                if (value instanceof JSONObject object) {
                    if (object.optString("type").equals("data_message")) { value = object.get("data"); continue; }
                    return object.has("type") ? object : null;
                }
                return null;
            }
        } catch (Exception ignored) { }
        return null;
    }
    static String conversationId(JSONObject event) {
        if (event == null || !event.optString("type").equals("conversation_update")) return null;
        JSONObject payload = event.optJSONObject("payload");
        if (payload == null || !(payload.opt("conversation_id") instanceof String)) return null;
        try { return ConversationData.identifier(payload.getString("conversation_id")); }
        catch (Exception ignored) { return null; }
    }
}
