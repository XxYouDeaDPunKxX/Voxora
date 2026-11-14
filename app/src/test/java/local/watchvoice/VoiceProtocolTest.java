package local.watchvoice;

import org.junit.Test;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.Assert.*;

public class VoiceProtocolTest {
    // Sanitized fixture mirrors the native observed add event, without live values.
    private JSONObject message(String id, String role, Object channel, String recipient, boolean hidden, String text) throws Exception {
        JSONObject message = new JSONObject().put("id", id).put("author", new JSONObject().put("role", role))
            .put("channel", channel).put("recipient", recipient).put("metadata", new JSONObject().put("is_visually_hidden_from_conversation", hidden))
            .put("content", new JSONObject().put("content_type", "multimodal_text").put("parts", new org.json.JSONArray().put(new JSONObject().put("content_type", "audio_transcription").put("text", text))));
        return new JSONObject().put("type", "chat_message_delta").put("payload", new JSONObject().put("delta", new JSONObject().put("c", 0).put("p", "").put("o", "add").put("v", new JSONObject().put("message", message))));
    }
    @Test public void observedVisibleCaptionMessagesHaveStableBoundaries() throws Exception {
        VoiceProtocol.LiveCaptions parser = new VoiceProtocol.LiveCaptions();
        VoiceProtocol.Caption first = parser.accept(message("one", "user", JSONObject.NULL, "all", false, "cia"));
        assertNotNull(first); assertTrue(first.fresh); assertEquals("user", first.kind);
        VoiceProtocol.Caption partial = parser.accept(message("one", "user", JSONObject.NULL, "all", false, "ciao"));
        assertFalse(partial.fresh); assertEquals("ciao", partial.text);
        VoiceProtocol.Caption second = parser.accept(message("two", "user", JSONObject.NULL, "all", false, "x".repeat(3000)));
        assertTrue(second.fresh); assertEquals(2000, second.text.length());
    }
    @Test public void captionVisibilityRequiresPositiveObservedFields() throws Exception {
        VoiceProtocol.LiveCaptions parser = new VoiceProtocol.LiveCaptions();
        assertNull(parser.accept(message("a", "assistant", "analysis", "all", false, "private")));
        assertNull(parser.accept(message("b", "assistant", "final", "tool", false, "private")));
        assertNull(parser.accept(message("c", "assistant", "final", "all", true, "private")));
        assertNull(parser.accept(message("d", "tool", JSONObject.NULL, "all", false, "private")));
        VoiceProtocol.Caption visible = parser.accept(message("e", "assistant", "final", "all", false, "visible"));
        assertNotNull(visible); assertEquals("assistant", visible.kind);
    }
    private JSONObject patch(boolean includeOp, String path, String text) throws Exception {
        JSONObject delta = new JSONObject().put("v", new org.json.JSONArray().put(new JSONObject().put("p", path).put("o", "append").put("v", text)));
        if (includeOp) delta.put("o", "patch");
        return new JSONObject().put("type", "chat_message_delta").put("payload", new JSONObject().put("delta", delta));
    }
    private JSONObject mutation(String path, String operation, Object value, boolean nestedPatch) throws Exception {
        JSONObject change = new JSONObject().put("p", path).put("o", operation).put("v", value);
        JSONObject delta = nestedPatch ? new JSONObject().put("o", "patch").put("v", new org.json.JSONArray().put(change)) : change;
        return new JSONObject().put("type", "chat_message_delta").put("payload", new JSONObject().put("delta", delta));
    }
    @Test public void visibilityIdentityAndContentMutationsDiscardCaptionContext() throws Exception {
        String[] paths = {"", "/", "/message", "/message/metadata", "/message/metadata/is_visually_hidden_from_conversation",
            "/message/author", "/message/author/role", "/message/channel", "/message/recipient", "/message/id",
            "/message/content", "/message/content/content_type", "/message/content/parts", "/message/content/parts/0",
            "/message/content/parts/0/content_type", "/message/content/parts/0/text", "/message/content/parts/1"};
        for (String path : paths) {
            VoiceProtocol.LiveCaptions parser = new VoiceProtocol.LiveCaptions();
            assertNotNull(parser.accept(message("a", "assistant", "final", "all", false, "visible")));
            assertNull(path, parser.accept(mutation(path, "replace", new JSONObject().put("is_visually_hidden_from_conversation", true), true)));
            assertNull("append after mutation " + path, parser.accept(patch(false, "/message/content/parts/0/text", "must not be shown")));
            assertNotNull("fresh visible add restores context", parser.accept(message("b", "user", JSONObject.NULL, "all", false, "visible again")));
        }
    }
    @Test public void rootNonAddOperationsDiscardCaptionContext() throws Exception {
        for (String operation : new String[]{"replace", "remove", "append"}) {
            VoiceProtocol.LiveCaptions parser = new VoiceProtocol.LiveCaptions();
            assertNotNull(parser.accept(message("a", "assistant", "final", "all", false, "visible")));
            assertNull(parser.accept(mutation("", operation, new JSONObject(), false)));
            assertNull(operation, parser.accept(patch(true, "/message/content/parts/0/text", "must not be shown")));
        }
    }
    @Test public void unsupportedInitialPartNeverActivatesCaptionContext() throws Exception {
        for (Object part : new Object[]{new JSONObject().put("content_type", "text").put("text", "unsupported"),
            new JSONObject().put("content_type", "audio_transcription").put("text", 7), "unsupported"}) {
            VoiceProtocol.LiveCaptions parser = new VoiceProtocol.LiveCaptions();
            JSONObject event = message("a", "assistant", "final", "all", false, "visible");
            event.getJSONObject("payload").getJSONObject("delta").getJSONObject("v").getJSONObject("message")
                .getJSONObject("content").put("parts", new org.json.JSONArray().put(part));
            assertNull(parser.accept(event));
            assertNull(parser.accept(patch(true, "/message/content/parts/0/text", "must not be shown")));
        }
    }
    // Mirrors the native patch, then the observed subsequent v-only compressed patch.
    @Test public void observedCompressedPatchExtendsSameCaption() throws Exception {
        VoiceProtocol.LiveCaptions parser = new VoiceProtocol.LiveCaptions();
        parser.accept(message("a", "assistant", "final", "all", false, "one"));
        VoiceProtocol.Caption partial = parser.accept(patch(true, "/message/content/parts/0/text", " two"));
        assertNotNull(partial); assertFalse(partial.fresh); assertEquals("one two", partial.text);
        assertEquals("one two three", parser.accept(patch(false, "/message/content/parts/0/text", " three")).text);
        assertNull(parser.accept(patch(true, "/message/metadata/private", "secret")));
        assertEquals(2000, parser.accept(patch(true, "/message/content/parts/0/text", "x".repeat(3000))).text.length());
        parser.accept(message("hidden", "assistant", "analysis", "all", false, "private"));
        assertNull(parser.accept(patch(true, "/message/content/parts/0/text", "secret")));
    }
    @Test public void errorDiagnosticRetainsOnlyAllowedKeyTypes() throws Exception {
        JSONObject event = new JSONObject().put("type", "error").put("payload", new JSONObject().put("error",
            new JSONObject().put("code", "arbitrary_code_value").put("reason", "private_reason_value")
                .put("message", "private_message_value").put("details", new JSONObject().put("type", "arbitrary_type_value").put("dynamic_private_id", "private_value"))));
        java.lang.reflect.Method schema = VoiceEngine.class.getDeclaredMethod("schema", JSONObject.class, int.class);
        schema.setAccessible(true);
        String diagnostic = (String) schema.invoke(null, event, 0);
        assertTrue(diagnostic.contains("error:{")); assertTrue(diagnostic.contains("code:string"));
        assertTrue(diagnostic.contains("reason:string")); assertTrue(diagnostic.contains("message:string"));
        assertTrue(diagnostic.length() <= 3072);
        assertFalse(diagnostic.contains("private")); assertFalse(diagnostic.contains("arbitrary"));
    }
    // Catches taking request/response IDs or unrelated events as a confirmed conversation.
    @Test public void confirmsOnlyObservedConversationUpdatePayload() throws Exception {
        JSONObject event = VoiceProtocol.event("{\"type\":\"conversation_update\",\"payload\":{\"type\":\"conversation_update\",\"conversation_id\":\"chat-confirmed\",\"parent_message_id\":\"node-current\",\"response\":{}}}");
        assertEquals("chat-confirmed", VoiceProtocol.conversationId(event));
        assertNull(VoiceProtocol.conversationId(new JSONObject("{\"type\":\"conversation_update\",\"conversation_id\":\"wrong-level\"}")));
        assertNull(VoiceProtocol.conversationId(new JSONObject("{\"type\":\"state_update\",\"payload\":{\"conversation_id\":\"wrong-event\"}}")));
        assertNull(VoiceProtocol.conversationId(new JSONObject("{\"type\":\"conversation_update\",\"payload\":{\"conversation_id\":null}}")));
        assertNull(VoiceProtocol.conversationId(new JSONObject("{\"type\":\"conversation_update\",\"payload\":{\"conversation_id\":\"bad/id\"}}")));
    }
    // Catches silently opening a new conversation or resuming an obsolete node.
    @Test public void resumeCarriesExactConversationAndParent() throws Exception {
        JSONObject wire = VoiceProtocol.session("request", "Europe/Rome", -120, "chat-1", "parent-2", VoiceOptions.defaults());
        assertEquals("chat-1", wire.getString("conversation_id"));
        assertEquals("parent-2", wire.getString("parent_message_id"));
    }
    @Test public void newConversationHasNullIdAndNoParent() throws Exception {
        JSONObject newWire = VoiceProtocol.session("request", "Europe/Rome", -120, null, null, VoiceOptions.defaults());
        assertTrue(newWire.isNull("conversation_id")); assertFalse(newWire.has("parent_message_id"));
    }
    // Catches swapped SDP/session fields or a missing required wm field.
    @Test public void multipartCarriesSdpAndWingmanSession() throws Exception {
        JSONObject session = VoiceProtocol.session("test-id", "Europe/Rome", -120);
        String wire = new String(VoiceProtocol.multipart("boundary", "v=0\r\n", session), StandardCharsets.UTF_8);
        assertTrue(wire.contains("name=\"sdp\"\r\n\r\nv=0\r\n"));
        assertTrue(wire.contains("name=\"session\"\r\n\r\n{"));
        assertTrue(wire.endsWith("--boundary--\r\n"));
        assertEquals("wingman", session.getString("voice_mode"));
        assertEquals("it-IT", session.getString("language_code"));
        assertFalse(session.getBoolean("history_and_training_disabled"));
        assertEquals("", session.getString("model_slug"));
        assertEquals("test-id", session.getString("voice_status_request_id"));
    }
    // Catches injection of unrelated browser cookies into the credential request.
    @Test public void onlySessionCookiesCanBeProvisioned() {
        assertThrows(IllegalArgumentException.class, () -> VoiceProtocol.cookieHeader(Map.of("other", "secret"), "device"));
        assertThrows(IllegalArgumentException.class, () -> VoiceProtocol.cookieHeader(Map.of("__Secure-next-auth.session-token", "x\r\nInjected: y"), "device"));
        assertThrows(IllegalArgumentException.class, () -> VoiceProtocol.cookieHeader(Map.of("__Secure-next-auth.session-token", "x"), "a;other=x"));
    }
    @Test public void chunkedSessionRetainsBothChunks() {
        String result = VoiceProtocol.cookieHeader(Map.of("__Secure-next-auth.session-token.0", "aaa", "__Secure-next-auth.session-token.1", "bbb"), "device");
        assertTrue(result.contains("__Secure-next-auth.session-token.0=aaa"));
        assertTrue(result.contains("__Secure-next-auth.session-token.1=bbb"));
        assertTrue(result.endsWith("oai-did=device"));
    }
    // Catches ignored state/close events inside ChatGPT's data_message envelope.
    @Test public void unwrapsNestedDataChannelEvents() throws Exception {
        JSONObject parsed = VoiceProtocol.event("{\"type\":\"data_message\",\"data\":\"{\\\"type\\\":\\\"state_update\\\",\\\"payload\\\":{\\\"new_state\\\":\\\"speaking\\\"}}\"}");
        assertEquals("state_update", parsed.getString("type"));
        assertEquals("speaking", parsed.getJSONObject("payload").getString("new_state"));
        assertEquals("goodbye", VoiceProtocol.event("{\"type\":\"goodbye\"}").getString("type"));
        assertNull(VoiceProtocol.event("not json"));
    }
}
