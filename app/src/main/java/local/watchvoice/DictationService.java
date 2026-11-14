package local.watchvoice;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static local.watchvoice.DictationState.*;

/** Owns one dictation turn; Activity recreation never restarts capture, send or playback. */
public final class DictationService extends Service {
    static final String ACTION="local.watchvoice.DICTATION";
    static volatile boolean running;
    static volatile long recordingStartedAt;
    static volatile String outputLabel="";
    static volatile Snapshot snapshot=new DictationState().snapshot();
    static volatile ReadAloudPlayer.Snapshot playback=new ReadAloudPlayer.Snapshot("idle",0,0,1,false,"");
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"Voxora-dictation-io"));
    private DictationState state;
    private final AtomicReference<AudioProgress> progress=new AtomicReference<>(new AudioProgress(0,false,0,0));
    private DictationTurnStore cache;
    private DictationRecorder recorder;
    private Recording recording;
    private volatile DictationClient client;
    private ReadAloudPlayer player;
    private VoiceOptions options;
    private PowerManager.WakeLock wake;
    private boolean closing,foreground,networkActive;
    private String notified="";
    @Override public IBinder onBind(Intent intent){return null;}
    static void inspectRecovery(Context context){
        if(running||snapshot.phase()==Phase.UNCERTAIN)return;
        DictationTurnStore cache=new DictationTurnStore(new File(context.getNoBackupFilesDir(),"dictation"));
        DictationState next=new DictationState(snapshot.generation());
        try{SendRecord record=cache.read();if(record!=null&&!"verified".equals(record.phase()))next.restoreUncertain(record);else cache.clearResolvedClaim();cache.clearCache();}
        catch(IOException error){next.fail(next.snapshot().generation(),"private_storage",true);}snapshot=next.snapshot();
    }
    @Override public void onCreate(){
        super.onCreate();state=new DictationState(snapshot.generation()-(snapshot.phase()==Phase.UNCERTAIN?1:0));cache=new DictationTurnStore(new File(getNoBackupFilesDir(),"dictation"));options=new WatchStore(this).options();
        try{SendRecord record=cache.read();if(record!=null){if("verified".equals(record.phase()))cache.clearResolvedClaim();else state.restoreUncertain(record);}cache.clearCache();}catch(IOException error){state.fail(state.snapshot().generation(),"private_storage",true);}
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("dictation","Dettatura e lettura",NotificationManager.IMPORTANCE_LOW));
        wake=getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"Voxora:dictation");publish();
    }
    static boolean accepts(String command,long generation,Snapshot value){
        if(value==null||command==null||generation!=value.generation())return false;
        return switch(command){
            case "RECORD" -> value.phase()==Phase.READY || value.phase()==Phase.AUDIO || (value.phase()==Phase.ERROR&&value.assistantId()!=null);
            case "FINISH" -> value.phase()==Phase.RECORDING||value.phase()==Phase.CAPTURED;
            case "SEND","RETAKE" -> value.phase()==Phase.DRAFT;
            case "CANCEL" -> value.phase()==Phase.RECORDING||value.phase()==Phase.CAPTURED||value.phase()==Phase.TRANSCRIBING||value.phase()==Phase.DRAFT||(value.phase()==Phase.ERROR&&value.userId()==null);
            case "VERIFY","ACKNOWLEDGE" -> value.phase()==Phase.UNCERTAIN;
            case "RETRY_TRANSCRIPTION" -> value.phase()==Phase.ERROR&&value.userId()==null;
            case "RETRY_AUDIO","PLAY","PAUSE","STOP_AUDIO","REPLAY","SPEED","SEEK" -> value.assistantId()!=null;
            case "END","VOLUME" -> true;
            default -> false;
        };
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        String command=intent.getStringExtra("command");long generation=intent.getLongExtra("generation",-1);
        if(VoiceService.running){stopSelf();return START_NOT_STICKY;}
        running=true;
        if(!accepts(command,generation,state.snapshot())){publish();if(!foreground)stopSelf();return START_NOT_STICKY;}
        if("END".equals(command)){end();return START_NOT_STICKY;}
        try {
            if(!"VOLUME".equals(command))foreground(false);
            switch(command){
                case "RECORD" -> begin(new Destination(intent.getStringExtra("conversationId"),intent.getStringExtra("chatTitle")));
                case "FINISH" -> finish(generation);
                case "SEND" -> send(generation);
                case "RETAKE" -> {Destination destination=state.snapshot().destination();cancelTurn(generation,false);begin(destination);}
                case "CANCEL" -> cancelTurn(generation,true);
                case "VERIFY" -> verify(generation);
                case "ACKNOWLEDGE" -> {cache.acknowledge();end();}
                case "RETRY_TRANSCRIPTION" -> {if(recording!=null){long next=state.retryTranscription(generation);transcribe(next,recording);}else state.fail(generation,"recording_missing",false);}
                case "RETRY_AUDIO" -> {long next=state.retryAudio(generation);stopResources();download(next);}
                case "VOLUME" -> {options.volumePercent=Math.max(0,Math.min(100,intent.getIntExtra("volume",options.volumePercent)));if(player!=null)player.volume(options.volumePercent);}
                case "SEEK" -> {if(player!=null)player.seek(intent.getIntExtra("positionMs",0));}
                case "PLAY" -> {if(player!=null)player.play();}
                case "PAUSE" -> {if(player!=null)player.pause();}
                case "STOP_AUDIO" -> {if(player!=null)player.stop();}
                case "REPLAY" -> {if(player!=null)player.replay();}
                case "SPEED" -> {float speed=intent.getFloatExtra("speed",1);if(player!=null&&progress.get().complete()&&WatchStore.validSpeed(speed)){player.speed(speed);new WatchStore(this).saveReadAloudSpeed(speed);}}
            }
        }catch(Exception error){stopResources();state.commandFailed(code(error));foreground(false);publish();}
        publish();return START_NOT_STICKY;
    }
    private void begin(Destination destination)throws Exception{
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED)throw new IOException("microphone_permission");
        if(cache.read()!=null)cache.clearResolvedClaim();
        stopResources();cache.clearCache();options=new WatchStore(this).options();long generation=state.begin(destination);foreground(true);
        recorder=new DictationRecorder(cache.file("recording.wav"),(g,result)->main.post(()->{if(current(g)){recording=result;state.captured(g);foreground(false);publish();}}),(g,code)->main.post(()->{if(current(g)){state.fail(g,code,false);foreground(false);publish();}}));
        recordingStartedAt=SystemClock.elapsedRealtime();recorder.start(generation);publish();
    }
    private void finish(long generation){
        if(!state.finish(generation))return;DictationRecorder active=recorder;
        networkActive=true;publish();
        io.execute(()->{try{Recording result=active.finish();main.post(()->{if(current(generation)){recording=result;recorder=null;foreground(false);transcribe(generation,result);}});}catch(Exception error){main.post(()->{if(current(generation)){networkActive=false;foreground(false);fail(generation,error,false);}});}});
    }
    private void transcribe(long generation,Recording captured){
        networkActive=true;publish();
        Destination destination=state.snapshot().destination();String language=options.language;
        io.execute(()->{try{check(generation);DictationClient request=newClient();String text=request.transcribe(captured,language,destination,()->check(generation));
            main.post(()->{if(current(generation)){networkActive=false;if(state.draft(generation,text)){captured.file().delete();recording=null;}publish();}});
        }catch(Exception error){main.post(()->{if(current(generation)){networkActive=false;fail(generation,error,false);}});}});
    }
    private void send(long generation){
        if(!state.claimSend(generation))return;String user=UUID.randomUUID().toString();Snapshot value=state.snapshot();state.sentIdentity(generation,user,value.conversationId());networkActive=true;publish();
        io.execute(()->{try{check(generation);VerifiedReply reply=newClient().send(value.input(),value.destination(),user,()->check(generation));main.post(()->acceptReply(generation,reply));}
            catch(Exception error){main.post(()->{if(current(generation)){networkActive=false;boolean uncertain=error instanceof DictationClient.Failure&&((DictationClient.Failure)error).sendPossible;
                if(uncertain)fail(generation,error,true);else {state.preSendFailure(generation,code(error));publish();}}});}});
    }
    private void verify(long generation){networkActive=true;publish();io.execute(()->{try{VerifiedReply reply=newClient().verify(cache.read(),()->check(generation));main.post(()->acceptReply(generation,reply));}catch(Exception error){main.post(()->{if(current(generation)){networkActive=false;fail(generation,error,true);}});}});}
    private void acceptReply(long generation,VerifiedReply reply){
        if(!current(generation))return;networkActive=false;
        try{if(!state.reply(generation,reply))return;String title=state.snapshot().destination()==null?"Conversazione dettata":state.snapshot().destination().title();
            WatchStore store=new WatchStore(this);ConversationData.Chat confirmed=new ConversationData.Chat(reply.conversationId(),title,"");store.saveLastChat(confirmed);store.savePendingChat(confirmed);download(generation);
        }catch(Exception error){fail(generation,error,false);}
    }
    private void download(long generation){
        Snapshot value=state.snapshot();if(value.assistantId()==null)return;
        if(player!=null)player.close();progress.set(new AudioProgress(0,false,0,0));networkActive=true;
        player=new ReadAloudPlayer(this,options,new WatchStore(this).readAloudSpeed(),changed->{if(current(generation)){playback=changed;if("playing".equals(changed.phase())||"finished".equals(changed.phase()))state.audioReady(generation);publish();}});
        player.load(cache.file("audio.aac"),progress::get,true);publish();
        VerifiedReply reply=new VerifiedReply(value.conversationId(),value.userId(),value.assistantId(),value.output());
        String voice=options.voice;
        io.execute(()->{try{check(generation);newClient().download(reply,voice,cache.file("audio.aac"),()->check(generation),p->{if(current(generation))progress.set(p);});int duration=audioDuration(cache.file("audio.aac"));main.post(()->{if(current(generation)){networkActive=false;if(player!=null)player.cachedDuration(duration);publish();}});}
            catch(Exception error){main.post(()->{if(current(generation)){networkActive=false;if(player!=null)player.downloadFailed();fail(generation,error,false);}});}});
    }
    private static int audioDuration(File file){
        android.media.MediaExtractor extractor=new android.media.MediaExtractor();
        try{extractor.setDataSource(file.getAbsolutePath());for(int i=0;i<extractor.getTrackCount();i++){android.media.MediaFormat format=extractor.getTrackFormat(i);if(format.containsKey(android.media.MediaFormat.KEY_DURATION)){long ms=format.getLong(android.media.MediaFormat.KEY_DURATION)/1000;return (int)Math.min(Integer.MAX_VALUE,Math.max(0,ms));}}}
        catch(Exception ignored){Log.i("VoxoraDictation","cached_duration_unavailable=true");}finally{extractor.release();}return 0;
    }
    private DictationClient newClient(){DictationClient result=new DictationClient(new CredentialStore(this),cache);client=result;return result;}
    private boolean current(long g){return !closing&&state.snapshot().generation()==g;}
    private void check(long g)throws IOException{if(!current(g))throw new IOException("cancelled");}
    private void cancelTurn(long generation,boolean finish){state.discard(generation);stopResources();try{cache.clearCache();}catch(IOException ignored){}recording=null;if(finish)end();}
    private void stopResources(){DictationClient active=client;if(active!=null)active.cancel();client=null;if(recorder!=null){recorder.cancel();recorder=null;}if(player!=null){player.close();player=null;}playback=new ReadAloudPlayer.Snapshot("idle",0,0,1,false,"");networkActive=false;}
    private void fail(long generation,Exception error,boolean uncertain){state.fail(generation,code(error),uncertain);publish();}
    private static String code(Exception error){String code=error instanceof DictationClient.Failure?((DictationClient.Failure)error).code:error.getMessage();return code!=null&&code.matches("[a-zA-Z0-9_]{1,80}")?code:"operation_failed";}
    private void publish(){
        outputLabel=player==null?"":player.outputLabel();
        snapshot=state.snapshot();boolean busy=networkActive||snapshot.phase()==Phase.RECORDING||"playing".equals(playback.phase());
        if(wake!=null){if(busy&&!wake.isHeld())wake.acquire(60*60*1000L);else if(!busy&&wake.isHeld())wake.release();}
        String label=snapshot.phase().name()+":"+playback.phase()+":"+snapshot.error();
        if(!label.equals(notified)){notified=label;Log.i("VoxoraDictation","phase="+snapshot.phase()+" generation="+snapshot.generation()+" input_chars="+snapshot.input().length()+" output_chars="+snapshot.output().length()+" error="+snapshot.error());if(foreground)getSystemService(NotificationManager.class).notify(7,notification());}
    }
    private void foreground(boolean microphone){
        int type=ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;if(microphone)type|=ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        startForeground(7,notification(),type);foreground=true;
    }
    private Notification notification(){
        PendingIntent open=PendingIntent.getActivity(this,7,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,"dictation").setSmallIcon(R.drawable.ic_voxora_notification).setContentTitle("Voxora · Dettatura").setContentText(label(state.snapshot())).setContentIntent(open).setOngoing(true);
        String command=player!=null?"STOP_AUDIO":accepts("CANCEL",state.snapshot().generation(),state.snapshot())?"CANCEL":null;
        if(command!=null){Intent stop=new Intent(this,DictationService.class).setAction(ACTION).putExtra("command",command).putExtra("generation",state.snapshot().generation());builder.addAction(new Notification.Action.Builder(null,player!=null?"Stop audio":"Annulla",PendingIntent.getService(this,8,stop,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());}
        return builder.build();
    }
    static String label(Snapshot value){return switch(value.phase()){
        case READY->"Pronto";case RECORDING->"Registrazione in corso";case CAPTURED->"Registrazione pronta · scegli Trascrivi";case TRANSCRIBING->"Trascrizione in corso";case DRAFT->"Bozza pronta · Invia oppure Rifai";
        case SENDING->"Invio in corso";case WAITING->"Attendo risposta";case PREPARING_AUDIO->"Preparazione audio";case AUDIO->switch(playback.phase()){case "playing"->"Riproduzione";case "paused"->"In pausa";case "stopped"->"Audio fermato";case "finished"->"Lettura terminata";default->"Audio disponibile";};
        case UNCERTAIN->"Invio da verificare · nessun reinvio automatico";case ERROR->"Operazione non riuscita: "+value.error();};}
    private void end(){closing=true;stopResources();try{cache.clearCache();cache.clearResolvedClaim();}catch(IOException ignored){}if(wake!=null&&wake.isHeld())wake.release();if(foreground)stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;running=false;stopSelf();}
    @Override public void onDestroy(){closing=true;stopResources();io.shutdownNow();try{cache.clearCache();}catch(IOException ignored){}if(wake!=null&&wake.isHeld())wake.release();
        DictationState next=new DictationState(state.snapshot().generation()+1);try{SendRecord record=cache.read();if(record!=null&&!"verified".equals(record.phase()))next.restoreUncertain(record);}catch(IOException ignored){}
        snapshot=next.snapshot();running=false;super.onDestroy();}
}
