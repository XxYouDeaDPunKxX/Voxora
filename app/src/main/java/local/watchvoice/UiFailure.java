package local.watchvoice;

/** Human-facing list/preview messages; technical codes stay in transport diagnostics. */
final class UiFailure {
    static String preview(String reason) {
        if ("detail_http_404".equals(reason)) return VoiceState.failure(reason);
        return "Anteprima non disponibile. " + VoiceState.failure(reason);
    }
    static String list(String reason) { return "Elenco non disponibile. " + VoiceState.failure(reason); }
}
