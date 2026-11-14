package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
public class AudioLevelTest {
    // Catches signed PCM overflow, endianness mistakes, and reading an incomplete sample.
    @Test public void pcmSilenceAndClippingRemainBounded() {
        assertEquals(0f, AudioLevel.pcm16(new byte[32]), 0.0001f);
        assertEquals(1f, AudioLevel.pcm16(new byte[]{0, (byte)128}), 0.0001f);
        assertEquals(0.5f, AudioLevel.pcm16(new byte[]{0, 64}), 0.0001f);
        float clipped = AudioLevel.pcm16(new byte[]{(byte)255, 127, 0, (byte)128});
        assertTrue(clipped >= 0 && clipped <= 1);
        assertEquals(0f, AudioLevel.pcm16(new byte[]{127}), 0.0001f);
    }
}
