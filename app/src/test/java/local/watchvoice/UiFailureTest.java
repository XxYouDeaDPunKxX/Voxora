package local.watchvoice;

import org.junit.Test;
import static org.junit.Assert.*;

public class UiFailureTest {
    // Catches exposing raw404 or claiming deletion/falling back to a new conversation.
    @Test public void unavailablePreviewOffersRecoveryWithoutInventingDeletion() {
        assertTrue(VoiceState.failure("detail_http_404").contains("Chat non disponibile"));
        String message = UiFailure.preview("detail_http_404");
        assertTrue(message.contains("Chat non disponibile"));
        assertTrue(message.toLowerCase(java.util.Locale.ITALY).contains("aggiorna"));
        assertFalse(message.contains("detail_http_404"));
        assertFalse(message.contains("eliminata"));
        assertFalse(message.contains("Nuova conversazione"));
    }
    // Catches replacing the existing useful network/auth classification with generic text.
    @Test public void previewAndListReuseKnownFailureCategories() {
        assertTrue(UiFailure.preview("rete_timeout").contains("Errore rete:"));
        String denied = UiFailure.list("list_http_401").toLowerCase(java.util.Locale.ROOT);
        assertTrue(denied.contains("accesso rifiutato"));
        assertFalse(denied.contains("revocat"));
    }
    // Catches reflecting an arbitrary exception reason into the product UI.
    @Test public void unknownReasonIsNotReflected() {
        assertFalse(UiFailure.preview("untrusted_detail_reason").contains("untrusted_detail_reason"));
        assertFalse(UiFailure.list("untrusted_list_reason").contains("untrusted_list_reason"));
    }
}
