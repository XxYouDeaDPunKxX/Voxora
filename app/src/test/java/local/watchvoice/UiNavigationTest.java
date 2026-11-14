package local.watchvoice;

import org.junit.Test;
import static org.junit.Assert.*;

public class UiNavigationTest {
    @Test public void contextualReaderRestoresItsOriginAndStatusClosesFirst() {
        UiNavigation nav=new UiNavigation();nav.open(UiNavigation.CHATS);nav.remember(120);
        nav.show(UiNavigation.DETAIL);nav.show(UiNavigation.READER);nav.remember(800);
        nav.show(UiNavigation.STATUS);UiNavigation copy=new UiNavigation();copy.restore(nav.path(),nav.positions());
        assertTrue(copy.back());assertEquals(UiNavigation.READER,copy.screen());assertEquals(800,copy.position());
        assertTrue(copy.back());assertEquals(UiNavigation.DETAIL,copy.screen());
        assertTrue(copy.back());assertEquals(UiNavigation.CHATS,copy.screen());assertEquals(120,copy.position());
    }
    // Catches Back skipping the menu/selector origin or dismissing the Activity too early.
    @Test public void backReturnsThroughSecondaryPagesThenSession() {
        UiNavigation nav = new UiNavigation();
        nav.open(UiNavigation.CHATS);
        nav.show(UiNavigation.MENU);
        nav.show(UiNavigation.CONFIRM);
        assertTrue(nav.back()); assertEquals(UiNavigation.MENU, nav.screen());
        assertTrue(nav.back()); assertEquals(UiNavigation.CHATS, nav.screen());
        assertTrue(nav.back()); assertEquals(UiNavigation.SESSION, nav.screen());
        assertFalse(nav.back());
        nav.open(UiNavigation.SETTINGS); nav.show(UiNavigation.VOICE);
        assertTrue(nav.back()); assertEquals(UiNavigation.SETTINGS, nav.screen());
    }
    // Catches a replacement page overwriting its origin's scroll position.
    @Test public void positionsBelongToEachVisiblePage() {
        UiNavigation nav = new UiNavigation();
        nav.open(UiNavigation.CHATS); nav.remember(175);
        nav.show(UiNavigation.MENU); nav.remember(29);
        nav.back(); assertEquals(175, nav.position());
        nav.show(UiNavigation.MENU); assertEquals(29, nav.position());
        nav.open(UiNavigation.SETTINGS); assertEquals(0, nav.position());
        nav.remember(240); nav.show(UiNavigation.LANGUAGE); nav.remember(80);
        nav.back(); assertEquals(240, nav.position());
    }
    // Catches lifecycle recreation losing the selector's parent and saved offsets.
    @Test public void restoredPageKeepsBackPathAndScrollOffsets() {
        UiNavigation nav = new UiNavigation();
        nav.open(UiNavigation.SETTINGS); nav.remember(240);
        nav.show(UiNavigation.ROUTE); nav.remember(48);
        UiNavigation restored = new UiNavigation();
        restored.restore(nav.path(), nav.positions());
        assertEquals(UiNavigation.ROUTE, restored.screen()); assertEquals(48, restored.position());
        restored.back(); assertEquals(UiNavigation.SETTINGS, restored.screen()); assertEquals(240, restored.position());
        restored.back(); assertEquals(UiNavigation.SESSION, restored.screen());
    }
}
