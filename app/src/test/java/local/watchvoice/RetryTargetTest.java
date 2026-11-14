package local.watchvoice;

import org.junit.Test;
import static org.junit.Assert.*;

public class RetryTargetTest {
    // A failed A must not be used as the retry command after a row/intent chooses B.
    @Test public void choosingAnotherChatRemovesOldRetryCommand() {
        RetryTarget retry = new RetryTarget();
        retry.failed("a", "a", "Chat A", true, retry.generation());
        assertEquals("a", retry.resolve("a").id);
        retry.choose("b");
        assertNull(retry.resolve("b"));
        assertNull(retry.resolve("a")); // An old rendered listener must also fail closed.
    }
    // Nuova is explicit even if the pending selection was null throughout the call.
    @Test public void wearNewClearsRetryOfExistingAndNewlyConfirmedCalls() {
        RetryTarget retry = new RetryTarget();
        retry.failed("a", "a", "Chat A", true, retry.generation());
        assertEquals("new", WearEntry.action("new", false, true));
        retry.choose(null);
        assertNull(retry.resolve(null));
        retry.failed(null, "created", "Nuova conversazione", true, retry.generation());
        assertEquals("created", retry.resolve(null).id);
        long failedGeneration = retry.generation();
        retry.choose(null);
        assertNull(retry.resolve(null));
        // A call ending after explicit Termina e cambia must not revive its retry.
        retry.failed(null, "created", "Nuova conversazione", true, failedGeneration);
        assertNull(retry.resolve(null));
    }
    // No new choice: retry must retain the confirmed ID, but never a saved parent node.
    @Test public void unchangedSelectionRetriesConfirmedDestinationAndTitle() {
        RetryTarget retry = new RetryTarget();
        retry.failed("a", "a", "Chat A", true, retry.generation());
        assertEquals("a", retry.resolve("a").id);
        assertEquals("Chat A", retry.resolve("a").title);
        assertNull(retry.resolve("b"));
        retry.failed(null, "created", "Nuova conversazione", true, retry.generation());
        assertEquals("created", retry.resolve(null).id);
        assertEquals("Conversazione confermata", retry.resolve(null).title);
        // A resumed retry still belongs to the original pending Nuova selection.
        retry.failed(null, "created", "Conversazione confermata", true, retry.generation());
        assertEquals("created", retry.resolve(null).id);
        retry.clear();
        assertNull(retry.resolve(null));
    }
}
