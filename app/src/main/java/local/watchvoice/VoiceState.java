package local.watchvoice;

/** Immutable presentation snapshot, not a transition engine. */
final class VoiceState {
    final String phase, serverState, conversationId;
    final boolean muted;
    VoiceState(String phase, String serverState, boolean muted, String conversationId) {
        this.phase = phase; this.serverState = serverState; this.muted = muted; this.conversationId = conversationId;
    }
    String microphoneLabel() {
        if ("connected".equals(phase)) return muted ? "disattivato" : "attivo";
        return "connecting".equals(phase) ? "in apertura" : "in attesa";
    }
    String label() {
        if (!"connected".equals(phase)) return switch (phase) {
            case "wifi" -> "Rete Wi-Fi…";
            case "network" -> "Rete del sistema…";
            case "resuming" -> "Recupero chat…";
            case "connecting" -> "Apertura Voice…";
            default -> "Pronto";
        };
        if (muted) return "Microfono disattivato";
        return switch (serverState) {
            case "listening", "listening_intently" -> "In ascolto";
            case "thinking" -> "Sta pensando";
            case "speaking" -> "Sta rispondendo";
            default -> "Connesso";
        };
    }
    static String failure(String reason) {
        if (reason == null) return "Errore del servizio Voice";
        if(reason.equals("detail_http_429"))return "Limite temporaneo sulla lettura della chat: riprova più tardi";
        if(reason.equals("list_http_429"))return "Limite temporaneo sull’elenco chat: riprova più tardi";
        if (reason.equals("detail_http_404")) return "Chat non disponibile: aggiorna l’elenco Chat o scegli un’altra conversazione.";
        if (reason.equals("session_expired")) return "Sessione scaduta e rinnovo non disponibile: manutenzione necessaria dal computer";
        if (reason.equals("auth_token_invalid") || reason.equals("auth_response_invalid") || reason.equals("auth_refresh_failed"))
            return "Rinnovo della sessione non riuscito: verifica la configurazione dal computer";
        if (reason.equals("auth_http_401") || reason.endsWith("_http_401"))
            return "Accesso rifiutato dal servizio: verifica la sessione dal computer; potrebbe servire manutenzione";
        if (reason.startsWith("microfono") || reason.equals("audio_permission")) return "Errore microfono: verifica il permesso audio";
        if (reason.startsWith("speaker") || reason.startsWith("audio") || reason.equals("focus_denied")) return "Errore audio: uscita non disponibile";
        if (reason.contains("timeout") || reason.startsWith("rete") || reason.startsWith("wifi") || reason.startsWith("network_") || reason.equals("SocketTimeoutException") || reason.equals("UnknownHostException") || reason.equals("ConnectException")) return "Errore rete: verifica telefono o Wi-Fi e riprova";
        if (reason.equals("voice_http_429")) return "Voice non disponibile: limite del servizio";
        if (reason.equals("voice_http_400")) return "Configurazione Voice non accettata: ripristina quella riuscita";
        if (reason.equals("auth_http_403") || reason.endsWith("_http_403")) return "Accesso negato dal servizio: verifica account e autorizzazioni dal computer";
        return "Errore del servizio Voice; puoi riprovare";
    }
}

