package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
import static local.watchvoice.DictationState.*;
public class DictationStateTest {
    @Test public void synchronousRecordingSetupErrorBelongsToTheNewGeneration() {
        DictationState state=new DictationState();long before=state.snapshot().generation();state.begin(new Destination(null,"Nuova"));
        state.commandFailed("storage_low");assertTrue(state.snapshot().generation()>before);assertEquals(Phase.ERROR,state.snapshot().phase());assertEquals("storage_low",state.snapshot().error());
        state.restoreUncertain(new SendRecord("user",null,null,"uncertain"));state.commandFailed("private_storage");assertEquals(Phase.UNCERTAIN,state.snapshot().phase());
    }
    @Test public void retryAudioInvalidatesLateDownloadButKeepsExactReply() {
        DictationState state=new DictationState();long g=state.begin(new Destination(null,"Nuova"));state.finish(g);state.draft(g,"input");state.claimSend(g);
        state.sentIdentity(g,"user","chat");state.reply(g,new VerifiedReply("chat","user","assistant","complete output"));state.fail(g,"audio_download_failed",false);
        long next=state.retryAudio(g);assertTrue(next>g);assertFalse(state.audioReady(g));assertEquals("assistant",state.snapshot().assistantId());assertEquals("complete output",state.snapshot().output());
    }
    @Test public void discardRejectsLateDraft() {
        DictationState state=new DictationState();
        long old=state.begin(new Destination(null,"Nuova"));
        assertTrue(state.finish(old)); assertFalse(state.finish(old));
        assertTrue(state.discard(old));
        state.begin(new Destination(null,"Nuova"));
        assertFalse(state.draft(old,"old attempt"));
        assertEquals("",state.snapshot().input());
    }
    @Test public void draftRequiresExplicitSingleSend() {
        DictationState state=new DictationState();long g=state.begin(new Destination(null,"Nuova"));
        assertFalse(state.claimSend(g));state.finish(g);
        assertTrue(state.draft(g,"漢😀".repeat(25000)));
        assertEquals(75000,state.snapshot().input().length());
        assertEquals(Phase.DRAFT,state.snapshot().phase());
        assertTrue(state.claimSend(g));assertFalse(state.claimSend(g));
        assertFalse(state.discard(g));
        assertEquals(Phase.SENDING,state.snapshot().phase());
    }
    @Test public void uncertainSendCannotStartAnotherTurnOrDropIdentity() {
        DictationState state=new DictationState();long g=state.begin(new Destination("chat_1","old"));
        state.finish(g);state.draft(g,"hello");state.claimSend(g);
        state.sentIdentity(g,"user_1","chat_1");state.fail(g,"timeout",true);
        try {state.begin(new Destination(null,"Nuova"));fail();}catch(IllegalStateException expected){}
        assertEquals("user_1",state.snapshot().userId());assertEquals(Phase.UNCERTAIN,state.snapshot().phase());
    }
    @Test public void staleReplyDoesNotReplaceCurrentDestination() {
        DictationState state=new DictationState();long old=state.begin(new Destination(null,"Nuova"));
        state.discard(old);long now=state.begin(new Destination("chat_2","selected"));
        assertFalse(state.reply(old,new VerifiedReply("chat_1","user_1","assistant_1","wrong")));
        assertEquals(now,state.snapshot().generation());assertEquals("chat_2",state.snapshot().destination().id());
    }
    @Test public void retryTranscriptionKeepsDestinationAndInvalidatesFailedGeneration() {
        DictationState state=new DictationState();long g=state.begin(new Destination("chat_2","chosen"));state.finish(g);state.fail(g,"transcription_failed",false);
        long retried=state.retryTranscription(g);assertTrue(retried>g);
        assertEquals("chat_2",state.snapshot().destination().id());assertEquals(Phase.TRANSCRIBING,state.snapshot().phase());
        assertFalse(state.draft(g,"late text"));assertTrue(state.draft(retried,"new text"));
    }
    @Test public void recreatedReadyStateKeepsEpochInsteadOfReusingOldCallbackGeneration() {
        DictationState state=new DictationState();long old=state.begin(new Destination(null,"Nuova"));state.discard(old);
        DictationState recreated=new DictationState(state.snapshot().generation());recreated.begin(new Destination(null,"Nuova"));
        assertFalse(recreated.finish(old));assertTrue(recreated.snapshot().generation()>old);
    }
}
