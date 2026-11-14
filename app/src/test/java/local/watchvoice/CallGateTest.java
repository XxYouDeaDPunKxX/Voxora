package local.watchvoice;

import org.junit.Test;
import static org.junit.Assert.*;

public class CallGateTest {
    @Test public void lateEventsAfterStopAreIgnored() {
        CallGate gate = new CallGate(); long generation = gate.start(); gate.stop();
        for (int i = 0; i < 5; i++) assertFalse(gate.accepts(generation));
        long restart = gate.start(); assertFalse(gate.accepts(generation)); assertTrue(gate.accepts(restart));
    }
    // Catches a conversation-ID event from a stopped call replacing the new call's identity.
    @Test public void stoppedConversationEventCannotOverwriteRestartedIdentity() {
        CallGate gate = new CallGate(); long oldGeneration = gate.start();
        String confirmed = "old-chat";
        gate.stop(); assertFalse(gate.accepts(oldGeneration));
        long newGeneration = gate.start(); confirmed = "new-chat";
        if (gate.accepts(oldGeneration)) confirmed = "late-old-chat";
        assertEquals("new-chat", confirmed); assertFalse(gate.accepts(oldGeneration)); assertTrue(gate.accepts(newGeneration));
    }
    // Catches late HTTP/SDP callbacks reopening the mic after Stop.
    @Test public void stopInvalidatesPendingCallbacksAndRestartGetsNewGeneration() {
        CallGate gate = new CallGate();
        long first = gate.start();
        assertTrue(gate.accepts(first));
        gate.stop();
        assertFalse(gate.accepts(first));
        long second = gate.start();
        assertNotEquals(first, second);
        assertFalse(gate.accepts(first));
        assertTrue(gate.accepts(second));
    }
    @Test public void secondStartDoesNotCreateSecondCall() {
        CallGate gate = new CallGate();
        long first = gate.start();
        assertEquals(-1, gate.start());
        assertTrue(gate.accepts(first));
    }
}
