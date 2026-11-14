package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
import static local.watchvoice.DictationState.*;
public class DictationServiceStateTest {
    @Test public void oldGenerationCannotSendOrStopNewTurn() {
        DictationState state=new DictationState();long old=state.begin(new Destination(null,"Nuova"));state.discard(old);state.begin(new Destination(null,"Nuova"));
        assertFalse(DictationService.accepts("FINISH",old,state.snapshot()));
        assertFalse(DictationService.accepts("CANCEL",old,state.snapshot()));
    }
    @Test public void finalSendIsOnlyAvailableOnDraft() {
        DictationState state=new DictationState();long g=state.begin(new Destination(null,"Nuova"));
        assertFalse(DictationService.accepts("SEND",g,state.snapshot()));state.finish(g);state.draft(g,"message");
        assertTrue(DictationService.accepts("SEND",g,state.snapshot()));state.claimSend(g);
        assertFalse(DictationService.accepts("SEND",g,state.snapshot()));assertFalse(DictationService.accepts("RETAKE",g,state.snapshot()));
    }
}
