package local.watchvoice;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Private metadata only: no authentication, audio or message archive. */
final class WatchStore {
    private static final int MAX_BYTES = 256000;
    private static final Object LOCK = new Object();
    private final AtomicFile file;
    WatchStore(Context context) { file = new AtomicFile(new File(context.getNoBackupFilesDir(), "watch-state.json")); }
    private JSONObject read() {
        try { return decode(file.openRead()); }
        catch (Exception e) { return new JSONObject(); }
    }
    private void write(JSONObject value) {
        byte[] bytes = encode(value); // Validate before AtomicFile starts replacing the old state.
        FileOutputStream output = null;
        try { output = file.startWrite(); output.write(bytes); file.finishWrite(output); }
        catch (Exception e) { if (output != null) file.failWrite(output); throw new IllegalStateException("watch_store_write"); }
    }
    VoiceOptions options() { synchronized (LOCK) { return VoiceOptions.fromJson(read().optJSONObject("options")); } }
    static String mode(JSONObject state) { return "dictation".equals(state.optString("mode")) ? "dictation" : "voice"; }
    static float readAloudSpeed(JSONObject state) { float speed=(float)state.optDouble("readAloudSpeed",1);return validSpeed(speed)?speed:1; }
    static boolean validSpeed(float speed){return speed==.75f||speed==1f||speed==1.25f||speed==1.5f;}
    String mode(){synchronized(LOCK){return mode(read());}}
    float readAloudSpeed(){synchronized(LOCK){return readAloudSpeed(read());}}
    void saveMode(String mode){if(!mode.equals("voice")&&!mode.equals("dictation"))throw new IllegalArgumentException("mode_invalid");synchronized(LOCK){try{JSONObject state=read();state.put("mode",mode);write(state);}catch(org.json.JSONException e){throw new IllegalArgumentException("mode_invalid");}}}
    void saveReadAloudSpeed(float speed){if(!validSpeed(speed))throw new IllegalArgumentException("speed_invalid");synchronized(LOCK){try{JSONObject state=read();state.put("readAloudSpeed",speed);write(state);}catch(org.json.JSONException e){throw new IllegalArgumentException("speed_invalid");}}}
    void saveOptions(VoiceOptions value) { synchronized (LOCK) { try { JSONObject state = read(); state.put("options", value.toJson()); write(state); } catch (org.json.JSONException e) { throw new IllegalArgumentException("options_invalid"); } } }
    /** Call only after the service has observed successful call acceptance. */
    void saveSuccessfulOptions(VoiceOptions value) { synchronized (LOCK) { try { JSONObject state = read(); state.put("successfulOptions", value.toJson()); write(state); } catch (org.json.JSONException e) { throw new IllegalArgumentException("options_invalid"); } } }
    VoiceOptions successfulOptions() { synchronized (LOCK) { JSONObject value = read().optJSONObject("successfulOptions"); return value == null ? null : VoiceOptions.fromJson(value); } }
    boolean hasPendingSelection() { synchronized (LOCK) { return read().has("pendingChat"); } }
    ConversationData.Chat pendingChat() { synchronized (LOCK) { return chat(read().optJSONObject("pendingChat")); } }
    void savePendingChat(ConversationData.Chat value) { synchronized (LOCK) { try { JSONObject state = read(); state.put("pendingChat", value == null ? JSONObject.NULL : json(value)); write(state); } catch (org.json.JSONException e) { throw new IllegalArgumentException("chat_invalid"); } } }
    ConversationData.Chat lastChat() { synchronized (LOCK) { return chat(read().optJSONObject("lastChat")); } }
    void saveLastChat(ConversationData.Chat value) { synchronized (LOCK) { try { JSONObject state = read(); state.put("lastChat", value == null ? JSONObject.NULL : json(value)); write(state); } catch (org.json.JSONException e) { throw new IllegalArgumentException("chat_invalid"); } } }
    // Bounded UTF-8 codec shared by every AtomicFile write and read.
    static JSONObject decode(InputStream input) throws Exception {
        return new JSONObject(new String(CredentialStore.readLimited(input, MAX_BYTES), StandardCharsets.UTF_8));
    }
    static byte[] encode(JSONObject value) {
        byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        JSONArray chats = value.optJSONArray("chats");
        // Evict oldest tail metadata only. Preferences, IDs and explicit New survive.
        while (bytes.length > MAX_BYTES && chats != null && chats.length() > 0) {
            chats.remove(chats.length() - 1);
            bytes = value.toString().getBytes(StandardCharsets.UTF_8);
        }
        if (bytes.length > MAX_BYTES) throw new IllegalStateException("watch_store_size");
        return bytes;
    }
    private static Set<String> favorites(JSONObject state) {
        Set<String> ids = new LinkedHashSet<>(); JSONArray items = state.optJSONArray("favorites");
        if (items != null) for (int i = 0; i < items.length() && ids.size() < 500; i++) { try { ids.add(ConversationData.identifier(items.getString(i))); } catch (Exception ignored) {} }
        return ids;
    }
    Set<String> favorites() { synchronized (LOCK) { return favorites(read()); } }
    static boolean toggleFavorite(JSONObject state, String id) {
        ConversationData.identifier(id); Set<String> ids = favorites(state);
        if (!ids.remove(id)) { if (ids.size() >= 500) return false; ids.add(id); }
        try { state.put("favorites", new JSONArray(ids)); return true; } catch (org.json.JSONException e) { throw new IllegalArgumentException("favorite_invalid"); }
    }
    boolean toggleFavorite(String id) { synchronized (LOCK) {
        JSONObject state = read(); boolean changed = toggleFavorite(state, id); if (changed) write(state); return changed;
    } }
    List<ConversationData.Chat> cachedChats() { synchronized (LOCK) {
        List<ConversationData.Chat> result = new ArrayList<>(); JSONArray items = read().optJSONArray("chats");
        if (items != null) for (int i = 0; i < items.length() && result.size() < 200; i++) { ConversationData.Chat item = chat(items.optJSONObject(i)); if (item != null) result.add(item); }
        return result;
    } }
    void saveChats(List<ConversationData.Chat> items) { synchronized (LOCK) { try {
        JSONArray chats = new JSONArray(); for (int i = 0; i < items.size() && i < 200; i++) chats.put(json(items.get(i)));
        JSONObject state = read(); state.put("chats", chats); write(state);
    } catch (org.json.JSONException e) { throw new IllegalArgumentException("chats_invalid"); } } }
    private static ConversationData.Chat chat(JSONObject value) { try { return value == null ? null : new ConversationData.Chat(value.getString("id"), value.getString("title"), value.getString("updatedAt")); } catch (Exception ignored) { return null; } }
    private static JSONObject json(ConversationData.Chat value) throws org.json.JSONException { return new JSONObject().put("id", value.id).put("title", value.title).put("updatedAt", value.updatedAt); }
}
