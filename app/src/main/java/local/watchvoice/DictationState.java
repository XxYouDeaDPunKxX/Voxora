package local.watchvoice;
import java.io.*;
import java.nio.charset.StandardCharsets;
final class DictationState {
    enum Phase { READY, RECORDING, CAPTURED, TRANSCRIBING, DRAFT, SENDING, WAITING, PREPARING_AUDIO, AUDIO, ERROR, UNCERTAIN }
    record Destination(String id,String title) {
        Destination { if(id!=null)ConversationData.identifier(id);if(title==null)title=""; }
    }
    record Snapshot(long generation,Phase phase,Destination destination,String input,String output,String conversationId,String userId,String assistantId,String error) {}
    record Recording(File file,long durationMs,long pcmBytes) {}
    record SendRecord(String userId,String conversationId,String assistantId,String phase) {}
    record AudioProgress(long bytes,boolean complete,long firstByteMs,long endMs) {}
    record VerifiedReply(String conversationId,String userId,String assistantId,String text) {}
    interface Cancellation { void check() throws IOException; }
    private long generation;
    private Phase phase=Phase.READY;
    private Destination destination;
    private String input="",output="",conversationId,userId,assistantId,error="";
    private boolean sendClaimed;
    DictationState(){}
    DictationState(long generation){this.generation=generation;}
    synchronized long retryAudio(long g){
        if(g!=generation||assistantId==null)throw new IllegalStateException("audio_retry_unavailable");
        generation++;phase=Phase.PREPARING_AUDIO;error="";return generation;
    }
    synchronized long retryTranscription(long g){
        if(g!=generation||phase!=Phase.ERROR||sendClaimed)throw new IllegalStateException("transcription_retry_unavailable");
        generation++;phase=Phase.TRANSCRIBING;input="";error="";return generation;
    }
    synchronized long begin(Destination selected) {
        if(selected==null || (phase!=Phase.READY && phase!=Phase.AUDIO && phase!=Phase.ERROR)
                || (sendClaimed && assistantId==null))throw new IllegalStateException("turn_busy");
        generation++;destination=selected;phase=Phase.RECORDING;input="";output="";
        conversationId=selected.id();userId=null;assistantId=null;error="";sendClaimed=false;return generation;
    }
    synchronized boolean finish(long g) {
        if(g!=generation || (phase!=Phase.RECORDING && phase!=Phase.CAPTURED))return false;
        phase=Phase.TRANSCRIBING;return true;
    }
    synchronized boolean captured(long g) {if(g!=generation || phase!=Phase.RECORDING)return false;phase=Phase.CAPTURED;return true;}
    synchronized boolean draft(long g,String text) {
        if(g!=generation || phase!=Phase.TRANSCRIBING)return false;
        if(text==null || text.trim().isEmpty() || text.getBytes(StandardCharsets.UTF_8).length>1048576)throw new IllegalArgumentException("transcription_size_or_empty");
        input=text;phase=Phase.DRAFT;error="";return true;
    }
    synchronized boolean claimSend(long g) {if(g!=generation || phase!=Phase.DRAFT || sendClaimed)return false;sendClaimed=true;phase=Phase.SENDING;return true;}
    synchronized boolean discard(long g) {
        if(g!=generation || sendClaimed || phase==Phase.READY)return false;
        generation++;phase=Phase.READY;input="";output="";destination=null;conversationId=null;userId=null;assistantId=null;error="";return true;
    }
    synchronized boolean reply(long g,VerifiedReply value) {
        if(g!=generation || !sendClaimed || value==null)return false;
        if(userId!=null && !userId.equals(value.userId()))throw new IllegalArgumentException("reply_identity");
        if(conversationId!=null && !conversationId.equals(value.conversationId()))throw new IllegalArgumentException("reply_identity");
        if(value.text()==null || value.text().getBytes(StandardCharsets.UTF_8).length>1048576)throw new IllegalArgumentException("reply_size");
        ConversationData.identifier(value.conversationId());ConversationData.identifier(value.assistantId());
        conversationId=value.conversationId();userId=value.userId();assistantId=value.assistantId();output=value.text();phase=Phase.PREPARING_AUDIO;error="";return true;
    }
    synchronized void sentIdentity(long g,String user,String conversation) {if(g!=generation)return;ConversationData.identifier(user);userId=user;if(conversation!=null)conversationId=ConversationData.identifier(conversation);}
    synchronized void fail(long g,String code,boolean uncertain) {if(g!=generation)return;error=safeCode(code);phase=uncertain?Phase.UNCERTAIN:Phase.ERROR;}
    /** Synchronous owner-command failure may occur after begin/retry advanced the generation. */
    synchronized void commandFailed(String code) {fail(generation,code,phase==Phase.UNCERTAIN);}
    synchronized void restoreUncertain(SendRecord record) {generation++;sendClaimed=true;userId=record.userId();conversationId=record.conversationId();assistantId=record.assistantId();destination=new Destination(conversationId,"Invio da verificare");phase=Phase.UNCERTAIN;error="send_uncertain";}
    synchronized boolean preSendFailure(long g,String code) {if(g!=generation || phase!=Phase.SENDING)return false;sendClaimed=false;userId=null;phase=Phase.DRAFT;error=safeCode(code);return true;}
    synchronized boolean audioReady(long g) {if(g!=generation || assistantId==null)return false;phase=Phase.AUDIO;error="";return true;}
    synchronized Snapshot snapshot() {return new Snapshot(generation,phase,destination,input,output,conversationId,userId,assistantId,error);}
    private static String safeCode(String code){return code!=null && code.matches("[a-zA-Z0-9_]{1,80}")?code:"operation_failed";}
}
