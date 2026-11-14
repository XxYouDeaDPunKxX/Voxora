package local.watchvoice;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatRefreshStateTest {
    // Catches cache-only startup, duplicated opening events, and failure to refresh on return.
    @Test public void openingsStartRefreshWithoutDuplicatingInFlightRequest() {
        ChatRefreshState state = new ChatRefreshState();
        assertTrue(state.begin()); assertTrue(state.loading());
        assertFalse(state.begin());
        state.completed(); assertFalse(state.loading());
        assertTrue(state.begin()); // Later warm opening refreshes again.
    }
    // Catches presenting a saved list as fresh after its refresh failed.
    @Test public void failedRefreshExplainsStaleListAndRetryRecovers() {
        ChatRefreshState state = new ChatRefreshState();
        state.begin(); state.failed("rete_timeout");
        assertFalse(state.loading()); assertTrue(state.label().contains("non aggiornato"));
        assertTrue(state.label().contains("Errore rete:"));
        assertTrue(state.begin()); state.completed(); assertEquals("", state.label());
    }
    // Catches a cancelled request keeping future app openings blocked or erasing the failure.
    @Test public void cancellationAllowsNextOpeningAndRetainsStaleNotice() {
        ChatRefreshState state = new ChatRefreshState();
        state.begin(); state.failed("rete_timeout");
        state.cancel(); assertTrue(state.label().contains("non aggiornato"));
        assertTrue(state.begin()); state.cancel(); assertFalse(state.loading());
        assertTrue(state.begin());
    }
}
