package local.watchvoice;
import android.media.MediaDataSource;
import java.io.*;
import java.util.function.Supplier;
import static local.watchvoice.DictationState.*;
final class GrowingAudioSource extends MediaDataSource {
    private final GrowingAudioBuffer buffer;
    private int diagnostics;
    GrowingAudioSource(File file,Supplier<AudioProgress> progress)throws IOException{buffer=new GrowingAudioBuffer(file,progress);}
    public int readAt(long position,byte[] data,int offset,int size)throws IOException{
        long start=android.os.SystemClock.elapsedRealtime();boolean trace=diagnostics++<12;
        if(trace)android.util.Log.i("VoxoraDictation","source_read position="+position+" requested="+size);
        int read=buffer.readAt(position,data,offset,size);
        if(trace)android.util.Log.i("VoxoraDictation","source_result bytes="+read+" waited_ms="+(android.os.SystemClock.elapsedRealtime()-start));
        return read;
    }
    public long getSize()throws IOException{return buffer.size();}
    public void close()throws IOException{buffer.close();}
}
