package local.watchvoice;
import android.content.Context;
import android.media.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.util.function.*;
import static local.watchvoice.DictationState.*;
final class ReadAloudPlayer {
    static final class CachedSeek {
        private int target=-1;
        boolean request(int position,int duration,boolean complete){if(!complete||duration<=0)return false;target=Math.max(0,Math.min(duration-1,position));return true;}
        int target(){return target;}
        void clear(){target=-1;}
    }
    static final class PlaybackState {
        private boolean armed,cacheOnly;
        void arm(){armed=true;}
        void pause(){armed=false;}
        void stop(){armed=false;cacheOnly=true;}
        boolean mayAutoStart(){return armed;}
        boolean mayOpen(boolean complete){return armed&&(!cacheOnly||complete);}
    }
    record Snapshot(String phase,int positionMs,int durationMs,float speed,boolean complete,String error) {}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AudioManager audio;
    private final VoiceOptions options;
    private final Consumer<Snapshot> changed;
    private PlaybackState intent=new PlaybackState();
    private final CachedSeek seek=new CachedSeek();
    private MediaPlayer player;
    private GrowingAudioSource source;
    private AudioFocusRequest focus;
    private AudioDeviceInfo route;
    private File file;
    private Supplier<AudioProgress> progress;
    private boolean prepared,progressive,failed,closed,atEnd,firstProgress;
    private int generation,lastPosition,duration;
    private float speed;
    private String phase="idle",error="";
    private final Runnable poll=new Runnable(){public void run(){if(closed)return;maybeOpen();publish();main.postDelayed(this,250);}};
    private final AudioDeviceCallback devices=new AudioDeviceCallback(){@Override public void onAudioDevicesRemoved(AudioDeviceInfo[] removed){if(route!=null)for(AudioDeviceInfo device:removed)if(device.getId()==route.getId()){pause();error="route_removed";publish();}}};
    ReadAloudPlayer(Context context,VoiceOptions options,float speed,Consumer<Snapshot> changed){audio=context.getSystemService(AudioManager.class);this.options=options;this.speed=speed;this.changed=changed;audio.registerAudioDeviceCallback(devices,main);}
    void load(File file,Supplier<AudioProgress> progress,boolean autoPlay){
        seek.clear();release();this.file=file;this.progress=progress;closed=false;failed=false;atEnd=false;lastPosition=0;duration=0;error="";phase="buffering";intent=new PlaybackState();if(autoPlay)intent.arm();
        main.removeCallbacks(poll);main.post(poll);
    }
    void play(){if(closed)return;intent.arm();error="";
        if(atEnd){intent.stop();intent.arm();release();atEnd=false;lastPosition=0;}
        if(failed){if(!complete()){phase="waiting_cache";publish();return;}failed=false;}
        if(prepared)start();else maybeOpen();publish();
    }
    void pause(){intent.pause();if(player!=null&&prepared)try{lastPosition=safePosition();if(player.isPlaying())player.pause();}catch(IllegalStateException ignored){}phase="paused";event("pause");publish();}
    void stop(){seek.clear();intent.stop();release();lastPosition=0;atEnd=false;phase="stopped";event("stop");publish();}
    void replay(){if(!complete())return;seek.clear();intent.stop();intent.arm();release();failed=false;atEnd=false;lastPosition=0;error="";phase="preparing";event("replay");maybeOpen();}
    void volume(int percent){options.volumePercent=Math.max(0,Math.min(100,percent));if(player!=null)try{player.setVolume(options.volumePercent/100f,options.volumePercent/100f);}catch(IllegalStateException ignored){}Log.i("VoxoraDictation","player_volume="+options.volumePercent);}
    void cachedDuration(int milliseconds){if(milliseconds>0)duration=milliseconds;publish();}
    void seek(int milliseconds){
        if(closed||!seek.request(milliseconds,duration,complete()))return;
        release();lastPosition=seek.target();atEnd=false;failed=false;error="";phase="seeking";event("seek");maybeOpen();publish();
    }
    void speed(float selected){
        if(selected!=.75f&&selected!=1f&&selected!=1.25f&&selected!=1.5f)return;
        if(!complete())return;speed=selected;
        if(player!=null&&prepared){boolean playing=playing();try{player.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1));if(!playing)player.pause();}catch(RuntimeException rejected){error="speed_unavailable";}}
        event("speed");publish();
    }
    void downloadFailed(){failed=true;intent.pause();release();phase="error";error="audio_download_failed";event("error");publish();}
    private boolean complete(){return progress!=null&&progress.get().complete();}
    private void maybeOpen(){
        if(closed||failed||file==null||progress==null||player!=null||(!intent.mayOpen(complete())&&seek.target()<0))return;
        if(!file.isFile()||(!complete()&&progress.get().bytes()<16384))return;
        MediaPlayer candidate=new MediaPlayer();int current=++generation;progressive=!complete();prepared=false;firstProgress=false;
        try {
            candidate.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
            route=chooseRoute();if(route==null)throw new IOException("route_unavailable");
            candidate.setVolume(options.volumePercent/100f,options.volumePercent/100f);
            if(progressive){source=new GrowingAudioSource(file,progress);candidate.setDataSource(source);}else candidate.setDataSource(file.getAbsolutePath());
            // Native MediaPlayer creates its output in setDataSource; routing before it returns NO_INIT.
            if(!candidate.setPreferredDevice(route))throw new IOException("route_unavailable");
            player=candidate;phase="preparing";
            candidate.setOnPreparedListener(mp->{if(current!=generation||mp!=player)return;prepared=true;int known=mp.getDuration();if(known>0)duration=known;event("prepared");if(seek.target()>=0){phase="seeking";mp.seekTo((long)seek.target(),MediaPlayer.SEEK_CLOSEST);}else if(intent.mayAutoStart())start();publish();});
            candidate.setOnSeekCompleteListener(mp->{if(current!=generation||mp!=player)return;seek.clear();lastPosition=safePosition();event("seek_complete");if(intent.mayAutoStart())start();else{if(mp.isPlaying())mp.pause();phase="paused";}publish();});
            candidate.setOnCompletionListener(mp->{if(current!=generation||mp!=player)return;lastPosition=safePosition();atEnd=true;intent.pause();phase="finished";event("completion");publish();});
            candidate.setOnErrorListener((mp,what,extra)->{if(current!=generation||mp!=player)return true;failed=true;intent.pause();phase="error";error="audio_decoder";Log.i("VoxoraDictation","player_error what="+what+" extra="+extra);release();publish();return true;});
            candidate.prepareAsync();
        }catch(Exception failure){candidate.release();player=null;failed=true;intent.pause();phase="error";error="route_unavailable".equals(failure.getMessage())?"route_unavailable":"audio_decoder";release();publish();}
    }
    private AudioDeviceInfo chooseRoute(){
        for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            if(matchesRoute(options.route,device.getId(),device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))return device;
        }return null;
    }
    static boolean matchesRoute(String selected,int id,boolean speaker){return "speaker".equals(selected)?speaker:("device_"+id).equals(selected);}
    private void start(){
        if(player==null||!prepared||!intent.mayAutoStart()||seek.target()>=0)return;
        if(focus==null)focus=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()).setOnAudioFocusChangeListener(change->{if(change<0)pause();},main).build();
        if(audio.requestAudioFocus(focus)!=AudioManager.AUDIOFOCUS_REQUEST_GRANTED){intent.pause();phase="paused";error="audio_focus";publish();return;}
        try {player.start();if(complete())player.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1));phase="playing";event("start");}catch(RuntimeException failure){failed=true;intent.pause();phase="error";error="audio_decoder";release();publish();}
    }
    private boolean playing(){try{return player!=null&&prepared&&player.isPlaying();}catch(IllegalStateException invalid){return false;}}
    private int safePosition(){if(seek.target()>=0)return seek.target();try{return player!=null&&prepared?Math.max(0,player.getCurrentPosition()):lastPosition;}catch(IllegalStateException invalid){return lastPosition;}}
    Snapshot snapshot(){return new Snapshot(phase,safePosition(),duration,speed,complete(),error);}
    /** Observed media route, never the preferred or communication device. */
    String outputLabel(){
        try{AudioDeviceInfo device=player==null?null:player.getRoutedDevice();return device==null?"":device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER?"Speaker":device.getProductName().toString();}
        catch(IllegalStateException unavailable){return "";}
    }
    private void publish(){if(closed)return;lastPosition=safePosition();if(!firstProgress&&playing()&&lastPosition>0){firstProgress=true;event("first_progress");}changed.accept(snapshot());}
    private void event(String event){Log.i("VoxoraDictation","player="+event+" elapsed_ms="+SystemClock.elapsedRealtime()+" position_ms="+safePosition()+" complete="+complete()+" bytes="+(progress==null?0:progress.get().bytes())+" speed="+speed);}
    private void release(){generation++;prepared=false;if(source!=null){try{source.close();}catch(IOException ignored){}source=null;}if(player!=null){try{player.release();}catch(RuntimeException ignored){}player=null;}if(focus!=null){audio.abandonAudioFocusRequest(focus);focus=null;}}
    void close(){closed=true;intent.pause();main.removeCallbacks(poll);audio.unregisterAudioDeviceCallback(devices);release();}
}
