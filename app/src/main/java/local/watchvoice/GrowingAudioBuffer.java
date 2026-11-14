package local.watchvoice;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.function.Supplier;
import static local.watchvoice.DictationState.*;
/** A temporarily short file is not EOF. Wait for a full decoder read unless download completed. */
final class GrowingAudioBuffer implements AutoCloseable {
    private final FileInputStream input;
    private final FileChannel channel;
    private final Supplier<AudioProgress> progress;
    private volatile boolean closed;
    GrowingAudioBuffer(File file,Supplier<AudioProgress> progress)throws IOException{input=new FileInputStream(file);channel=input.getChannel();this.progress=progress;}
    long size()throws IOException{check();return progress.get().complete()?channel.size():-1;}
    int readAt(long position,byte[] target,int offset,int size)throws IOException {
        if(position<0||offset<0||size<0||offset>target.length-size)throw new IOException("audio_read_range");if(size==0)return 0;
        long deadline=System.nanoTime()+60000000000L;
        while(true){
            check();long available=Math.max(0,channel.size()-position);boolean complete=progress.get().complete();
            if(available>=size || (complete&&available>0))return channel.read(ByteBuffer.wrap(target,offset,(int)Math.min(size,available)),position);
            if(complete)return -1;if(System.nanoTime()>deadline)throw new IOException("audio_read_timeout");
            try{Thread.sleep(25);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("audio_read_cancelled");}
        }
    }
    private void check()throws IOException{if(closed)throw new IOException("audio_source_closed");}
    public void close()throws IOException{closed=true;input.close();}
}
