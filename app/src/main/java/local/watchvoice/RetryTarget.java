package local.watchvoice;

/** The failed call's destination, independent of its fresh resume node. */
final class RetryTarget {
    private Target target;
    private String requested;
    private long choiceGeneration;
    static final class Target {
        final String id, title;
        Target(String id, String title) { this.id = id; this.title = title; }
    }
    long generation() { return choiceGeneration; }
    void failed(String requested, String confirmed, String title, boolean retry, long generation) {
        if (generation != choiceGeneration) { clear(); return; }
        this.requested = requested;
        target = retry ? new Target(confirmed != null ? confirmed : requested,
            requested == null && confirmed != null ? "Conversazione confermata" : title) : null;
    }
    void clear() { target = null; }
    void choose(String id) {
        choiceGeneration++;
        if (id == null || target == null || !java.util.Objects.equals(id, target.id)) clear();
    }
    Target resolve(String selected) {
        return target != null && (java.util.Objects.equals(selected, requested) || java.util.Objects.equals(selected, target.id)) ? target : null;
    }
}
