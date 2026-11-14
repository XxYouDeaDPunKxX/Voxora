package local.watchvoice;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static local.watchvoice.DictationState.*;
import java.io.*;
public class DictationTurnStoreTest {
    @Test public void explicitAcknowledgementUnblocksWithoutSendingOrDeletingServerMessage() throws Exception {
        File directory=temporary.newFolder();DictationTurnStore store=new DictationTurnStore(directory);
        assertTrue(store.claim(new SendRecord("user",null,null,"uncertain")));store.clearCache();assertNotNull(store.read());
        store.acknowledge();assertNull(new DictationTurnStore(directory).read());assertTrue(store.claim(new SendRecord("different_user",null,null,"pending")));
    }
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void persistedClaimSurvivesCacheCleanupAndNewStore() throws Exception {
        File directory=temporary.newFolder();DictationTurnStore store=new DictationTurnStore(directory);
        assertTrue(store.claim(new SendRecord("user_1",null,null,"pending")));
        new FileOutputStream(store.file("audio.aac")).close();store.clearCache();
        DictationTurnStore reopened=new DictationTurnStore(directory);
        assertEquals("user_1",reopened.read().userId());
        assertFalse(reopened.claim(new SendRecord("user_2",null,null,"pending")));
        assertFalse(store.file("audio.aac").exists());
    }
    @Test public void incompleteClaimStillBlocksSend() throws Exception {
        File directory=temporary.newFolder();new FileOutputStream(new File(directory,"send.claim")).close();
        DictationTurnStore store=new DictationTurnStore(directory);
        assertFalse(store.claim(new SendRecord("user_2",null,null,"pending")));
        assertEquals("uncertain",store.read().phase());
    }
    @Test public void unresolvedClaimCannotBeClearedAsSuccessful() throws Exception {
        DictationTurnStore store=new DictationTurnStore(temporary.newFolder());
        store.claim(new SendRecord("user_1",null,null,"pending"));
        try {store.clearResolvedClaim();fail();}catch(IOException expected){}
        assertNotNull(store.read());
        store.update(new SendRecord("user_1","chat_1","assistant_1","verified"));store.clearResolvedClaim();
        assertNull(store.read());
    }
}
