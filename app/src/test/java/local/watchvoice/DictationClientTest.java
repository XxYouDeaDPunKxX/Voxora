package local.watchvoice;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static local.watchvoice.DictationProtocolTest.*;
import org.json.*;
import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.cert.Certificate;
import java.util.*;
public class DictationClientTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void timeoutAfterClaimNeverRetriesOnNewClient()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);
        DictationClient client=client(journal,network);
        try{client.send("whole text",new DictationState.Destination(null,"Nuova"),USER,()->{});fail();}catch(DictationClient.Failure expected){assertTrue(expected.sendPossible);}
        assertEquals(1,network.sends);assertEquals("uncertain",journal.read().phase());
        try{client(journal,network).send("whole text",new DictationState.Destination(null,"Nuova"),USER,()->{});fail();}catch(DictationClient.Failure expected){}
        assertEquals(1,network.sends);assertTrue(network.claimWasWrittenBeforeSend);
    }
    @Test public void missingOldChatNeverFallsBackToNew()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);network.missing=true;
        try{client(journal,network).send("text",new DictationState.Destination(CHAT,"old"),USER,()->{});fail();}catch(DictationClient.Failure expected){assertFalse(expected.sendPossible);}
        assertEquals(0,network.sends);assertNull(journal.read());
    }
    @Test public void transcribeStreamsWavAndItalianWithoutTruncatingText()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);
        File wav=journal.file("recording.wav");try(FileOutputStream out=new FileOutputStream(wav)){out.write(DictationRecorder.wavHeader(2));out.write(new byte[]{1,2});}
        String text=client(journal,network).transcribe(new DictationState.Recording(wav,1,2),"it-IT",new DictationState.Destination(null,"Nuova"),()->{});
        assertEquals("漢".repeat(10000),text);String body=new String(network.transcriptionBody,java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"language\"\r\n\r\nit\r\n"));assertTrue(body.contains("filename=\"audio.wav\""));assertTrue(body.contains("RIFF"));
    }
    @Test public void interruptedKnownChatCanBeVerifiedWithoutAssistantIdOrRepost()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);
        journal.claim(new DictationState.SendRecord(USER,CHAT,null,"uncertain"));
        var reply=client(journal,network).verify(journal.read(),()->{});
        assertEquals(ASSISTANT,reply.assistantId());assertEquals("new answer",reply.text());
        assertEquals(0,network.sends);assertEquals("verified",journal.read().phase());
    }
    @Test public void transcribeCarriesVerifiedChatContextAndDuration()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);
        File wav=journal.file("recording.wav");try(FileOutputStream out=new FileOutputStream(wav)){out.write(DictationRecorder.wavHeader(2));out.write(new byte[]{1,2});}
        client(journal,network).transcribe(new DictationState.Recording(wav,800,2),"it-IT",new DictationState.Destination(CHAT,"old"),()->{});
        String body=new String(network.transcriptionBody,java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"duration_ms\"\r\n\r\n800\r\n"));
        assertTrue(body.contains("name=\"conversation_id\"\r\n\r\n"+CHAT+"\r\n"));
        assertTrue(body.contains("name=\"parent_message_id\"\r\n\r\n"+ASSISTANT+"\r\n"));
    }
    @Test public void successfulSendReturnsVerifiedResponseAndKeepsClaim()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);network.complete=true;
        var reply=client(journal,network).send("whole text",new DictationState.Destination(null,"Nuova"),USER,()->{});
        assertEquals("new answer",reply.text());assertEquals(USER,reply.userId());assertEquals(ASSISTANT,reply.assistantId());
        assertEquals(1,network.sends);assertEquals("verified",journal.read().phase());
    }
    @Test public void newChatUsesSavedAccountModelAndMaxEffortInsteadOfFirstCatalogEntry()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);network.complete=true;
        client(journal,network).send("text",new DictationState.Destination(null,"Nuova"),USER,()->{});
        JSONObject sent=new JSONObject(new String(network.sentBody,java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("gpt-6-thinking",sent.getString("model"));assertEquals("max",sent.getString("thinking_effort"));
        assertEquals("gpt-6-thinking",sent.getJSONArray("messages").getJSONObject(0).getJSONObject("metadata").getString("default_model_slug"));
    }
    @Test public void existingChatUsesCurrentAccountDefaultsEvenWhenItsPreviousSelectionDiffers()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);network.complete=true;
        network.chatModel="gpt-5-6-thinking";network.chatEffort="extended";
        client(journal,network).send("text",new DictationState.Destination(CHAT,"old"),USER,()->{});
        JSONObject sent=new JSONObject(new String(network.sentBody,java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("gpt-6-thinking",sent.getString("model"));assertEquals("max",sent.getString("thinking_effort"));
        assertEquals(ASSISTANT,sent.getString("parent_message_id"));assertEquals(1,network.sends);
    }
    @Test public void missingNewChatPreferenceNeverSendsWithFallbackModel()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);network.settings="{\"settings\":{}}";
        try{client(journal,network).send("text",new DictationState.Destination(null,"Nuova"),USER,()->{});fail("must stop before send");}
        catch(DictationClient.Failure expected){assertFalse(expected.sendPossible);}
        assertEquals(0,network.sends);assertNull(journal.read());
    }
    @Test public void unavailableAccountDefaultNeverFallsBackToChatOrFirstModel()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);
        JSONObject preferences=new JSONObject(network.settings);preferences.getJSONObject("settings").getJSONObject("default_model_config").put("default_model_slug","retired-thinking");network.settings=preferences.toString();
        try{client(journal,network).send("text",new DictationState.Destination(CHAT,"old"),USER,()->{});fail("must stop before send");}
        catch(DictationClient.Failure expected){assertFalse(expected.sendPossible);}
        assertEquals(0,network.sends);assertNull(journal.read());
    }
    @Test public void unavailableEffortDoesNotDowngradeOrClaimSend()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);
        JSONObject preferences=new JSONObject(network.settings);preferences.getJSONObject("settings").getJSONObject("last_used_model_config").getJSONObject("juices").getJSONObject("default").put("gpt-6-thinking","future_effort");network.settings=preferences.toString();
        try{client(journal,network).send("text",new DictationState.Destination(CHAT,"old"),USER,()->{});fail("must not substitute a lower effort");}
        catch(DictationClient.Failure expected){assertFalse(expected.sendPossible);assertEquals("model_effort_unavailable",expected.code);}
        assertEquals(0,network.sends);assertNull(journal.read());
    }
    @Test public void nonThinkingAccountDefaultOmitsEffortEvenInThinkingChat()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);network.complete=true;
        JSONObject preferences=new JSONObject(network.settings);preferences.getJSONObject("settings").getJSONObject("default_model_config").put("default_model_slug","gpt-5-6");network.settings=preferences.toString();
        client(journal,network).send("text",new DictationState.Destination(CHAT,"old"),USER,()->{});
        JSONObject sent=new JSONObject(new String(network.sentBody,java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("gpt-5-6",sent.getString("model"));assertFalse(sent.has("thinking_effort"));
    }
    @Test public void unavailableAccountDefaultsStopBeforeSendingEvenWithCompleteChatMetadata()throws Exception {
        DictationTurnStore journal=new DictationTurnStore(temporary.newFolder());FakeNetwork network=new FakeNetwork(journal);network.complete=true;network.settingsStatus=503;
        try{client(journal,network).send("text",new DictationState.Destination(CHAT,"old"),USER,()->{});fail("must read current defaults");}
        catch(DictationClient.Failure expected){assertFalse(expected.sendPossible);assertEquals("http_503",expected.code);}
        assertEquals(0,network.sends);assertEquals(1,network.settingsReads);assertNull(journal.read());
    }
    private static DictationClient client(DictationTurnStore journal,FakeNetwork network){
        return new DictationClient(deadline->new JSONObject().put("accessToken","test-bearer").put("deviceId",CHAT),journal,network::open);
    }
    private static final class FakeNetwork {
        final DictationTurnStore journal;int sends,settingsReads,settingsStatus=200;boolean missing,complete,claimWasWrittenBeforeSend;byte[] transcriptionBody,sentBody;
        String chatModel="gpt-6-thinking",chatEffort="max";
        String settings="{\"settings\":{\"last_used_model_config\":{\"slugs\":{\"default\":\"gpt-6-thinking\",\"android_app\":\"gpt-6-thinking\",\"web\":\"gpt-6-thinking\"},\"juices\":{\"default\":{\"gpt-6-thinking\":\"max\",\"gpt-5-6-thinking\":\"max\"}}},\"default_model_config\":{\"default_model_slug\":null}}}";
        FakeNetwork(DictationTurnStore journal){this.journal=journal;}
        HttpsURLConnection open(URL url)throws IOException {
            return new HttpsURLConnection(url){
                ByteArrayOutputStream outgoing=new ByteArrayOutputStream();
                public void connect(){}public void disconnect(){}public boolean usingProxy(){return false;}
                public String getCipherSuite(){return "test";}public Certificate[] getLocalCertificates(){return null;}public Certificate[] getServerCertificates(){return null;}
                public OutputStream getOutputStream()throws IOException {
                    if(url.getPath().equals("/backend-api/f/conversation")){sends++;claimWasWrittenBeforeSend=journal.read()!=null;}
                    return outgoing;
                }
                public int getResponseCode(){if(url.getPath().equals("/backend-api/settings/user")){settingsReads++;return settingsStatus;}return missing&&url.getPath().contains("/conversation/")?404:200;}
                public String getContentType(){return url.getPath().endsWith("/f/conversation")?"text/event-stream":"application/json";}
                public InputStream getInputStream()throws IOException {
                    String result="{}";
                    if(url.getPath().equals("/backend-api/f/conversation")){
                        sentBody=outgoing.toByteArray();
                        if(!complete)throw new IOException("simulated disconnect after POST");
                        result="event: delta_encoding\ndata: \"v1\"\n\ndata: {\"conversation_id\":\""+CHAT+"\",\"message\":{\"id\":\""+ASSISTANT+"\",\"author\":{\"role\":\"assistant\"}}}\n\ndata: [DONE]\n\n";
                    }
                    if(url.getPath().equals("/backend-api/models"))result="{\"default_model_slug\":\"gpt-6\",\"models\":[{\"slug\":\"gpt-5-6\",\"configurable_thinking_effort\":false,\"thinking_efforts\":[]},{\"slug\":\"gpt-6-thinking\",\"configurable_thinking_effort\":true,\"thinking_efforts\":[{\"thinking_effort\":\"standard\"},{\"thinking_effort\":\"extended\"},{\"thinking_effort\":\"max\"}]},{\"slug\":\"gpt-5-6-thinking\",\"configurable_thinking_effort\":true,\"thinking_efforts\":[{\"thinking_effort\":\"standard\"},{\"thinking_effort\":\"extended\"},{\"thinking_effort\":\"max\"}]}]}";
                    if(url.getPath().equals("/backend-api/settings/user"))result=settings;
                    if(url.getPath().endsWith("chat-requirements"))result="{\"token\":\"test\",\"expire_after\":540}";
                    if(url.getPath().contains("/conversation/"))try{JSONObject graph=graph(USER).put("default_model_slug",chatModel);graph.getJSONObject("mapping").getJSONObject(ASSISTANT).getJSONObject("message").put("metadata",new JSONObject().put("default_model_slug",chatModel).put("model_slug",chatModel).put("thinking_effort",chatEffort));result=graph.toString();}catch(Exception invalid){throw new IOException();}
                    if(url.getPath().endsWith("transcribe")){transcriptionBody=outgoing.toByteArray();try{result=new JSONObject().put("text","漢".repeat(10000)).toString();}catch(JSONException impossible){throw new IOException();}}
                    return new ByteArrayInputStream(result.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
            };
        }
    }
}
