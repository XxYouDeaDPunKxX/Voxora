package local.watchvoice;

/** Loading and freshness presentation only; cache and selection remain with their owners. */
final class ChatRefreshState {
    private boolean loading;
    private String failure = "";
    boolean begin() {
        if (loading) return false;
        loading = true; return true;
    }
    boolean loading() { return loading; }
    void completed() { loading = false; failure = ""; }
    void failed(String reason) { loading = false; failure = UiFailure.list(reason); }
    void cancel() { loading = false; }
    String label() {
        if (loading) return "Aggiornamento elenco…";
        return failure.isEmpty() ? "" : "Elenco non aggiornato. Resta visibile l’ultimo elenco salvato.\n" + failure;
    }
}
