package local.watchvoice;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class ConversationListTest {
    private ConversationData.Chat chat(String id, String title) { return new ConversationData.Chat(id, title, "2026-10-07"); }
    private ConversationData.Page page(int offset, int total, ConversationData.Chat... chats) { return new ConversationData.Page(Arrays.asList(chats), total, offset, 20); }
    // Catches merging by title instead of the server ID.
    @Test public void duplicateTitlesRemainDistinct() { ConversationList list = new ConversationList(); list.merge(page(0, 2, chat("a", "Saluto"), chat("b", "Saluto"))); assertEquals(2, list.visible("", Set.of(), false).size()); }
    // Catches repeated pages duplicating IDs or keeping outdated metadata.
    @Test public void paginationDeduplicatesIds() { ConversationList list = new ConversationList(); list.merge(page(0, 3, chat("a", "Prima"), chat("b", "Seconda"))); list.merge(page(2, 3, chat("b", "Aggiornata"), chat("c", "Terza"))); assertEquals(3, list.visible("", Set.of(), false).size()); assertEquals("Aggiornata", list.visible("", Set.of(), false).get(1).title); assertEquals(4, list.nextOffset()); }
    @Test public void filterMatchesTitlesCaseInsensitively() { ConversationList list = new ConversationList(); list.merge(page(0, 2, chat("a", "Saluto"), chat("b", "Altro"))); assertEquals(1, list.visible("SALUTO", Set.of(), false).size()); assertEquals("a", list.visible("SALUTO", Set.of(), false).get(0).id); }
    @Test public void emptyPageEndsPagination() { ConversationList list = new ConversationList(); list.merge(page(0, 100)); assertFalse(list.hasMore()); }
    @Test public void favoritesSurviveRefresh() { ConversationList list = new ConversationList(); Set<String> favorites = Set.of("b"); list.merge(page(0, 2, chat("a", "Uno"), chat("b", "Due"))); list.clear(); list.merge(page(0, 2, chat("b", "Rinnovata"), chat("c", "Tre"))); assertEquals(1, list.visible("", favorites, true).size()); assertEquals("b", list.visible("", favorites, true).get(0).id); }
    @Test public void clearResetsPagination() { ConversationList list = new ConversationList(); list.merge(page(0, 1, chat("a", "Uno"))); list.clear(); assertEquals(0, list.nextOffset()); assertTrue(list.hasMore()); assertTrue(list.visible("", Set.of(), false).isEmpty()); }
    @Test public void oldSelectionResponseCannotReplaceNewSelection() { CallGate selectionGate = new CallGate(); long generationA = selectionGate.start(); selectionGate.stop(); long generationB = selectionGate.start(); assertFalse(selectionGate.accepts(generationA)); assertTrue(selectionGate.accepts(generationB)); }
    // A refreshed first page has a new remote frontier, even when local deep rows survive.
    @Test public void automaticRefreshKeepsUnseenChatsReachableAfterRemoteChanges() {
        for (List<ConversationData.Chat> remote : List.of(
                List.of(chat("c", "C"), chat("d", "D"), chat("e", "E"), chat("f", "F")),
                List.of(chat("n1", "New 1"), chat("n2", "New 2"), chat("n3", "New 3"), chat("a", "A"), chat("b", "B"), chat("c", "C"), chat("d", "D"), chat("e", "E"), chat("f", "F")))) {
            ConversationList list = new ConversationList();
            list.merge(new ConversationData.Page(List.of(chat("a", "A"), chat("b", "B")), 6, 0, 2));
            list.merge(new ConversationData.Page(List.of(chat("c", "C"), chat("d", "D")), 6, 2, 2));
            list.refresh(new ConversationData.Page(remote.subList(0, 2), remote.size(), 0, 2), true);
            for (int guard = 0; list.hasMore() && guard < 8; guard++) {
                int next = list.nextOffset();
                list.merge(new ConversationData.Page(remote.subList(next, Math.min(next + 2, remote.size())), remote.size(), next, 2));
            }
            List<String> visible = list.visible("", Set.of(), false).stream().map(chat -> chat.id).toList();
            assertTrue("The retained deep row disappeared", visible.contains("d"));
            assertTrue("A remote deletion made later chats unreachable", visible.containsAll(List.of("e", "f")));
            if (remote.get(0).id.equals("n1")) assertTrue("New chats between the first page and old frontier were skipped", visible.contains("n3"));
            assertEquals("Repeated pages must not duplicate rows", new HashSet<>(visible).size(), visible.size());
        }
    }
}

