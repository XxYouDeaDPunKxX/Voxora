package local.watchvoice;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class CaptionBufferTest {
    // Catches appending a partial transcript as a second copy and unbounded call memory.
    @Test public void captionsReplacePartialAndBoundMemory() {
        CaptionBuffer buffer = new CaptionBuffer();
        buffer.add("user_start", ""); buffer.add("user", "cia"); buffer.add("user", "ciao");
        assertEquals(1, buffer.snapshot().size()); assertEquals("ciao", buffer.snapshot().get(0).text);
        for (int i = 0; i < 20; i++) { buffer.add("user_start", ""); buffer.add("user", "u" + i); buffer.add("assistant_start", ""); buffer.add("assistant", "a" + i); }
        assertTrue(buffer.snapshot().size() <= 12); assertEquals("u14", buffer.snapshot().get(0).text);
        buffer.add("assistant", "x".repeat(3000));
        for (ConversationData.Turn turn : buffer.snapshot()) assertTrue(turn.text.length() <= 2000);
        List<ConversationData.Turn> snapshot = buffer.snapshot();
        buffer.add("user_start", ""); buffer.add("user", "new"); assertEquals(2000, snapshot.get(snapshot.size()-1).text.length());
    }
    @Test public void sameRoleNewMessageDoesNotOverwritePreviousTurn() {
        CaptionBuffer buffer = new CaptionBuffer(); buffer.add("user", "first");
        buffer.add("user_start", ""); buffer.add("user", "second"); buffer.add("tool", "private");
        assertEquals(2, buffer.snapshot().size()); assertEquals("first", buffer.snapshot().get(0).text);
    }
}
