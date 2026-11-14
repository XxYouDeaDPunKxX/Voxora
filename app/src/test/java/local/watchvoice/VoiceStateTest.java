package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoiceStateTest {
    @Test public void readRateLimitDoesNotClaimVoiceQuotaIsExhausted(){
        String text=VoiceState.failure("detail_http_429");assertTrue(text.contains("lettura"));assertTrue(text.contains("temporaneo"));assertFalse(text.contains("quota Voice"));
    }
    @Test public void microphoneWaitsUntilCaptureCanBeActive() {
        assertEquals("in attesa", new VoiceState("wifi", "", false, null).microphoneLabel());
        assertEquals("in attesa", new VoiceState("resuming", "", false, null).microphoneLabel());
        assertEquals("in apertura", new VoiceState("connecting", "", false, null).microphoneLabel());
        assertEquals("attivo", new VoiceState("connected", "listening", false, "chat").microphoneLabel());
        assertEquals("disattivato", new VoiceState("connected", "listening", true, "chat").microphoneLabel());
    }
    // Catches opening the channel being presented as active server listening.
    @Test public void channelOpenIsConnectedNotListening() {
        assertEquals("Connesso", new VoiceState("connected", "", false, "chat").label());
        assertEquals("Connesso", new VoiceState("connected", "idle", false, "chat").label());
    }
    @Test public void muteOverridesListeningLabel() {
        assertEquals("Microfono disattivato", new VoiceState("connected", "listening", true, "chat").label());
        assertEquals("In ascolto", new VoiceState("connected", "listening", false, "chat").label());
    }
    @Test public void unknownStateDoesNotInventActivity() {
        assertEquals("Connesso", new VoiceState("connected", "unrecognized", false, "chat").label());
        assertEquals("Sta rispondendo", new VoiceState("connected", "speaking", false, "chat").label());
    }
    @Test public void authNetworkAndAudioFailuresAreDistinct() {
        assertTrue(VoiceState.failure("voice_http_401").contains("manutenzione"));
        assertTrue(VoiceState.failure("session_expired").contains("manutenzione"));
        assertTrue(VoiceState.failure("rete_rtc").contains("rete"));
        assertTrue(VoiceState.failure("microfono_init").contains("microfono"));
        assertFalse(VoiceState.failure("voice_http_429").contains("manutenzione"));
        assertFalse(VoiceState.failure("secret conversation text").contains("secret"));
    }
    @Test public void authFailuresDoNotOverstateRevocationOrConflateAccessDenied() {
        String expired = VoiceState.failure("session_expired").toLowerCase(java.util.Locale.ROOT);
        assertTrue(expired.contains("manutenzione"));
        assertTrue(expired.contains("scadut"));
        assertTrue(expired.contains("rinnov"));
        assertFalse(expired.contains("revocat"));

        for (String code : new String[]{"auth_token_invalid", "auth_response_invalid", "auth_refresh_failed"}) {
            String message = VoiceState.failure(code).toLowerCase(java.util.Locale.ROOT);
            assertTrue(code, message.contains("rinnov"));
            assertFalse(code, message.contains("revocat"));
        }

        for (String code : new String[]{"auth_http_401", "list_http_401", "detail_http_401", "voice_http_401"}) {
            String message = VoiceState.failure(code).toLowerCase(java.util.Locale.ROOT);
            assertTrue(code, message.contains("manutenzione"));
            assertFalse(code, message.contains("revocat"));
            assertFalse(code, message.contains("scadut"));
        }

        for (String code : new String[]{"auth_http_403", "list_http_403", "detail_http_403", "voice_http_403"}) {
            String message = VoiceState.failure(code).toLowerCase(java.util.Locale.ROOT);
            assertTrue(code, message.contains("negat"));
            assertFalse(code, message.contains("revocat"));
            assertFalse(code, message.contains("scadut"));
        }
    }
}

