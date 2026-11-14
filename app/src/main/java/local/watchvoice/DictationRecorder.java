package local.watchvoice;
import java.nio.*;
import java.io.*;
import java.util.function.*;
import android.media.AudioRecord;
import android.media.AudioFormat;
import android.media.MediaRecorder;
import android.os.SystemClock;
import static local.watchvoice.DictationState.*;
final class DictationRecorder {
    interface Source extends AutoCloseable {void start();int read(byte[] bytes);void stop();void close();}
    private final File file;
    private final Source source;
    private final LongSupplier clock;
    private final BiConsumer<Long,Recording> onLimit;
    private final BiConsumer<Long,String> onError;
    private long generation;
    private volatile boolean running,limited;
    private volatile String failure;
    private volatile long pcmBytes;
    private Thread thread;
    DictationRecorder(File file,BiConsumer<Long,Recording> onLimit,BiConsumer<Long,String> onError){this(file,new Hardware(),SystemClock::elapsedRealtime,onLimit,onError);}
    DictationRecorder(File file,Source source,LongSupplier clock,BiConsumer<Long,Recording> onLimit,BiConsumer<Long,String> onError){this.file=file;this.source=source;this.clock=clock;this.onLimit=onLimit;this.onError=onError;}
    synchronized void start(long generation)throws IOException {
        if(thread!=null)throw new IOException("recording_busy");
        if(file.getParentFile().getUsableSpace()<128L*1024*1024)throw new IOException("storage_low");
        this.generation=generation;running=true;thread=new Thread(this::capture,"Voxora-dictation-recorder");thread.start();
    }
    private void capture(){
        long start=clock.getAsLong();byte[] buffer=new byte[32768];
        try(RandomAccessFile out=new RandomAccessFile(file,"rw")){
            out.setLength(0);out.write(wavHeader(0));source.start();
            while(running){
                if(clock.getAsLong()-start>=600000 || pcmBytes>=19200000){limited=true;break;}
                int count;
                try{count=source.read(buffer);}catch(RuntimeException stopped){if(!running)break;throw stopped;}
                if(!running)break;
                if(count<0)throw new IOException("recording_read");
                if(count==0){try{Thread.sleep(10);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}continue;}
                if(count>buffer.length || (count&1)!=0)throw new IOException("recording_alignment");
                int usable=(int)Math.min(count,19200000-pcmBytes);
                if(file.getParentFile().getUsableSpace()<buffer.length+65536)throw new IOException("storage_low");
                out.write(buffer,0,usable);pcmBytes+=usable;
            }
            out.seek(0);out.write(wavHeader(pcmBytes));out.getFD().sync();
        }catch(Exception error){failure=error instanceof IOException && "storage_low".equals(error.getMessage())?"storage_low":"recording_failed";}
        finally {
            running=false;try{source.stop();}catch(RuntimeException ignored){}try{source.close();}catch(RuntimeException ignored){}
            if(failure!=null)onError.accept(generation,failure);else if(limited)onLimit.accept(generation,result());
        }
    }
    Recording finish()throws IOException {
        Thread active;
        synchronized(this){active=thread;if(active==null)throw new IOException("recording_missing");running=false;}
        try{source.stop();}catch(RuntimeException ignored){}
        if(active!=Thread.currentThread())try{active.join(3000);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("recording_cancelled");}
        if(active.isAlive() || failure!=null)throw new IOException(failure==null?"recording_stop":failure);
        return result();
    }
    void cancel(){try{if(thread!=null)finish();else source.close();}catch(Exception ignored){}if(thread==null||!thread.isAlive())file.delete();}
    private Recording result(){return new Recording(file,pcmBytes*1000/32000,pcmBytes);}
    private static final class Hardware implements Source {
        private final AudioRecord recorder;
        Hardware(){int min=AudioRecord.getMinBufferSize(16000,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);if(min<=0)throw new IllegalStateException("recording_format");
            try{recorder=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC).setAudioFormat(new AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(Math.max(min,32768)).build();}
            catch(SecurityException denied){throw new IllegalStateException("microphone_permission");}
            if(recorder.getState()!=AudioRecord.STATE_INITIALIZED){recorder.release();throw new IllegalStateException("recording_init");}}
        public void start(){recorder.startRecording();}public int read(byte[] bytes){return recorder.read(bytes,0,bytes.length,AudioRecord.READ_BLOCKING);}
        public void stop(){if(recorder.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)recorder.stop();}public void close(){recorder.release();}
    }
    static byte[] wavHeader(long pcmBytes){
        if(pcmBytes<0 || (pcmBytes&1)!=0 || pcmBytes>0xffffffffL-36)throw new IllegalArgumentException("wav_length");
        ByteBuffer out=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        out.put(new byte[]{'R','I','F','F'}).putInt((int)(pcmBytes+36)).put(new byte[]{'W','A','V','E','f','m','t',' '})
            .putInt(16).putShort((short)1).putShort((short)1).putInt(16000).putInt(32000).putShort((short)2).putShort((short)16)
            .put(new byte[]{'d','a','t','a'}).putInt((int)pcmBytes);return out.array();
    }
}
