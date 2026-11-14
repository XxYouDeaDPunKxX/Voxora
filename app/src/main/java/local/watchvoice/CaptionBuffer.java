package local.watchvoice;
import java.util.*;
/** Current-call memory only. *_start marks the observed message-ID boundary. */
final class CaptionBuffer {
    private final List<ConversationData.Turn> turns = new ArrayList<>();
    private boolean newMessage;
    synchronized void add(String kind, String text) {
        if (kind == null || text == null) return;
        if (kind.equals("user_start") || kind.equals("assistant_start")) { newMessage = true; return; }
        if ((!kind.equals("user") && !kind.equals("assistant")) || text.isEmpty()) return;
        String bounded = text.substring(0, Math.min(2000, text.length()));
        ConversationData.Turn turn = new ConversationData.Turn(kind, bounded);
        if (!newMessage && !turns.isEmpty() && turns.get(turns.size() - 1).role.equals(kind)) turns.set(turns.size() - 1, turn);
        else turns.add(turn);
        newMessage = false;
        while (turns.size() > 12) turns.remove(0);
    }
    synchronized List<ConversationData.Turn> snapshot() { return Collections.unmodifiableList(new ArrayList<>(turns)); }
}
