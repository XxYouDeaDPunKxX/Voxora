package local.watchvoice;

/** One in-memory failure, without chat identity or arbitrary remote text. */
final class VoiceDiagnostics {
    final String phase, code;
    VoiceDiagnostics(String phase, String reason) {
        this.phase = switch (phase == null ? "" : phase) {
            case "wifi" -> "Rete Wi-Fi"; case "resuming" -> "Recupero chat";
            case "network" -> "Rete del sistema";
            case "connecting" -> "Apertura Voice"; case "connected" -> "Chiamata";
            case "list" -> "Elenco Chat"; case "detail" -> "Anteprima";
            default -> "Non disponibile";
        };
        this.code = safeCode(reason);
    }
    static String safeCode(String reason) {
        if (reason != null && (reason.matches("(?:auth|list|detail|voice)_http_[1-5][0-9]{2}") || CODES.contains(reason))) return reason;
        return "unknown_error";
    }
    private static final java.util.Set<String> CODES = java.util.Set.of(
        "session_expired", "auth_token_invalid", "auth_response_invalid", "auth_refresh_failed", "credential_missing", "credential_format", "credential_invalid",
        "wifi_unavailable", "wifi_bind_failed", "wifi_lost", "network_io", "network_unavailable", "network_changed", "network_lost", "operation_timeout", "connessione_timeout",
        "SocketTimeoutException", "UnknownHostException", "ConnectException", "response_size", "cancelled",
        "conversation_list_invalid", "conversation_detail_invalid", "answer_invalid", "identity_mismatch",
        "microfono_permesso", "microfono_init", "microfono_start", "microfono", "speaker_init", "speaker_start", "speaker",
        "audio_route_unavailable", "audio_route_removed", "audio_route_changed", "audio_route_rejected", "audio_route_timeout",
        "audio_focus_lost", "focus_denied", "rete_rtc", "canale_chiuso", "peer_missing", "sdp_create", "sdp_set");
    String label() { return "Fase fallita: " + phase + "\nUltimo codice: " + code; }
}
