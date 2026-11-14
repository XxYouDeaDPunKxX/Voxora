package local.watchvoice;

import java.io.ByteArrayInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class WatchStoreTest {
    @Test public void oldMetadataDefaultsToVoiceAndNormalSpeed() throws Exception {
        JSONObject old=new JSONObject("{\"options\":{\"voice\":\"cove\"},\"lastChat\":{\"id\":\"old\"}}");
        assertEquals("voice",WatchStore.mode(old));assertEquals(1f,WatchStore.readAloudSpeed(old),0);
        old.put("mode","dictation").put("readAloudSpeed",1.5);
        JSONObject restored=WatchStore.decode(new ByteArrayInputStream(WatchStore.encode(old)));
        assertEquals("dictation",WatchStore.mode(restored));assertEquals(1.5f,WatchStore.readAloudSpeed(restored),0);assertEquals("old",restored.getJSONObject("lastChat").getString("id"));
        restored.put("mode","unknown").put("readAloudSpeed",17);assertEquals("voice",WatchStore.mode(restored));assertEquals(1f,WatchStore.readAloudSpeed(restored),0);
    }
    // Catches writing a valid multibyte cache which the real bounded reader cannot read.
    @Test public void multibyteCachePreservesAllNonCacheRecords() throws Exception {
        JSONObject state = new JSONObject("{\"options\":{\"voice\":\"breeze\",\"volumePercent\":35},\"successfulOptions\":{\"voice\":\"cove\"},\"pendingChat\":{\"id\":\"pending\",\"title\":\"待機\",\"updatedAt\":\"1\"},\"lastChat\":{\"id\":\"last\",\"title\":\"最後\",\"updatedAt\":\"2\"},\"favorites\":[\"fav\"]}");
        JSONArray chats = new JSONArray();
        for (int i = 0; i < 200; i++) chats.put(new JSONObject().put("id", "chat_" + i).put("title", "漢".repeat(500)).put("updatedAt", "3"));
        state.put("chats", chats);
        byte[] encoded = WatchStore.encode(state);
        assertTrue("serialized cache must fit the actual reader", encoded.length <= 256000);
        JSONObject restored = WatchStore.decode(new ByteArrayInputStream(encoded));
        assertEquals("breeze", VoiceOptions.fromJson(restored.getJSONObject("options")).voice);
        assertEquals(35, VoiceOptions.fromJson(restored.getJSONObject("options")).volumePercent);
        assertEquals("cove", VoiceOptions.fromJson(restored.getJSONObject("successfulOptions")).voice);
        assertEquals("pending", restored.getJSONObject("pendingChat").getString("id"));
        assertEquals("待機", restored.getJSONObject("pendingChat").getString("title"));
        assertEquals("last", restored.getJSONObject("lastChat").getString("id"));
        assertEquals("fav", restored.getJSONArray("favorites").getString(0));
        JSONArray retained = restored.getJSONArray("chats");
        assertTrue(retained.length() > 0); assertTrue(retained.length() < 200);
        assertEquals("chat_0", retained.getJSONObject(0).getString("id"));
        assertEquals("漢".repeat(500), retained.getJSONObject(0).getString("title"));
        restored.put("pendingChat", JSONObject.NULL);
        restored.put("options", new JSONObject("{\"voice\":\"maple\",\"volumePercent\":40}"));
        JSONObject again = WatchStore.decode(new ByteArrayInputStream(WatchStore.encode(restored)));
        assertTrue(again.has("pendingChat")); assertTrue(again.isNull("pendingChat"));
        assertEquals("maple", again.getJSONObject("options").getString("voice"));
        assertEquals("cove", again.getJSONObject("successfulOptions").getString("voice"));
        assertEquals("last", again.getJSONObject("lastChat").getString("id"));
        assertEquals("fav", again.getJSONArray("favorites").getString(0));
    }
    // Catches accepting an oversized non-cache record instead of rejecting before disk I/O.
    @Test public void oversizedNonCacheStateIsRejectedBeforeWriting() throws Exception {
        JSONObject state = new JSONObject().put("other", "漢".repeat(100000));
        try { WatchStore.encode(state); fail("unreadable state must not be written"); }
        catch (IllegalStateException expected) { assertEquals("watch_store_size", expected.getMessage()); }
        assertEquals(100000, state.getString("other").length());
    }
    // Catches throwing/crashing or adding a 501st favorite rather than a bounded result.
    @Test public void favoriteLimitRejectsAdditionWithoutChangingStoredCheckboxState() throws Exception {
        JSONObject state = fullFavorites();
        try { assertFalse(WatchStore.toggleFavorite(state, "extra")); }
        catch (IllegalStateException failure) { fail("favorite limit must be an outcome, not an exception"); }
        JSONObject restored = WatchStore.decode(new ByteArrayInputStream(WatchStore.encode(state)));
        assertEquals(500, restored.getJSONArray("favorites").length());
        assertFalse(contains(restored.getJSONArray("favorites"), "extra"));
    }
    // Catches treating the cap as a blanket lock which prevents removal and subsequent addition.
    @Test public void favoriteAtLimitCanBeRemovedThenAnotherAdded() throws Exception {
        JSONObject state = fullFavorites();
        assertTrue(WatchStore.toggleFavorite(state, "fav_0"));
        assertEquals(499, state.getJSONArray("favorites").length());
        assertFalse(contains(state.getJSONArray("favorites"), "fav_0"));
        assertTrue(WatchStore.toggleFavorite(state, "extra"));
        JSONObject restored = WatchStore.decode(new ByteArrayInputStream(WatchStore.encode(state)));
        assertEquals(500, restored.getJSONArray("favorites").length());
        assertTrue(contains(restored.getJSONArray("favorites"), "extra"));
    }
    private boolean contains(JSONArray items, String id) throws Exception {
        for (int i = 0; i < items.length(); i++) if (id.equals(items.getString(i))) return true;
        return false;
    }
    private JSONObject fullFavorites() throws Exception {
        JSONArray ids = new JSONArray(); for (int i = 0; i < 500; i++) ids.put("fav_" + i);
        return new JSONObject().put("favorites", ids);
    }
}
