package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.TimeZone;
public class DictationProtocolTest {
    static final String CHAT="11111111-1111-1111-1111-111111111111",USER="22222222-2222-2222-2222-222222222222",ASSISTANT="33333333-3333-3333-3333-333333333333",OTHER="44444444-4444-4444-4444-444444444444";
    @Test public void requirementsFailClosedOnUnknownAndRequiredControls()throws Exception {
        assertEquals(DictationProtocol.Decision.UNKNOWN,DictationProtocol.requirements(new JSONObject("{\"new_required_control\":true}")));
        assertEquals(DictationProtocol.Decision.READY,DictationProtocol.requirements(new JSONObject("{\"token\":\"test\",\"expire_after\":540}")));
        assertEquals(DictationProtocol.Decision.BLOCKED,DictationProtocol.requirements(new JSONObject("{\"token\":\"test\",\"expire_after\":540,\"turnstile\":{\"required\":true}}")));
    }
    @Test public void completeStreamAcceptsEncodingV1AndExactIds()throws Exception {
        String input="event: delta_encoding\ndata: \"v1\"\n\ndata: {\"conversation_id\":\""+CHAT+"\",\"message\":{\"id\":\""+ASSISTANT+"\",\"author\":{\"role\":\"assistant\"}}}\n\ndata: [DONE]\n\n";
        var stream=DictationProtocol.consume(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),()->{});
        assertEquals(CHAT,stream.conversationId());assertEquals(ASSISTANT,stream.assistantId());
    }
    @Test public void staleAbsoluteAndElapsedRelativeRequirementsCannotAuthorizePost()throws Exception {
        assertFalse(DictationProtocol.fresh(new JSONObject("{\"token\":\"t\",\"expire_at\":1699999999}"),0,0,1700000000000L));
        assertFalse(DictationProtocol.fresh(new JSONObject("{\"token\":\"t\",\"expire_after\":1}"),0,2000000000L,1700000000000L));
        assertTrue(DictationProtocol.fresh(new JSONObject("{\"token\":\"t\",\"expire_after\":540}"),0,1000000000L,1700000000000L));
    }
    @Test public void incompleteStreamCannotBecomeReply()throws Exception {
        try {DictationProtocol.consume(new ByteArrayInputStream("data: {}\n\n".getBytes(StandardCharsets.UTF_8)),()->{});fail();}catch(IOException expected){}
    }
    @Test public void replyMustDescendFromTheSentUser()throws Exception {
        JSONObject graph=graph(USER);assertEquals("new answer",DictationProtocol.verifyReply(graph,USER,ASSISTANT).text());
        try {DictationProtocol.verifyReply(graph(OTHER),USER,ASSISTANT);fail();}catch(IOException expected){}
        graph.getJSONObject("mapping").getJSONObject(ASSISTANT).remove("parent");
        try {DictationProtocol.verifyReply(graph,USER,ASSISTANT);fail();}catch(IOException expected){}
    }
    @Test public void existingChatUsesExplicitCurrentParentAndWholeText()throws Exception {
        String text="漢😀".repeat(25000);
        JSONObject body=DictationProtocol.body(USER,OTHER,CHAT,"auto",text,TimeZone.getTimeZone("Europe/Rome"),0);
        assertEquals(CHAT,body.getString("conversation_id"));assertEquals(OTHER,body.getString("parent_message_id"));
        assertEquals(text,body.getJSONArray("messages").getJSONObject(0).getJSONObject("content").getJSONArray("parts").getString(0));
    }
    @Test public void sendOmitsTheThreeOptionalToolFlags()throws Exception {
        JSONObject body=DictationProtocol.body(USER,OTHER,CHAT,"gpt-6-thinking","text",TimeZone.getTimeZone("Europe/Rome"),0);
        for(String key:new String[]{"force_use_search","force_use_tools","force_use_canvas"})assertFalse(key,body.has(key));
        assertTrue(body.getBoolean("force_use_sse"));assertFalse(body.getBoolean("force_paragen"));
    }
    static JSONObject graph(String parent)throws Exception {
        JSONObject nodes=new JSONObject();
        nodes.put(USER,new JSONObject().put("parent",JSONObject.NULL).put("message",new JSONObject().put("id",USER).put("author",new JSONObject().put("role","user"))));
        nodes.put(ASSISTANT,new JSONObject().put("parent",parent).put("message",new JSONObject().put("id",ASSISTANT).put("author",new JSONObject().put("role","assistant")).put("channel","final").put("recipient","all").put("status","finished_successfully").put("content",new JSONObject().put("content_type","text").put("parts",new JSONArray().put("new answer")))));
        return new JSONObject().put("conversation_id",CHAT).put("current_node",ASSISTANT).put("mapping",nodes);
    }
}
