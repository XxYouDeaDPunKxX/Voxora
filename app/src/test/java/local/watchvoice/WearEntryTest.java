package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
public class WearEntryTest {
    // Catches a shortcut replacing a call already owned by VoiceService.
    @Test public void entryReturnsToActiveCall() {
        assertEquals("session", WearEntry.action("new", true, true));
        assertEquals("session", WearEntry.action("resume", true, false));
    }
    @Test public void resumeWithoutLastChatShowsChooser() {
        assertEquals("chats", WearEntry.action("resume", false, false));
    }
    @Test public void unknownEntryOpensAppWithoutStartingMic() {
        assertEquals("session", WearEntry.action("unknown", false, true));
        assertEquals("session", WearEntry.action(null, false, false));
    }
    @Test public void idleEntriesChooseExplicitDestination() {
        assertEquals("new", WearEntry.action("new", false, true));
        assertEquals("resume", WearEntry.action("resume", false, true));
        assertEquals("chats", WearEntry.action("chats", false, true));
        assertEquals("session", WearEntry.action("session", false, true));
    }
}
