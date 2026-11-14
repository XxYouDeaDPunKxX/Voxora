package local.watchvoice;
final class CallNetwork {
    enum Action { WAIT, START, KEEP, STOP }
    private Long selected;
    private boolean ended;
    Action available(long handle, boolean usable) {
        if (ended) return Action.WAIT;
        if (selected != null && selected == handle) return Action.KEEP;
        if (!usable) return Action.WAIT;
        if (selected != null) { ended = true; return Action.STOP; }
        selected = handle; return Action.START;
    }
    boolean lost(long handle) {
        if (ended || selected == null || selected != handle) return false;
        ended = true; return true;
    }
    boolean started() { return selected != null; }
}
