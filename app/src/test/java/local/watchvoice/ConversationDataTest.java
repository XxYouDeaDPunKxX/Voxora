package local.watchvoice;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class ConversationDataTest {
    // Catches preview exposure of nonfinal, tool-directed, or unrecognized messages.
    @Test public void detailAllowsOnlyObservedVisibleChannelsAndRecipients() throws Exception {
        org.json.JSONArray messages = new org.json.JSONArray()
            .put(message("user", JSONObject.NULL, "all", "visible question"))
            .put(message("assistant", "final", "all", "visible answer"))
            .put(message("assistant", "analysis", "all", "internal analysis"))
            .put(message("assistant", "commentary", "all", "unknown channel"))
            .put(message("assistant", JSONObject.NULL, "all", "unknown assistant channel"))
            .put(message("user", "final", "all", "unknown user channel"))
            .put(message("assistant", "final", "browser", "tool-directed"))
            .put(message("user", JSONObject.NULL, "browser", "other recipient"));
        JSONObject missingChannel = message("user", JSONObject.NULL, "all", "missing channel"); missingChannel.remove("channel"); messages.put(missingChannel);
        JSONObject missingRecipient = message("assistant", "final", "all", "missing recipient"); missingRecipient.remove("recipient"); messages.put(missingRecipient);
        ConversationData.Detail detail = ConversationData.parseDetail(new JSONObject().put("conversation_id", "chat-1").put("current_node", "node-9").put("messages", messages), "chat-1");
        assertEquals(2, detail.turns.size());
        assertEquals("visible question", detail.turns.get(0).text); assertEquals("visible answer", detail.turns.get(1).text);
    }
    private static JSONObject message(String role, Object channel, String recipient, String text) throws Exception {
        return new JSONObject().put("id", "synthetic-message").put("author", new JSONObject().put("role", role).put("name", JSONObject.NULL).put("metadata", new JSONObject()))
            .put("create_time", 1).put("update_time", JSONObject.NULL).put("content", new JSONObject().put("content_type", "text").put("parts", new org.json.JSONArray().put(text)))
            .put("status", "finished_successfully").put("end_turn", true).put("weight", 1).put("metadata", new JSONObject()).put("recipient", recipient).put("channel", channel);
    }
    // Catches rendering hidden system/tool/audio payloads and losing observed voice transcriptions.
    @Test public void detailExcludesHiddenAndNonTextMessages() throws Exception {
        JSONObject value = new JSONObject("{\"conversation_id\":\"chat-1\",\"title\":\"Prova\",\"current_node\":\"node-9\",\"messages\":["
            + "{\"author\":{\"role\":\"system\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"system\"]}},"
            + "{\"author\":{\"role\":\"assistant\"},\"metadata\":{\"is_visually_hidden_from_conversation\":true},\"content\":{\"content_type\":\"text\",\"parts\":[\"hidden\"]}},"
            + "{\"author\":{\"role\":\"user\"},\"channel\":null,\"recipient\":\"all\",\"content\":{\"content_type\":\"text\",\"parts\":[\"Visible question\"]}},"
            + "{\"author\":{\"role\":\"assistant\"},\"channel\":\"final\",\"recipient\":\"all\",\"content\":{\"content_type\":\"multimodal_text\",\"parts\":[{\"content_type\":\"audio_transcription\",\"text\":\"Visible answer\",\"direction\":\"out\",\"decoding_id\":null},{\"content_type\":\"audio\",\"text\":\"not text\",\"audio\":\"synthetic\"}]}},"
            + "{\"author\":{\"role\":\"tool\"},\"content\":{\"content_type\":\"text\",\"parts\":[\"tool\"]}},"
            + "{\"author\":{\"role\":\"assistant\"},\"content\":{\"content_type\":\"audio\",\"parts\":[\"payload\"]}}]}");
        ConversationData.Detail detail = ConversationData.parseDetail(value, "chat-1");
        assertNotNull(detail.turns); assertEquals(2, detail.turns.size());
        assertEquals("node-9", detail.currentNode); assertEquals("chat-1", detail.id);
        assertEquals("user", detail.turns.get(0).role); assertEquals("Visible question", detail.turns.get(0).text);
        assertEquals("assistant", detail.turns.get(1).role); assertEquals("Visible answer", detail.turns.get(1).text);
    }
    // Catches treating the reduced list's null current_node as an unusable chat.
    @Test public void listAcceptsNullCurrentNode() throws Exception {
        ConversationData.Page page = ConversationData.parsePage(new JSONObject("{\"items\":[{\"id\":\"chat-1\",\"title\":\"Prova\",\"update_time\":\"2026-10-07T00:00:00Z\",\"current_node\":null}],\"total\":1,\"offset\":0,\"limit\":20}"));
        assertNotNull(page.items); assertEquals(1, page.items.size());
        assertEquals("chat-1", page.items.get(0).id); assertEquals("Prova", page.items.get(0).title);
        assertEquals(20, page.limit); assertEquals(1, page.total);
    }
    // Catches resuming a different chat or fabricating a parent from absent state.
    @Test public void detailRejectsWrongIdOrMissingCurrentNode() throws Exception {
        JSONObject wrongChat = new JSONObject("{\"conversation_id\":\"other\",\"current_node\":\"node-1\",\"messages\":[]}");
        assertThrows(IllegalArgumentException.class, () -> ConversationData.parseDetail(wrongChat, "chat-1"));
        JSONObject missing = new JSONObject("{\"conversation_id\":\"chat-1\",\"current_node\":null,\"messages\":[]}");
        assertThrows(IllegalArgumentException.class, () -> ConversationData.parseDetail(missing, "chat-1"));
    }
}
