package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
public class ReadAloudPlayerStateTest {
    @Test public void seekNeedsCompleteFileAndKnownDurationThenClampsWithoutLosingLatestRequest(){
        ReadAloudPlayer.CachedSeek seek=new ReadAloudPlayer.CachedSeek();assertFalse(seek.request(1000,3000,false));assertEquals(-1,seek.target());
        assertFalse(seek.request(1000,0,true));assertTrue(seek.request(-10,3000,true));assertEquals(0,seek.target());
        assertTrue(seek.request(9999,3000,true));assertEquals(2999,seek.target());assertTrue(seek.request(1500,3000,true));assertEquals(1500,seek.target());
        seek.clear();assertEquals(-1,seek.target());
    }
    @Test public void mediaRouteRecognizesExistingStoredDeviceIdAndNeverFallsBack() {
        assertTrue(ReadAloudPlayer.matchesRoute("device_17",17,false));assertFalse(ReadAloudPlayer.matchesRoute("device_17",18,true));assertTrue(ReadAloudPlayer.matchesRoute("speaker",18,true));assertFalse(ReadAloudPlayer.matchesRoute("speaker",17,false));
    }
    @Test public void latePreparationAfterPauseDoesNotAutoplay() {
        ReadAloudPlayer.PlaybackState state=new ReadAloudPlayer.PlaybackState();state.arm();assertTrue(state.mayAutoStart());
        state.pause();assertFalse(state.mayAutoStart());state.arm();assertTrue(state.mayAutoStart());
    }
    @Test public void stopWaitsForCompleteCacheInsteadOfReopeningProgressive() {
        ReadAloudPlayer.PlaybackState state=new ReadAloudPlayer.PlaybackState();state.arm();state.stop();state.arm();
        assertFalse(state.mayOpen(false));assertTrue(state.mayOpen(true));state.pause();assertFalse(state.mayOpen(true));
    }
}
