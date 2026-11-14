package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.*;
import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.*;
public class DictationRecorderTest {
    @Test public void wavHeaderDescribesTenMinutesWithoutAllocatingRecording() {
        byte[] header=DictationRecorder.wavHeader(19200000);
        assertEquals(44,header.length);ByteBuffer buffer=ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(19200036,buffer.getInt(4));assertEquals(16000,buffer.getInt(24));
        assertEquals(32000,buffer.getInt(28));assertEquals(19200000,buffer.getInt(40));
        assertEquals(1,buffer.getShort(22));assertEquals(16,buffer.getShort(34));
    }
    @Test public void invalidPcmLengthIsRejected() {
        for(long n:new long[]{-1,3,Long.MAX_VALUE}) {
            try {DictationRecorder.wavHeader(n);fail();}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void localLimitRetainsACompleteWavWithoutTransmitting() throws Exception {
        File directory=Files.createTempDirectory("recorder-test").toFile();File file=new File(directory,"recording.wav");
        CountDownLatch limited=new CountDownLatch(1);java.util.concurrent.atomic.AtomicLong clock=new java.util.concurrent.atomic.AtomicLong();
        DictationRecorder.Source source=new DictationRecorder.Source(){
            public void start(){}public int read(byte[] b){b[0]=1;b[1]=2;clock.set(600000);return 2;}public void stop(){}public void close(){}
        };
        DictationRecorder recorder=new DictationRecorder(file,source,clock::get,(g,r)->{assertEquals(7,g.longValue());limited.countDown();},(g,e)->{throw new AssertionError(e);});
        recorder.start(7);assertTrue(limited.await(3,TimeUnit.SECONDS));
        DictationState.Recording result=recorder.finish();assertEquals(2,result.pcmBytes());
        assertEquals(46,file.length());byte[] bytes=Files.readAllBytes(file.toPath());
        assertEquals(2,ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(40));
        assertEquals(1,bytes[44]);assertEquals(2,bytes[45]);recorder.cancel();
        assertFalse(file.exists());Files.delete(directory.toPath());
    }
    @Test public void intentionalStopWithNegativeReadDoesNotReportCaptureFailure()throws Exception {
        File directory=Files.createTempDirectory("stop-test").toFile();File file=new File(directory,"recording.wav");
        CountDownLatch blocked=new CountDownLatch(1),stopped=new CountDownLatch(1);java.util.concurrent.atomic.AtomicInteger failures=new java.util.concurrent.atomic.AtomicInteger();
        DictationRecorder.Source source=new DictationRecorder.Source(){int calls;
            public void start(){}public int read(byte[] b){if(calls++==0){b[0]=1;b[1]=2;return 2;}blocked.countDown();try{stopped.await();}catch(InterruptedException ignored){}return -3;}public void stop(){stopped.countDown();}public void close(){}
        };
        DictationRecorder recorder=new DictationRecorder(file,source,()->0,(g,r)->fail("not a limit"),(g,e)->failures.incrementAndGet());
        recorder.start(9);assertTrue(blocked.await(2,TimeUnit.SECONDS));assertEquals(2,recorder.finish().pcmBytes());assertEquals(0,failures.get());recorder.cancel();Files.delete(directory.toPath());
    }
}
