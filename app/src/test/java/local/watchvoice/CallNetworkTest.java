package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;

public class CallNetworkTest {
    @Test public void waitsForInternetAndStartsOnlyOnceDespiteRepeatedCallbacks() {
        CallNetwork network = new CallNetwork();
        assertEquals(CallNetwork.Action.WAIT, network.available(1, false));
        assertEquals(CallNetwork.Action.START, network.available(1, true));
        assertEquals(CallNetwork.Action.KEEP, network.available(1, true));
        assertEquals(CallNetwork.Action.KEEP, network.available(1, false));
    }
    @Test public void changedNetworkEndsCallAndCannotStartAnotherSession() {
        CallNetwork network = new CallNetwork();
        network.available(1, true);
        assertEquals(CallNetwork.Action.STOP, network.available(2, true));
        assertEquals(CallNetwork.Action.WAIT, network.available(2, true));
    }
    @Test public void unrelatedLossDoesNotEndCallButSelectedLossDoes() {
        CallNetwork network = new CallNetwork();
        network.available(2, true);
        assertFalse(network.lost(1));
        assertTrue(network.lost(2));
        assertEquals(CallNetwork.Action.WAIT, network.available(2, true));
    }
}
