package local.watchvoice;

import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ChatReadingTest {
    private ConversationData.Chat chat(String id, String title) { return new ConversationData.Chat(id, title, ""); }
    private ConversationData.Page page(int offset, ConversationData.Chat... items) {
        return new ConversationData.Page(Arrays.asList(items), 8, offset, 20);
    }
    private List<ConversationData.Chat> all(ConversationList list) { return list.visible("", Set.of(), false); }
    // First-page refresh must not discard a reader's already loaded second-page row.
    @Test public void returningOnSecondPageKeepsChatAndLocalRowOffset() {
        ConversationList list = new ConversationList();
        list.merge(page(0, chat("a", "A"), chat("b", "B")));
        list.merge(page(2, chat("c", "C"), chat("d", "D")));
        ConversationList.Anchor anchor = ConversationList.anchor(all(list), new int[]{40,100,160,220}, new int[]{100,160,220,280}, 177);
        list.refresh(page(0, chat("b", "B aggiornata"), chat("a", "A")), true);
        assertEquals(List.of("b","a","c","d"), all(list).stream().map(c -> c.id).toList());
        assertEquals(2, list.nextOffset());
        assertTrue(list.hasMore());
        assertEquals(207, anchor.restore(all(list), new int[]{40,130,190,250}, 177));
    }
    // Same count is insufficient: a reordered/taller preceding row changes pixel position.
    @Test public void reorderedFirstPageFollowsSameChatInsteadOfOldPixel() {
        ConversationList list = new ConversationList();
        list.merge(page(0, chat("a", "A"), chat("b", "B")));
        ConversationList.Anchor anchor = ConversationList.anchor(all(list), new int[]{40,100}, new int[]{100,160}, 109);
        list.refresh(page(0, chat("b", "B longer"), chat("a", "A")), true);
        assertEquals("B longer", all(list).get(0).title);
        assertEquals(49, anchor.restore(all(list), new int[]{40,130}, 109));
    }
    // Manual Aggiorna replaces recent entries and restarts pagination intentionally.
    @Test public void manualRefreshRemovesStaleRecentRowsAndRestartsPages() {
        ConversationList list = new ConversationList();
        list.merge(page(0, chat("a", "A"), chat("b", "B")));
        list.merge(page(2, chat("c", "C"), chat("d", "D")));
        list.refresh(page(0, chat("b", "B"), chat("e", "E")), false);
        assertEquals(List.of("b","e"), all(list).stream().map(c -> c.id).toList());
        assertEquals(2, list.nextOffset());
    }
}
