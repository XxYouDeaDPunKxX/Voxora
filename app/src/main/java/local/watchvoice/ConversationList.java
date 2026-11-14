package local.watchvoice;
import java.util.*;
final class ConversationList {
    private final Map<String, ConversationData.Chat> chats = new LinkedHashMap<>();
    private int offset;
    private boolean more = true;
    void merge(ConversationData.Page page) {
        for (ConversationData.Chat chat : page.items) chats.put(chat.id, chat);
        offset = Math.max(offset, page.offset + page.items.size());
        more = !page.items.isEmpty() && offset < page.total;
    }
    List<ConversationData.Chat> visible(String query, Set<String> favorites, boolean favoritesOnly) {
        String filter = query.trim().toLowerCase(Locale.ROOT);
        List<ConversationData.Chat> result = new ArrayList<>();
        for (ConversationData.Chat chat : chats.values())
            if ((!favoritesOnly || favorites.contains(chat.id)) && chat.title.toLowerCase(Locale.ROOT).contains(filter)) result.add(chat);
        return result;
    }
    boolean hasMore() { return more; }
    int nextOffset() { return offset; }
    void refresh(ConversationData.Page page, boolean automatic) {
        if (!automatic) { clear(); merge(page); return; }
        Map<String, ConversationData.Chat> previous = new LinkedHashMap<>(chats);
        chats.clear();
        for (ConversationData.Chat chat : page.items) chats.put(chat.id, chat);
        // Absence from the first page says nothing about deletion or deeper pages.
        previous.forEach(chats::putIfAbsent);
        // Cached rows preserve reading, but cannot locate the next page in a reordered catalog.
        offset = page.offset + page.items.size();
        more = !page.items.isEmpty() && offset < page.total;
    }
    static final class Anchor {
        final String id;
        final int withinRow;
        Anchor(String id, int withinRow) { this.id = id; this.withinRow = withinRow; }
        int restore(List<ConversationData.Chat> visible, int[] tops, int fallback) {
            for (int i = 0; i < visible.size(); i++) if (visible.get(i).id.equals(id)) return Math.max(0, tops[i] + withinRow);
            return fallback;
        }
    }
    static Anchor anchor(List<ConversationData.Chat> visible, int[] tops, int[] bottoms, int pixels) {
        for (int i = 0; i < visible.size(); i++)
            if (tops[i] <= pixels && bottoms[i] > pixels) return new Anchor(visible.get(i).id, pixels - tops[i]);
        return null;
    }
    void clear() { chats.clear(); offset = 0; more = true; }
}
