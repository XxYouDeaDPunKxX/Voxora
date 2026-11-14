package local.watchvoice;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import org.json.JSONArray;
final class ConversationData {
    static final class Chat {
        final String id, title, updatedAt;
        Chat(String id, String title, String updatedAt) { this.id = identifier(id); this.title = title; this.updatedAt = updatedAt; }
    }
    static final class Page {
        final List<Chat> items; final int total, offset, limit;
        Page(List<Chat> items, int total, int offset, int limit) { this.items = Collections.unmodifiableList(items); this.total = total; this.offset = offset; this.limit = limit; }
    }
    static final class Turn {
        final String role, text;
        Turn(String role, String text) { this.role = role; this.text = text; }
    }
    static final class Detail {
        final String id, title, currentNode; final List<Turn> turns;
        Detail(String id, String title, String currentNode, List<Turn> turns) { this.id = id; this.title = title; this.currentNode = currentNode; this.turns = Collections.unmodifiableList(turns); }
    }
    static String identifier(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,128}")) throw new IllegalArgumentException("conversation_id_invalid");
        return id;
    }
    static Page parsePage(JSONObject value) {
        try {
            JSONArray items = value.getJSONArray("items"); int total = value.getInt("total"), offset = value.getInt("offset"), limit = value.getInt("limit");
            if (total < 0 || offset < 0 || limit < 1 || limit > 20 || items.length() > limit) throw new IllegalArgumentException();
            List<Chat> chats = new ArrayList<>();
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                chats.add(new Chat(item.getString("id"), bounded(item.optString("title", ""), 500), bounded(item.optString("update_time", ""), 100)));
            }
            return new Page(chats, total, offset, limit);
        } catch (Exception e) { throw new IllegalArgumentException("conversation_list_invalid"); }
    }
    static Detail parseDetail(JSONObject value, String expectedId) {
        try {
            identifier(expectedId); String id = identifier(value.getString("conversation_id"));
            if (!id.equals(expectedId)) throw new IllegalArgumentException();
            String node = identifier(value.getString("current_node")); JSONArray messages = value.getJSONArray("messages");
            if (messages.length() > 500) throw new IllegalArgumentException();
            List<Turn> turns = new ArrayList<>();
            for (int i = 0; i < messages.length(); i++) {
                JSONObject message = messages.getJSONObject(i), author = message.optJSONObject("author"), metadata = message.optJSONObject("metadata");
                String role = author == null ? "" : author.optString("role");
                if ((!role.equals("user") && !role.equals("assistant")) || (metadata != null && metadata.optBoolean("is_visually_hidden_from_conversation"))) continue;
                if (!"all".equals(message.opt("recipient")) || !message.has("channel")) continue;
                if (role.equals("user") ? !message.isNull("channel") : !"final".equals(message.opt("channel"))) continue;
                JSONObject content = message.optJSONObject("content"); if (content == null) continue;
                String type = content.optString("content_type");
                if (!type.equals("text") && !type.equals("multimodal_text")) continue;
                JSONArray parts = content.optJSONArray("parts"); if (parts == null) continue;
                StringBuilder text = new StringBuilder();
                for (int p = 0; p < parts.length() && text.length() < 2000; p++) {
                    Object part = parts.get(p); String visible = null;
                    if (part instanceof String) visible = (String) part;
                    else if (part instanceof JSONObject object && type.equals("multimodal_text") && object.optString("content_type").equals("audio_transcription")) visible = object.optString("text", "");
                    if (visible != null && !visible.isEmpty()) { if (text.length() > 0) text.append('\n'); text.append(visible, 0, Math.min(visible.length(), 2000 - text.length())); }
                }
                if (text.length() > 0) turns.add(new Turn(role, text.toString()));
            }
            if (turns.size() > 12) turns = new ArrayList<>(turns.subList(turns.size() - 12, turns.size()));
            return new Detail(id, bounded(value.optString("title", ""), 500), node, turns);
        } catch (Exception e) { throw new IllegalArgumentException("conversation_detail_invalid"); }
    }
    private static String bounded(String text, int limit) { return text.length() > limit ? text.substring(0, limit) : text; }
}
