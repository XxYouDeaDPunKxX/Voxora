package local.watchvoice;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceDiagnosticsTest {
    // A failure snapshot survives cleanup, but remote/private strings cannot enter Settings.
    @Test public void retainedFailureAcceptsKnownCodesAndRejectsArbitraryText() {
        VoiceDiagnostics failure = new VoiceDiagnostics("resuming", "detail_http_404");
        assertEquals("detail_http_404", failure.code);
        assertEquals("Recupero chat", failure.phase);
        VoiceDiagnostics unsafe = new VoiceDiagnostics("private phase", "token=private text");
        assertEquals("Non disponibile", unsafe.phase);
        assertEquals("unknown_error", unsafe.code);
        assertFalse(unsafe.label().contains("private"));
        assertEquals("unknown_error", new VoiceDiagnostics("connecting", "detail_http_999").code);
        assertEquals("network_io", new VoiceDiagnostics("wifi", "network_io").code);
    }
    @Test public void renewalFailuresKeepSafeDiagnosticCategories() {
        assertEquals("session_expired", new VoiceDiagnostics("resuming", "session_expired").code);
        assertEquals("auth_token_invalid", new VoiceDiagnostics("resuming", "auth_token_invalid").code);
        assertEquals("auth_response_invalid", new VoiceDiagnostics("resuming", "auth_response_invalid").code);
        assertEquals("auth_refresh_failed", new VoiceDiagnostics("resuming", "auth_refresh_failed").code);
        assertEquals("unknown_error", new VoiceDiagnostics("resuming", "private-account-error").code);
    }
}
