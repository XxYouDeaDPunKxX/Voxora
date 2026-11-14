package local.watchvoice;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import java.io.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static local.watchvoice.DictationState.*;
public class GrowingAudioSourceTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void partialFramesWaitForAllRequestedBytesAndEofWaitsForComplete()throws Exception {
        File file=temporary.newFile();AtomicReference<AudioProgress> progress=new AtomicReference<>(new AudioProgress(2,false,0,0));
        try(FileOutputStream out=new FileOutputStream(file)){out.write(new byte[]{1,2});}
        GrowingAudioBuffer buffer=new GrowingAudioBuffer(file,progress::get);assertEquals(-1,buffer.size());
        ExecutorService executor=Executors.newSingleThreadExecutor();byte[] output=new byte[4];
        try {
            Future<Integer> pending=executor.submit(()->buffer.readAt(0,output,0,4));Thread.sleep(100);assertFalse(pending.isDone());
            try(FileOutputStream out=new FileOutputStream(file,true)){out.write(new byte[]{3,4});}
            assertEquals(4,(int)pending.get(2,TimeUnit.SECONDS));assertArrayEquals(new byte[]{1,2,3,4},output);
            Future<Integer> eof=executor.submit(()->buffer.readAt(4,output,0,1));Thread.sleep(100);assertFalse(eof.isDone());
            progress.set(new AudioProgress(4,true,0,0));assertEquals(-1,(int)eof.get(2,TimeUnit.SECONDS));assertEquals(4,buffer.size());
        }finally{buffer.close();executor.shutdownNow();}
    }
    @Test public void closeUnblocksAWaitingDecoder()throws Exception {
        GrowingAudioBuffer buffer=new GrowingAudioBuffer(temporary.newFile(),()->new AudioProgress(0,false,0,0));ExecutorService executor=Executors.newSingleThreadExecutor();
        try {Future<Integer> read=executor.submit(()->buffer.readAt(0,new byte[1],0,1));Thread.sleep(100);buffer.close();
            try{read.get(2,TimeUnit.SECONDS);fail();}catch(ExecutionException expected){assertTrue(expected.getCause() instanceof IOException);}
        }finally{buffer.close();executor.shutdownNow();}
    }
}
