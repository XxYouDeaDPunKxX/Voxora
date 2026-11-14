package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
import static local.watchvoice.DictationState.*;
public class DictationViewStateTest {
    @Test public void stoppedAtLimitExplicitlyOffersTranscriptionInsteadOfMoreRecording() {
        DictationState state=new DictationState();long g=state.begin(new Destination(null,"Nuova"));assertEquals("Fine",DictationView.primaryLabel(state.snapshot(),idle()));
        state.captured(g);assertEquals("Trascrivi",DictationView.primaryLabel(state.snapshot(),idle()));
    }
    @Test public void selectedNewChatCannotBeReplacedByAlreadyHandledReply() {
        DictationState state=new DictationState();long g=state.begin(new Destination(null,"Nuova"));state.finish(g);state.draft(g,"input");state.claimSend(g);state.sentIdentity(g,"user","chat");state.reply(g,new VerifiedReply("chat","user","assistant","output"));
        assertTrue(DictationView.shouldBindReply(state.snapshot(),g-1));assertFalse(DictationView.shouldBindReply(state.snapshot(),g));
    }
    @Test public void panelsStayIndependentAcrossSameTurnAndCollapseOnNext() {
        DictationView.Panels panels=new DictationView.Panels();panels.generation(3);panels.open(false);panels.cursor(false,4000);
        assertTrue(panels.expanded(false));assertFalse(panels.expanded(true));panels.open(true);panels.cursor(true,8000);panels.close(false);
        assertFalse(panels.expanded(false));assertTrue(panels.expanded(true));panels.generation(3);assertEquals(8000,panels.cursor(true));
        panels.generation(4);assertFalse(panels.expanded(true));assertEquals(0,panels.cursor(true));
    }
    @Test public void uncertainAndSendingNeverOfferAnotherPostOrRecording() {
        DictationState state=new DictationState();long g=state.begin(new Destination(null,"Nuova"));state.finish(g);state.draft(g,"whole draft");
        assertEquals("SEND",DictationView.primary(state.snapshot(),idle()));state.claimSend(g);assertEquals("",DictationView.primary(state.snapshot(),idle()));
        state.fail(g,"send_uncertain",true);assertEquals("VERIFY",DictationView.primary(state.snapshot(),idle()));
    }
    private static ReadAloudPlayer.Snapshot idle(){return new ReadAloudPlayer.Snapshot("idle",0,0,1,false,"");}
}
