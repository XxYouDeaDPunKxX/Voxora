package local.watchvoice;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class LiveVolumeControlTest {
    @Test public void fingerChangesApplyBeforeReleaseButPersistOnlyAtRelease(){
        List<Integer> applied=new ArrayList<>(),saved=new ArrayList<>();var input=new LiveVolumeControl.Input(80,applied::add,saved::add);
        input.start();input.progress(25,true);assertEquals(List.of(25),applied);assertTrue(saved.isEmpty());
        input.progress(35,true);input.stop();assertEquals(35,(int)applied.get(applied.size()-1));assertEquals(List.of(35),saved);
    }
    @Test public void keyboardAndAccessibilityProgressDoNotNeedTouchStop(){
        List<Integer> applied=new ArrayList<>(),saved=new ArrayList<>();var input=new LiveVolumeControl.Input(80,applied::add,saved::add);
        input.progress(15,true);assertEquals(List.of(15),applied);assertEquals(List.of(15),saved);
    }
    @Test public void programmaticRefreshNeverChangesPlaybackOrSaves(){
        List<Integer> applied=new ArrayList<>(),saved=new ArrayList<>();var input=new LiveVolumeControl.Input(80,applied::add,saved::add);
        input.progress(20,false);assertTrue(applied.isEmpty());assertTrue(saved.isEmpty());
    }
}
