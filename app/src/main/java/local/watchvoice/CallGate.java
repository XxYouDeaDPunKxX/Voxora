package local.watchvoice;

/** Rejects callbacks belonging to a stopped or replaced call. */
final class CallGate {
    private long generation;
    private boolean active;
    synchronized long start() {
        if (active) return -1;
        active = true;
        return ++generation;
    }
    synchronized void stop() { active = false; }
    synchronized boolean accepts(long value) { return active && generation == value; }
}
