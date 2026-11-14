package local.watchvoice;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class VoiceOptionsTest {
    // Catches accepting a persisted unknown voice/language as a valid selectable configuration.
    @Test public void unsupportedSelectionsCannotReplaceValidDefaults() throws Exception {
        VoiceOptions value = VoiceOptions.fromJson(new JSONObject("{\"voice\":\"unknown\",\"language\":\"xx-YY\"}"));
        assertEquals("juniper", value.voice); assertEquals("it-IT", value.language);
    }
    // Catches lost preferences and volume values escaping the allowed range.
    @Test public void optionsRoundTripAndVolumeBounds() throws Exception {
        VoiceOptions options = VoiceOptions.defaults();
        assertEquals("juniper", options.voice); assertEquals("it-IT", options.language);
        assertEquals("speaker", options.route); assertEquals(100, options.volumePercent);
        assertTrue(options.haptics); assertFalse(options.largeText); assertFalse(options.captions); assertFalse(options.keepScreenOn);
        VoiceOptions custom = VoiceOptions.fromJson(new JSONObject("{\"voice\":\"breeze\",\"language\":\"en-US\",\"route\":\"speaker\",\"volumePercent\":35,\"haptics\":false,\"largeText\":true,\"captions\":true,\"keepScreenOn\":true}"));
        VoiceOptions restored = VoiceOptions.fromJson(custom.toJson());
        assertEquals("breeze", restored.voice); assertEquals("en-US", restored.language); assertEquals(35, restored.volumePercent);
        assertFalse(restored.haptics); assertTrue(restored.largeText); assertTrue(restored.captions); assertTrue(restored.keepScreenOn);
        assertEquals(0, VoiceOptions.fromJson(new JSONObject("{\"volumePercent\":-2}")).volumePercent);
        assertEquals(100, VoiceOptions.fromJson(new JSONObject("{\"volumePercent\":101}")).volumePercent);
    }
}
