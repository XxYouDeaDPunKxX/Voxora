package local.watchvoice;

import android.Manifest;
import android.app.*;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** Three main views with replacing selection pages; VoiceService owns the call. */
public final class MainActivity extends Activity {
    private static final int SESSION = 0, CHATS = 1, SETTINGS = 2;
    private final UiNavigation navigation = new UiNavigation();
    private final ChatRefreshState chatRefresh = new ChatRefreshState();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ConversationList conversations = new ConversationList();
    private final CallGate listGate = new CallGate(), selectionGate = new CallGate();
    private WatchStore store;
    private VoiceOptions options;
    private ConversationData.Chat selected;
    private ConversationData.Detail preview;
    private ChatGptClient listRequest, detailRequest;
    private boolean destroyed, favoritesOnly, resumed;
    private ConversationData.Chat changeTarget;
    private String query = "", previewError = "";
    private ScrollView scroll;
    private ConversationList.Anchor chatAnchor;
    private boolean positionRestoring;
    private RetryTarget.Target permissionTarget;
    private LinearLayout root, rows;
    private RoundUi shell;
    private TextView status, title, identity, chatListStatus;
    private Button primary, mute, retry, chatRefreshButton, chatMoreButton;
    private TextView audioDetails, captionText;
    private boolean captionsExpanded;
    private boolean dictationPermission;
    private final DictationView.Panels dictationPanels=new DictationView.Panels();
    private long handledDictationReply=-1;
    private final Runnable liveVolume=()->dispatchLiveVolume(options.volumePercent);
    private void queueVolume(int value){options.volumePercent=value;if(!main.hasCallbacks(liveVolume))main.postDelayed(liveVolume,60);}
    private void commitVolume(int value){options.volumePercent=value;main.removeCallbacks(liveVolume);store.saveOptions(options);dispatchLiveVolume(value);}
    private void dispatchLiveVolume(int value){
        if(destroyed)return;
        if(VoiceService.running)startService(new Intent(this,VoiceService.class).setAction(VoiceService.VOLUME).putExtra("volume",value).putExtra("startedAt",VoiceService.startedAt));
        else if(DictationService.running)startService(new Intent(this,DictationService.class).setAction(DictationService.ACTION).putExtra("command","VOLUME").putExtra("volume",value).putExtra("generation",DictationService.snapshot.generation()));
    }
    private void volumeControl(){volumeRoute=text("Uscita effettiva: "+effectiveOutput(),11);root.addView(LiveVolumeControl.create(this,options.volumePercent,options.largeText,this::queueVolume,this::commitVolume),new LinearLayout.LayoutParams(-1,-2));}
    private void seekAudio(long generation,int position){if(DictationService.running)startService(new Intent(this,DictationService.class).setAction(DictationService.ACTION).putExtra("command","SEEK").putExtra("generation",generation).putExtra("positionMs",position));}
    // Shares the lifetime of VoiceService's in-memory captions, including Activity reopen.
    private static long hiddenSessionStartedAt = -1;
    private CaptionBuffer renderedCaptions;
    private List<ConversationData.Turn> renderedTurns = List.of();
    private String layoutKey="", inlineNote="", pendingMode;
    private boolean transitionPending, filterOpen, seeking, acknowledgePending, restorePending;
    private long transitionEpoch;
    private TextView liveTime, indicatorFacts, volumeRoute;
    private SeekBar seekBar;
    private android.animation.ObjectAnimator indicatorAnimation;
    private static String readerSource="",readerOwner="",readerHeading="";
    private ConversationData.Chat detailChat;
    private final Runnable update = new Runnable() {
        public void run() {
            if(destroyed)return;
            bindConfirmedReply();
            String key=presentationKey();
            if(!seeking&&!key.equals(layoutKey)){rememberPosition();render();}
            updateLiveData();
            if (options.keepScreenOn && (VoiceService.running||DictationService.running)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            main.postDelayed(this, 150);
        }
    };
    private String presentationKey(){
        DictationState.Snapshot d=DictationService.snapshot;ReadAloudPlayer.Snapshot p=DictationService.playback;
        return navigation.screen()+":"+store.mode()+":"+VoiceService.running+":"+VoiceService.startedAt+":"+VoiceService.status+":"+VoiceService.snapshot.muted+":"+VoiceService.snapshot.serverState+":"+VoiceService.confirmedId+":"+d.generation()+":"+d.phase()+":"+d.error()+":"+p.phase()+":"+p.complete()+":"+p.error()+":"+p.speed()+":"+(ChatGptClient.voiceRetrySeconds()>0);
    }
    private void bindConfirmedReply(){
        String voiceId=VoxoraPresentation.boundChat(selected==null?null:selected.id,VoiceService.requestedId,VoiceService.confirmedId,VoiceService.running);
        if(voiceId!=null&&(selected==null||!selected.id.equals(voiceId))){selected=new ConversationData.Chat(voiceId,VoiceService.chatTitle,"");store.savePendingChat(selected);}
        DictationState.Snapshot value=DictationService.snapshot;
        if(DictationView.shouldBindReply(value,handledDictationReply)){
            handledDictationReply=value.generation();
            if(selected==null||!selected.id.equals(value.conversationId())){selected=new ConversationData.Chat(value.conversationId(),value.destination()==null?"Conversazione dettata":value.destination().title(),"");store.savePendingChat(selected);}
        }
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); store = new WatchStore(this); options = store.options();
        List<ConversationData.Chat> cached = store.cachedChats();
        if (!cached.isEmpty()) conversations.merge(new ConversationData.Page(cached, cached.size() + 1, 0, 20));
        selected = store.hasPendingSelection() ? store.pendingChat() : store.lastChat();
        if(DictationService.snapshot.assistantId()!=null)handledDictationReply=DictationService.snapshot.generation();
        if (saved != null) {
            navigation.restore(saved.getIntArray("path"), saved.getIntArray("positions")); query = saved.getString("query", ""); favoritesOnly = saved.getBoolean("favorites");
            captionsExpanded = saved.getBoolean("captionsExpanded");
            pendingMode=saved.getString("pendingMode");acknowledgePending=saved.getBoolean("acknowledgePending");restorePending=saved.getBoolean("restorePending");
            if(saved.getString("detailId")!=null)detailChat=new ConversationData.Chat(saved.getString("detailId"),saved.getString("detailTitle",""),saved.getString("detailDate",""));
            if (saved.getString("chatAnchorId") != null) chatAnchor = new ConversationList.Anchor(saved.getString("chatAnchorId"), saved.getInt("chatAnchorOffset"));
            if (saved.getString("changeId") != null) changeTarget = new ConversationData.Chat(saved.getString("changeId"), saved.getString("changeTitle", ""), saved.getString("changeDate", ""));
            if (saved.getBoolean("newChat")) selected = null;
            else if (saved.getString("selectedId") != null) selected = new ConversationData.Chat(saved.getString("selectedId"), saved.getString("selectedTitle", ""), saved.getString("selectedDate", ""));
        } else applyEntry(getIntent());
        DictationService.inspectRecovery(this);render();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);
    }
    /** Wear entries select a screen only. Avvia owns permission and microphone start. */
    private void applyEntry(Intent intent) {
        changeTarget = null;
        if (intent.hasExtra(WearEntry.EXTRA)) {
            String destination = WearEntry.action(intent.getStringExtra(WearEntry.EXTRA), VoiceService.running||DictationService.running||dictationLocked(), store.lastChat() != null);
            if (destination.equals("new") || destination.equals("resume")) {
                chooseDestination(destination.equals("new") ? null : store.lastChat());
                store.savePendingChat(selected); preview = null; previewError = "";
            }
            navigation.open(destination.equals("chats") ? CHATS : SESSION);
        } else { applySelection(intent); navigation.open(SESSION); }
    }
    private void applySelection(Intent intent) {
        String id = intent.getStringExtra("conversationId");
        if (id == null || VoiceService.running || DictationService.running) return;
        try {
            ConversationData.Chat destination = new ConversationData.Chat(id, "Conversazione selezionata", "");
            for (ConversationData.Chat chat : conversations.visible("", Set.of(), false)) if (chat.id.equals(id)) { destination = chat; break; }
            chooseDestination(destination);
            store.savePendingChat(selected);
        } catch (IllegalArgumentException ignored) { Toast.makeText(this, "Chat non valida", Toast.LENGTH_SHORT).show(); }
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); transitionEpoch++;transitionPending=false;pendingMode=null;changeTarget=null;rememberPosition(); cancelDetail(); applyEntry(intent); render(); loadChats(true, true); }
    private void rememberPosition() {
        if (scroll == null || positionRestoring) return;
        navigation.remember(scroll.getScrollY());
        if (navigation.screen() == CHATS && rows != null && rows.isLaidOut()) {
            List<ConversationData.Chat> visible = conversations.visible(query, store.favorites(), favoritesOnly);
            int[][] bounds = rowBounds(visible.size());
            chatAnchor = ConversationList.anchor(visible, bounds[0], bounds[1], scroll.getScrollY());
        }
    }
    private int[][] rowBounds(int count) {
        int[][] bounds = new int[2][count];
        for (int i=0; i<count; i++) {
            View row = rows.getChildAt(i * 2);
            if (row != null) { bounds[0][i] = rows.getTop() + row.getTop(); bounds[1][i] = rows.getTop() + row.getBottom(); }
        }
        return bounds;
    }
    private void navigate(int destination) { rememberPosition(); navigation.open(destination); render(); }
    private void showPage(int page) { rememberPosition(); navigation.show(page); render(); }
    private void returnPage() { if(transitionPending)return;rememberPosition(); if (navigation.back()) render(); }
    private int proportional(float fraction) {
        return Math.round(getResources().getDisplayMetrics().widthPixels * fraction);
    }
    private void render() {
        if(store==null)return;
        if(indicatorAnimation!=null){indicatorAnimation.cancel();indicatorAnimation=null;}
        seekBar=null;liveTime=null;indicatorFacts=null;volumeRoute=null;
        setVolumeControlStream(dictationMode()?AudioManager.STREAM_MUSIC:AudioManager.STREAM_VOICE_CALL);
        rows=null;chatListStatus=null;chatRefreshButton=null;chatMoreButton=null;
        shell=new RoundUi(this,options.largeText);scroll=shell.scroll;root=shell.body;
        int page=navigation.screen();
        Button left=tool(page==SESSION?"chats":"back", "",page==SESSION?"Conversazioni":"Indietro",page==SESSION?()->navigate(CHATS):this::returnPage,false);
        String tag=activityTag();boolean active=!tag.isEmpty();
        Button centre=tool(activityIcon(),active?tag:"Opzioni",active?"Stato attività: "+tag:"Opzioni e impostazioni",()->showPage(UiNavigation.STATUS),false);
        if(active&&VoxoraPresentation.advancing(VoiceService.running,VoiceService.snapshot,DictationService.snapshot,DictationService.playback)&&android.animation.ValueAnimator.areAnimatorsEnabled()){
            indicatorAnimation=android.animation.ObjectAnimator.ofFloat(centre,"alpha",1f,.55f,1f);indicatorAnimation.setDuration(1600);indicatorAnimation.setRepeatCount(android.animation.ValueAnimator.INFINITE);indicatorAnimation.start();
        }
        Button right=page==SESSION?tool("redo",dictationMode()?"Voice":"Dettatura","Passa a "+(dictationMode()?"Voice":"Dettatura"),this::changeMode,false):null;
        centre.setEnabled(page!=UiNavigation.CONFIRM&&!transitionPending);
        shell.head(left,centre,right);
        if(page!=SESSION&&page!=UiNavigation.STATUS){TextView heading=text(pageTitle(page),14);heading.setTypeface(null,Typeface.BOLD);}
        switch(page){
            case SESSION->sessionView();case CHATS->{chatsView();chatActions();}case SETTINGS->settingsView();
            case UiNavigation.MENU->menuView();case UiNavigation.VOICE->voiceChoices();
            case UiNavigation.LANGUAGE->choiceRows(VoiceOptions.LANGUAGES,VoiceOptions.LANGUAGES,options.language,value->{options.language=value;saveOptions(false);returnPage();});
            case UiNavigation.ROUTE->routeChoices();case UiNavigation.CONFIRM->confirmChange();
            case UiNavigation.VOLUME->volumeControl();case UiNavigation.STATUS->statusView();
            case UiNavigation.FILTER->filtersView();case UiNavigation.DETAIL->detailView();
            case UiNavigation.READER->{text(readerHeading,14);text(readerOwner,11);if(readerSource.isEmpty())text("Testo non disponibile dopo la riapertura. Torna all’attività o aggiorna i messaggi della chat.",13);else MessageReader.populate(this,root,scroll,readerSource,options.largeText,this::returnPage);shell.actions(null,tool("close","Chiudi","Chiudi testo",this::returnPage,true),null);}
            case UiNavigation.DIAGNOSTICS->diagnosticsView();case UiNavigation.TITLE->text(currentTitle(),15);
        }
        if(root.getChildCount()>0&&root.getChildAt(0) instanceof TextView){View first=root.getChildAt(0);first.setPadding(dp(6),first.getPaddingTop(),dp(6),first.getPaddingBottom());}
        layoutKey=presentationKey();setContentView(shell);restorePosition(scroll,navigation.position(),page);updateLiveData();
    }
    private String pageTitle(int page){return switch(page){case CHATS->"Conversazioni";case SETTINGS->"Impostazioni";case UiNavigation.MENU->"Opzioni";case UiNavigation.VOICE->"Voce";case UiNavigation.LANGUAGE->"Lingua";case UiNavigation.ROUTE->"Uscita audio";case UiNavigation.CONFIRM->"Conferma";case UiNavigation.VOLUME->"Volume";case UiNavigation.FILTER->"Ricerca e preferite";case UiNavigation.DETAIL->"Dettaglio chat";case UiNavigation.READER->"Testo";case UiNavigation.DIAGNOSTICS->"Diagnostica";default->"Conversazione";};}
    private Button tool(String icon,String label,String description,Runnable action,boolean footer){return shell.tool(icon,label,description,action,footer);}
    private String currentTitle(){
        if(VoiceService.running)return VoiceService.chatTitle;
        DictationState.Snapshot d=DictationService.snapshot;
        if(dictationMode()&&d.phase()!=DictationState.Phase.READY&&d.destination()!=null)return d.destination().title();
        return selected==null?"Nuova chat":selected.title;
    }
    private boolean voiceFailureVisible(){return !VoiceService.running&&!dictationMode()&&sessionPresentationCurrent()&&VoiceService.diagnostics!=null&&!VoiceService.status.equals("Pronto")&&!VoiceService.status.equals("Terminata");}
    private String activityTag(){if(voiceFailureVisible())return "Errore";return VoxoraPresentation.tag(VoiceService.running,VoiceService.snapshot,DictationService.snapshot,DictationService.playback);}
    private String activityIcon(){
        if(voiceFailureVisible())return "warning";
        if(VoiceService.running)return VoiceService.snapshot.muted?"mute":VoiceService.snapshot.serverState.equals("thinking")?"wait":VoiceService.snapshot.serverState.equals("speaking")?"volume":"phone";
        return switch(DictationService.snapshot.phase()){case READY->"more";case RECORDING->"record";case DRAFT->"text";case SENDING->"send";case AUDIO->!DictationService.playback.error().isEmpty()?"warning":switch(DictationService.playback.phase()){case "playing"->"volume";case "finished"->"check";case "stopped"->"stop";default->"pause";};case UNCERTAIN,ERROR->"warning";default->"wait";};
    }
    private void restorePosition(ScrollView target, int position, int page) {
        positionRestoring = true;
        target.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            public void onGlobalLayout() {
            target.getViewTreeObserver().removeOnGlobalLayoutListener(this);
            if (scroll != target || navigation.screen() != page) return;
            int resolved = position;
            if (page == CHATS && chatAnchor != null && rows != null) {
                List<ConversationData.Chat> visible = conversations.visible(query, store.favorites(), favoritesOnly);
                resolved = chatAnchor.restore(visible, rowBounds(visible.size())[0], position);
            }
            target.scrollTo(0, resolved); positionRestoring = false;
            }
        });
    }
    private TextView label(String text, int size) {
        TextView label = new TextView(this); label.setText(text); label.setTextColor(RoundUi.INK); label.setTextSize(size * (options.largeText ? 1.18f : 1f));
        label.setGravity(Gravity.START); label.setPadding(0, dp(3), 0, dp(3));
        label.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_HIGH_QUALITY); return label;
    }
    private TextView text(String text, int size) { TextView label = label(text, size); root.addView(label, new LinearLayout.LayoutParams(-1, -2)); return label; }
    private Button button(String text,Runnable action){
        Button button=new Button(this);button.setText(text);button.setTextSize(options.largeText?14:12);button.setAllCaps(false);button.setTextColor(RoundUi.ACCENT);button.setGravity(Gravity.CENTER);button.setPadding(dp(3),dp(3),dp(3),dp(3));button.setMinWidth(0);button.setMinimumWidth(0);button.setMinHeight(dp(48));button.setMinimumHeight(dp(48));button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0xff294b35),null,null));button.setOnClickListener(v->action.run());return button;
    }
    private Button flatButton(String text, Runnable action) {
        Button button = button(text, action); button.setTextSize(options.largeText ? 14 : 12);
        button.setTypeface(null, Typeface.NORMAL); button.setPadding(0, 0, 0, 0);
        button.setSingleLine(true); button.setEllipsize(null);
        button.setMinWidth(dp(48)); button.setMinimumWidth(dp(48));
        button.setTextColor(new android.content.res.ColorStateList(
            new int[][] { new int[] {-android.R.attr.state_enabled}, new int[0] },
            new int[] { 0xff647168, RoundUi.ACCENT }));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0xff304b52), null, null));
        return button;
    }
    private Button action(String text, Runnable action) {
        Button button = button(text, action); root.addView(button, new LinearLayout.LayoutParams(-1, -2)); return button;
    }
    private void divider() {
        View divider = new View(this); divider.setBackgroundColor(0xff263129);
        root.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    }
    private void menuView(){row("Conversazioni","",()->navigate(CHATS));row("Nuova chat","",()->select(null));row("Riprendi ultima chat",store.lastChat()==null?"Nessuna chat confermata":"",()->{if(store.lastChat()!=null)select(store.lastChat());});row("Impostazioni","",()->navigate(SETTINGS));row("Diagnostica","",()->showPage(UiNavigation.DIAGNOSTICS));}
    private void sessionView(){
        boolean voice=VoiceService.running;DictationState.Snapshot d=DictationService.snapshot;ReadAloudPlayer.Snapshot p=DictationService.playback;
        if(!voice&&(!dictationMode()||d.phase()==DictationState.Phase.READY))text("Selezionata · "+(dictationMode()?"Dettatura":"Voice"),10).setTextColor(RoundUi.DIM);
        title=text(currentTitle(),14);title.setTypeface(null,Typeface.BOLD);title.setTextColor(RoundUi.ACCENT);title.setOnClickListener(v->showPage(UiNavigation.TITLE));
        if(!inlineNote.isEmpty())text(inlineNote,11);
        if(voice||!dictationMode()){voiceView();return;}
        if(d.assistantId()==null)text(VoxoraPresentation.dictationStatus(d,p),14).setTextColor(RoundUi.ACCENT);
        if(!d.error().isEmpty()){text(dictationFailure(d.error()),11);row("Dettagli errore","",()->showPage(UiNavigation.DIAGNOSTICS));}
        else if(!p.error().isEmpty()&&d.assistantId()!=null){text("Risposta ricevuta. La lettura è stata interrotta; il testo è conservato.",11);row("Riprova lettura","Richiede solo l’audio di questa risposta",()->dictationUi("RETRY_AUDIO",1,d.generation()));row("Dettagli errore","",()->showPage(UiNavigation.DIAGNOSTICS));}
        switch(d.phase()){
            case READY->text("Registra, termina e invia la bozza.\nLimiti locali: 10 min / 24 MiB.",11);
            case RECORDING,CAPTURED->{liveTime=text("",12);text("Nessun messaggio inviato.\nLimiti locali: 10 min / 24 MiB.",11);}
            case TRANSCRIBING->text("Nessun messaggio inviato. Puoi annullare la trascrizione.",11);
            case DRAFT->{previewButton("Trascrizione · "+d.input().length()+" caratteri",d.input(),()->readTurn(false));text("Registra di nuovo sostituisce la bozza.",10);}
            case UNCERTAIN->{text("Non reinviare. Controlla la conversazione in ChatGPT se Voxora non può confermare l’esito.",12);row("Chiudi dopo controllo","Non annulla il messaggio su ChatGPT",this::acknowledge);}
            case SENDING,WAITING->text("Attendi l’esito. Nessun nuovo invio sarà avviato da questa schermata.",11);
            default->{}
        }
        if(d.assistantId()!=null){
            FrameLayout seekArea=new FrameLayout(this);root.addView(seekArea,new LinearLayout.LayoutParams(-1,dp(48)));
            seekBar=new SeekBar(this);seekBar.setContentDescription("Posizione audio");seekBar.setPadding(dp(8),dp(18),dp(8),0);seekArea.addView(seekBar,new FrameLayout.LayoutParams(-1,dp(48)));seekBar.setEnabled(p.complete()&&p.durationMs()>0);
            liveTime=label("",10);liveTime.setPadding(0,0,0,0);liveTime.setTextColor(RoundUi.DIM);liveTime.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);seekArea.addView(liveTime,new FrameLayout.LayoutParams(-1,-2));
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){long generation;
                public void onStartTrackingTouch(SeekBar bar){seeking=true;generation=DictationService.snapshot.generation();}
                public void onStopTrackingTouch(SeekBar bar){seeking=false;seekAudio(generation,bar.getProgress());}
                public void onProgressChanged(SeekBar bar,int value,boolean user){if(user){setTextIfChanged(liveTime,clock(value)+" / "+clock(bar.getMax()));if(!seeking)seekAudio(d.generation(),value);}}
            });
            if(!p.complete())text("Spostamento e velocità dopo il download.",10);
            LinearLayout pair=new LinearLayout(this);root.addView(pair,new LinearLayout.LayoutParams(-1,-2));
            previewCell(pair,"Tu",d.input(),()->readTurn(false));previewCell(pair,"ChatGPT",d.output(),()->readTurn(true));
            row("Registra un altro messaggio","Sostituisce audio e testi locali",()->dictationUi("RECORD",1,d.generation()));
        }
        Button centre=tool(d.phase()==DictationState.Phase.DRAFT?"send":d.assistantId()!=null?(p.phase().equals("playing")?"pause":"play"):d.phase()==DictationState.Phase.RECORDING?"stop":"record",VoxoraPresentation.primaryLabel(d,p),VoxoraPresentation.primaryLabel(d,p),()->dictationUi(VoxoraPresentation.primary(d,p),1,d.generation()),true);
        centre.setEnabled(!VoxoraPresentation.primary(d,p).isEmpty());
        Button left=null,right=null;
        if(d.phase()==DictationState.Phase.READY){left=tool("add","Nuova","Nuova chat",()->select(null),true);right=tool("gear","Opzioni","Impostazioni",()->navigate(SETTINGS),true);}
        else if(d.phase()==DictationState.Phase.DRAFT){left=tool("redo","Registra","Registra di nuovo e sostituisci la bozza",()->dictationUi("RETAKE",1,d.generation()),true);right=tool("close","Scarta","Scarta bozza",()->dictationUi("CANCEL",1,d.generation()),true);}
        else if(d.assistantId()!=null){left=tool(d.phase()==DictationState.Phase.ERROR?"refresh":p.phase().equals("playing")?"pause":"play",VoxoraPresentation.primaryLabel(d,p),d.phase()==DictationState.Phase.ERROR?"Riprova la lettura della stessa risposta":"Pausa o riprendi lo stesso audio",()->dictationUi(d.phase()==DictationState.Phase.ERROR?"RETRY_AUDIO":p.phase().equals("playing")?"PAUSE":"PLAY",1,d.generation()),true);centre=tool("speed",speedLabel(p.speed()),"Velocità lettura",this::cycleSpeed,true);centre.setEnabled(p.complete());right=tool("volume","Volume","Volume lettura",()->showPage(UiNavigation.VOLUME),true);}
        else if(DictationService.accepts("CANCEL",d.generation(),d)){left=tool("close","Annulla","Annulla tentativo locale",()->dictationUi("CANCEL",1,d.generation()),true);}
        shell.actions(left,centre,right);
    }
    private void voiceView(){
        boolean running=VoiceService.running,connected=running&&VoiceService.snapshot.phase.equals("connected");long wait=running?0:ChatGptClient.voiceRetrySeconds();
        text(running?VoxoraPresentation.voiceStatus(VoiceService.snapshot):wait>0?"Limite temporaneo del servizio":sessionPresentationCurrent()?VoiceService.status:"Pronto",14).setTextColor(RoundUi.ACCENT);
        if(running){text(VoiceService.confirmedId==null?"Conversazione non ancora confermata":"Conversazione confermata",10);liveTime=text("",11);previewButton("Scambi Voice",captionSource(),this::readCaptions);}
        else{if(wait>0){liveTime=text("",11);row("Dettagli errore","",()->showPage(UiNavigation.DIAGNOSTICS));}else text("Avvia per parlare nella chat selezionata.",11);if(sessionPresentationCurrent()&&VoiceService.diagnostics!=null){text(VoiceState.failure(VoiceService.diagnostics.code),11);row("Dettagli errore","",()->showPage(UiNavigation.DIAGNOSTICS));}}
        Button mic=running?tool(VoiceService.snapshot.muted?"mic":"mute",VoiceService.snapshot.muted?"Riattiva":"Disattiva",VoiceService.snapshot.muted?"Riattiva microfono":"Disattiva microfono",()->voiceCommand(VoiceService.MUTE),true):tool("add","Nuova","Nuova chat",()->select(null),true);if(running)mic.setEnabled(connected);
        Button go=tool(running?"stop":"phone",running?connected?"Termina":"Annulla":wait>0?"Attendi":"Avvia",running?"Termina chiamata":"Avvia chiamata",()->{if(running)voiceCommand(VoiceService.STOP);else startVoice();},true);go.setEnabled(running||wait==0);
        shell.actions(mic,go,tool(running?"volume":"gear",running?"Volume":"Opzioni",running?"Volume chiamata":"Impostazioni",()->{if(running)showPage(UiNavigation.VOLUME);else navigate(SETTINGS);},true));
    }
    private void setTextIfChanged(TextView target,String value){if(target!=null&&!target.getText().toString().equals(value))target.setText(value);}
    private static String clock(long milliseconds){long seconds=Math.max(0,milliseconds/1000);return String.format(Locale.ITALY,"%d:%02d",seconds/60,seconds%60);}
    private static String speedLabel(float speed){return String.valueOf(speed).replace(".0","").replace('.',',')+"×";}
    private void cycleSpeed(){ReadAloudPlayer.Snapshot p=DictationService.playback;if(!p.complete())return;float[] speeds={.75f,1,1.25f,1.5f};int index=0;for(int i=0;i<speeds.length;i++)if(speeds[i]==p.speed())index=i;dictationCommand("SPEED",speeds[(index+1)%speeds.length]);}
    private void updateLiveData(){
        if(liveTime!=null){if(VoiceService.running)setTextIfChanged(liveTime,(VoiceService.snapshot.muted?"Microfono disattivato":"Microfono attivo")+" · "+clock(SystemClock.elapsedRealtime()-VoiceService.startedAt));else if(!dictationMode())setTextIfChanged(liveTime,"Riprova tra "+ChatGptClient.voiceRetrySeconds()+" s");else if(DictationService.snapshot.phase()==DictationState.Phase.RECORDING)setTextIfChanged(liveTime,clock(SystemClock.elapsedRealtime()-DictationService.recordingStartedAt));else {ReadAloudPlayer.Snapshot p=DictationService.playback;if(!seeking)setTextIfChanged(liveTime,clock(p.positionMs())+" / "+(p.durationMs()>0?clock(p.durationMs()):"—")+"\n"+VoxoraPresentation.dictationStatus(DictationService.snapshot,p));}}
        if(seekBar!=null&&!seeking){ReadAloudPlayer.Snapshot p=DictationService.playback;seekBar.setMax(Math.max(1,p.durationMs()));seekBar.setProgress(p.positionMs());}
        if(indicatorFacts!=null)setTextIfChanged(indicatorFacts,activityFacts());
        if(volumeRoute!=null)setTextIfChanged(volumeRoute,"Uscita effettiva: "+effectiveOutput());
    }
    private void previewButton(String name,String content,Runnable open){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setMinimumHeight(dp(48));box.setPadding(0,dp(4),0,dp(4));
        TextView heading=label(name+" ›",11);heading.setTextColor(RoundUi.ACCENT);box.addView(heading);
        TextView snippet=label(content.isEmpty()?"Nessun testo disponibile":content.replace('\n',' '),11);snippet.setMaxLines(1);snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(snippet);
        box.setClickable(true);box.setFocusable(true);box.setContentDescription("Mostra "+name);box.setOnClickListener(v->open.run());root.addView(box,new LinearLayout.LayoutParams(-1,-2));
    }
    private void previewCell(LinearLayout pair,String name,String content,Runnable open){LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);TextView nameView=label(name+" ›",11);nameView.setTextColor(RoundUi.ACCENT);nameView.setPadding(0,0,0,0);box.addView(nameView);TextView snippet=label(content.isEmpty()?"Non disponibile":content.replace('\n',' '),10);snippet.setPadding(0,0,0,0);snippet.setSingleLine(true);snippet.setEllipsize(android.text.TextUtils.TruncateAt.END);box.addView(snippet);box.setMinimumHeight(dp(48));box.setClickable(true);box.setFocusable(true);box.setContentDescription("Mostra "+name);box.setOnClickListener(v->open.run());pair.addView(box,new LinearLayout.LayoutParams(0,-2,1));}
    private void chatActions(){shell.actions(tool("add","Nuova","Nuova chat",()->select(null),true),chatRefreshButton=tool("refresh",chatRefresh.loading()?"Attendi":"Aggiorna","Aggiorna elenco",()->loadChats(true),true),tool("search","Ricerca","Ricerca e preferite",()->showPage(UiNavigation.FILTER),true));chatRefreshButton.setEnabled(!chatRefresh.loading());}
    private void filtersView(){
        EditText filter=new EditText(this);filter.setSingleLine(true);filter.setTextColor(RoundUi.INK);filter.setHintTextColor(RoundUi.DIM);filter.setTextSize(options.largeText?14:12);filter.setHint("Cerca nelle chat caricate");filter.setContentDescription("Cerca nelle chat caricate");filter.setText(query);filter.setMinHeight(dp(48));root.addView(filter);
        filter.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void afterTextChanged(Editable e){}public void onTextChanged(CharSequence s,int a,int b,int c){query=s.toString();chatAnchor=null;}});
        Switch favorites=new Switch(this);favorites.setText(getString(R.string.favorites_only));favorites.setTextColor(RoundUi.INK);favorites.setTextSize(options.largeText?14:12);favorites.setMinHeight(dp(48));favorites.setChecked(favoritesOnly);root.addView(favorites,new LinearLayout.LayoutParams(-1,-2));favorites.setOnCheckedChangeListener((view,checked)->{favoritesOnly=checked;chatAnchor=null;});action("Rimuovi filtri",()->{query="";favoritesOnly=false;chatAnchor=null;returnPage();});action("Mostra risultati",this::returnPage);
    }
    private void chatsView(){
        text("Selezionata: "+(selected==null?"Nuova chat":selected.title),11).setTextColor(RoundUi.ACCENT);
        if(!query.isEmpty()||favoritesOnly)row("Filtri attivi",(favoritesOnly?"Solo preferite · ":"")+query,()->showPage(UiNavigation.FILTER));
        String message=chatRefresh.label();chatListStatus=text(message,11);chatListStatus.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);
        rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);root.addView(rows);renderRows();
        chatMoreButton=action("Carica altre chat",()->loadChats(false));chatMoreButton.setEnabled(!chatRefresh.loading()&&conversations.hasMore());
        row("Riprendi ultima chat",store.lastChat()==null?"Nessuna chat confermata":"",()->{if(store.lastChat()!=null)select(store.lastChat());});
    }
    private void updateChatFeedback(){if(navigation.screen()!=CHATS||chatRefreshButton==null||chatListStatus==null||chatMoreButton==null)return;setTextIfChanged(chatRefreshButton,chatRefresh.loading()?"Attendi":"Aggiorna");chatRefreshButton.setEnabled(!chatRefresh.loading());String message=chatRefresh.label();setTextIfChanged(chatListStatus,message);chatListStatus.setVisibility(message.isEmpty()?View.GONE:View.VISIBLE);chatMoreButton.setEnabled(!chatRefresh.loading()&&conversations.hasMore());}
    private void renderRows(){renderRows(scroll.getScrollY());}
    private void renderRows(int position){
        if(rows==null||navigation.screen()!=CHATS)return;rows.removeAllViews();LinearLayout previous=root;root=rows;Set<String> favorites=store.favorites();List<ConversationData.Chat> visible=conversations.visible(query,favorites,favoritesOnly);
        if(visible.isEmpty())text(chatRefresh.loading()?"Caricamento conversazioni…":!query.isEmpty()||favoritesOnly?"Nessuna conversazione trovata nelle chat caricate.":chatRefresh.label().isEmpty()?"Nessuna conversazione disponibile nell’elenco.":"Elenco non disponibile. Controlla la connessione.",12);
        for(ConversationData.Chat chat:visible){LinearLayout line=new LinearLayout(this);line.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout details=new LinearLayout(this);details.setOrientation(LinearLayout.VERTICAL);details.setMinimumHeight(dp(48));TextView name=label(chat.title.isEmpty()?"Senza titolo":chat.title,13);name.setTextColor(selected!=null&&selected.id.equals(chat.id)?RoundUi.ACCENT:RoundUi.INK);details.addView(name);if(!chat.updatedAt.isEmpty())details.addView(label(date(chat.updatedAt)+(favorites.contains(chat.id)?" · ★":""),10));details.setClickable(true);details.setFocusable(true);details.setContentDescription("Seleziona "+chat.title);details.setOnClickListener(v->select(chat));line.addView(details,new LinearLayout.LayoutParams(0,-2,1));
            Button detail=tool("info","","Dettagli di "+chat.title,()->openDetail(chat),false);line.addView(detail,new LinearLayout.LayoutParams(dp(40),dp(48)));root.addView(line);divider();}
        root=previous;restorePosition(scroll,position,CHATS);
    }
    private String date(String source) {
        try { double seconds = Double.parseDouble(source); return new java.text.SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(new Date((long)(seconds * 1000))); }
        catch (Exception ignored) { return source.length() >= 10 ? source.substring(0,10) : source; }
    }
    private void select(ConversationData.Chat chat){
        if(VoxoraPresentation.sameExistingChat(chat==null?null:chat.id,selected==null?null:selected.id)){navigate(SESSION);return;}
        requestChange(null,chat);
    }
    private void requestChange(String mode,ConversationData.Chat target){
        if(transitionPending)return;
        VoxoraPresentation.Change policy=VoxoraPresentation.change(VoiceService.running,DictationService.snapshot);
        if(policy==VoxoraPresentation.Change.BLOCK){inlineNote=DictationService.snapshot.phase()==DictationState.Phase.UNCERTAIN?"Controlla l’invio prima di cambiare attività.":"Attendi l’esito prima di cambiare attività.";navigate(SESSION);return;}
        pendingMode=mode;changeTarget=target;
        if(policy==VoxoraPresentation.Change.CONFIRM){showPage(UiNavigation.CONFIRM);return;}
        applyPendingChange();
    }
    private void applyPendingChange(){
        inlineNote="";dictationPermission=false;permissionTarget=null;
        if(pendingMode!=null){store.saveMode(pendingMode);pendingMode=null;handledDictationReply=DictationService.snapshot.generation();navigate(SESSION);}
        else{ConversationData.Chat target=changeTarget;changeTarget=null;prepareSelection(target);}
    }
    private void confirmChange(){
        if(acknowledgePending){text("Hai controllato la conversazione in ChatGPT?",14);text("La chiusura riguarda solo Voxora. Non annulla né reinvia il messaggio su ChatGPT.",12);action("Ho controllato, chiudi",()->{acknowledgePending=false;dictationCommand("ACKNOWLEDGE",1);navigate(SESSION);});action("Mantieni tentativo",()->{acknowledgePending=false;returnPage();});return;}
        if(restorePending){text("Ripristinare le ultime preferenze confermate?",14);text("Voce e lingua valgono dalla prossima apertura. Il volume si applica subito.",12);action("Ripristina",()->{restorePending=false;VoiceOptions restored=store.successfulOptions();if(restored!=null){options=restored;saveOptions(false);commitVolume(options.volumePercent);}returnPage();});action("Mantieni preferenze",()->{restorePending=false;returnPage();});return;}
        String target=pendingMode!=null?(pendingMode.equals("voice")?"Voice":"Dettatura"):changeTarget==null?"Nuova chat":changeTarget.title;
        text("Passare a "+target+"?",14);
        DictationState.Phase phase=DictationService.snapshot.phase();
        text(VoxoraPresentation.changeConsequence(VoiceService.running,DictationService.snapshot),12);
        if(transitionPending)text("Chiusura dell’attività in corso…",12);
        action(VoiceService.running?"Termina e cambia":"Scarta e cambia",this::endForChange).setEnabled(!transitionPending);
        action("Mantieni attività",()->{pendingMode=null;changeTarget=null;returnPage();}).setEnabled(!transitionPending);
    }
    private void endForChange(){
        if(VoxoraPresentation.change(VoiceService.running,DictationService.snapshot)==VoxoraPresentation.Change.BLOCK){pendingMode=null;changeTarget=null;inlineNote="Attendi o controlla l’esito dell’invio. La destinazione non è stata cambiata.";navigate(SESSION);return;}
        if(transitionPending)return;transitionPending=true;long epoch=++transitionEpoch;
        if(VoiceService.running)voiceCommand(VoiceService.STOP);
        if(DictationService.running)dictationCommand(DictationService.accepts("CANCEL",DictationService.snapshot.generation(),DictationService.snapshot)?"CANCEL":"END",1);
        render();awaitStopped(epoch,0);
    }
    private void awaitStopped(long epoch,int attempt){
        if(destroyed||epoch!=transitionEpoch)return;
        if(VoxoraPresentation.canApplyChange(VoiceService.running,DictationService.running)){transitionPending=false;applyPendingChange();return;}
        if(attempt>=50){transitionPending=false;inlineNote="Chiusura non confermata. La destinazione non è stata cambiata.";navigate(SESSION);return;}
        main.postDelayed(()->awaitStopped(epoch,attempt+1),100);
    }
    private void acknowledge(){acknowledgePending=true;showPage(UiNavigation.CONFIRM);}
    private void chooseDestination(ConversationData.Chat chat) {
        handledDictationReply=DictationService.snapshot.generation();
        if (chat == null || selected == null || !chat.id.equals(selected.id)) {
            captionsExpanded = false;
            hiddenSessionStartedAt = VoiceService.startedAt;
        }
        selected = chat; permissionTarget = null;
        VoiceService.retryTarget.choose(chat == null ? null : chat.id);
    }
    private void prepareSelection(ConversationData.Chat chat) { cancelDetail(); chooseDestination(chat); preview = null; previewError = ""; store.savePendingChat(chat); navigate(SESSION); }
    private void cancelList() { listGate.stop(); if (listRequest != null) listRequest.cancel(); listRequest = null; chatRefresh.cancel(); }
    private void cancelDetail() { selectionGate.stop(); if (detailRequest != null) detailRequest.cancel(); detailRequest = null; }
    private void loadChats(boolean refresh) { loadChats(refresh, false); }
    private void loadChats(boolean refresh, boolean automatic) {
        if (chatRefresh.loading()) return;
        cancelList(); chatRefresh.begin(); long generation = listGate.start(); int offset = refresh ? 0 : conversations.nextOffset();
        ChatGptClient client = new ChatGptClient(new CredentialStore(this)); listRequest = client;
        if (navigation.screen() == CHATS) { rememberPosition(); updateChatFeedback(); restorePosition(scroll, navigation.position(), CHATS); }
        io.execute(() -> {
            try {
                ConversationData.Page page = client.list(offset);
                main.post(() -> {
                    if (destroyed || !listGate.accepts(generation)) return;
                    if (navigation.screen() == CHATS) rememberPosition();
                    listGate.stop(); listRequest = null; chatRefresh.completed();
                    if (refresh) conversations.refresh(page, automatic); else conversations.merge(page);
                    store.saveChats(conversations.visible("", Set.of(), false));
                    if (refresh && !automatic) {
                        chatAnchor = null;
                        navigation.remember(CHATS, 0);
                    }
                    if (navigation.screen() == CHATS) { updateChatFeedback(); renderRows(navigation.position()); }
                });
            } catch (Exception e) {
                String reason = VoiceService.safeFailure(e);
                main.post(() -> { if (!destroyed && listGate.accepts(generation)) { listGate.stop(); listRequest = null; chatRefresh.failed(reason); VoiceService.diagnostics = new VoiceDiagnostics("list", reason); if (navigation.screen() == CHATS) { rememberPosition(); updateChatFeedback(); restorePosition(scroll, navigation.position(), CHATS); } } });
            }
        });
    }
    private void openDetail(ConversationData.Chat chat){detailChat=chat;preview=null;previewError="";showPage(UiNavigation.DETAIL);}
    private void detailView(){
        ConversationData.Chat chat=detailChat;
        if(chat==null){text("Conversazione non disponibile dopo la riapertura.",12);return;}
        text(chat.title,14);if(!chat.updatedAt.isEmpty())text(date(chat.updatedAt),11);
        row("Seleziona conversazione","Non avvia il microfono",()->select(chat));
        boolean favorite=store.favorites().contains(chat.id);row(favorite?"Rimuovi dalle preferite":"Aggiungi alle preferite",getString(R.string.favorite)+" solo su questo Watch",()->{if(!store.toggleFavorite(chat.id))inlineNote="Limite di 500 preferite raggiunto. Rimuovine una per aggiungerne un’altra.";rememberPosition();render();});
        row("Aggiorna messaggi recenti","Legge la conversazione da ChatGPT",()->loadPreview(chat));
        if(!previewError.isEmpty())text(previewError,12);
        if(preview!=null&&preview.id.equals(chat.id)){
            if(preview.turns.isEmpty())text("Nessun messaggio leggibile disponibile.",12);
            for(ConversationData.Turn turn:preview.turns)previewButton(turn.role.equals("user")?"Tu":"ChatGPT",turn.text,()->openReader(turn.role.equals("user")?"Tu":"ChatGPT",chat.title+" · Messaggi della chat",turn.text));
        }
    }
    private void loadPreview(ConversationData.Chat chat){
        cancelDetail();long generation=selectionGate.start();previewError="Caricamento messaggi…";rememberPosition();render();
        ChatGptClient client=new ChatGptClient(new CredentialStore(this));detailRequest=client;
        io.execute(()->{try{ConversationData.Detail detail=client.detail(chat.id);main.post(()->{
            if(destroyed||!selectionGate.accepts(generation)||detailChat==null||!detailChat.id.equals(detail.id))return;
            selectionGate.stop();detailRequest=null;preview=detail;previewError="";if(navigation.screen()==UiNavigation.DETAIL){rememberPosition();render();}
        });}catch(Exception error){String reason=VoiceService.safeFailure(error);main.post(()->{if(!destroyed&&selectionGate.accepts(generation)){selectionGate.stop();detailRequest=null;previewError=UiFailure.preview(reason);VoiceService.diagnostics=new VoiceDiagnostics("detail",reason);if(navigation.screen()==UiNavigation.DETAIL){rememberPosition();render();}}});}});
    }
    private void openReader(String heading,String owner,String source){readerHeading=heading;readerOwner=owner;readerSource=source;navigation.remember(UiNavigation.READER,0);showPage(UiNavigation.READER);}
    private void readTurn(boolean output){DictationState.Snapshot d=DictationService.snapshot;openReader(output?"ChatGPT":"Trascrizione",(d.destination()==null?currentTitle():d.destination().title())+" · Turno di dettatura",output?d.output():d.input());}
    private String captionSource(){
        if(!options.captions)return getString(R.string.captions_disabled);
        if(!sessionPresentationCurrent())return "Nessun testo della chiamata per questa destinazione.";
        StringBuilder out=new StringBuilder();for(ConversationData.Turn turn:VoiceService.captions.snapshot()){if(out.length()>0)out.append("\n\n");out.append(turn.role.equals("user")?"Tu: ":"ChatGPT: ").append(turn.text);}return out.length()==0?"Nessuna didascalia ricevuta.":out.toString();
    }
    private void readCaptions(){openReader("Scambi Voice",VoiceService.chatTitle+" · Testo della chiamata",captionSource());}
    private String activityFacts(){
        if(!VoiceService.running&&!dictationMode()&&DictationService.snapshot.phase()==DictationState.Phase.READY)return (voiceFailureVisible()?VoiceState.failure(VoiceService.diagnostics.code):"Voice pronta. Avvia apre la chiamata nella chat selezionata.")+"\n\nChat: "+currentTitle()+"\n\nPreferenze prossima chiamata: "+options.voice+" · "+options.language+"\nVolume: "+options.volumePercent+"%"+(ChatGptClient.voiceRetrySeconds()>0?"\nAttendi "+ChatGptClient.voiceRetrySeconds()+" s prima di riprovare.":"");
        if(VoiceService.running)return VoxoraPresentation.voiceStatus(VoiceService.snapshot)+"\n\n"+(VoiceService.snapshot.muted?"Microfono disattivato. Riattiva per parlare.":"Microfono attivo. Puoi parlare quando ChatGPT è in ascolto.")+"\nDisattiva silenzia solo l’ingresso. Termina chiude la chiamata.\n\nDurata "+clock(SystemClock.elapsedRealtime()-VoiceService.startedAt)+"\nVoce richiesta: "+VoiceService.requestedVoice+" · "+VoiceService.requestedLanguage+"\nUscita di sistema: "+observedRoute()+" · "+options.volumePercent+"%\n\nChat: "+VoiceService.chatTitle;
        DictationState.Snapshot d=DictationService.snapshot;ReadAloudPlayer.Snapshot p=DictationService.playback;
        String hint=switch(d.phase()){
            case READY->"Scegli Voice o Dettatura dalla schermata principale. Nessun microfono attivo.";
            case RECORDING->"Termina per ottenere la bozza. Annulla elimina questa registrazione locale. Nessun messaggio inviato.\nLimiti locali: 10 min / 24 MiB.";
            case CAPTURED->"Registrazione terminata al limite locale. Trascrivi per ottenere la bozza.";
            case TRANSCRIBING->"Nessun messaggio inviato. Annulla ferma il tentativo locale; non revoca dati già ricevuti dal servizio.";
            case DRAFT->"Invia manda la bozza completa. Registra di nuovo sostituisce la bozza nella stessa chat. Scarta elimina solo la bozza locale.";
            case SENDING,WAITING->"Attendi l’esito senza reinviare. Puoi consultare altre schermate e tornare qui.";
            case UNCERTAIN->"Controlla invio legge la chat senza inviare messaggi. Se non è confermato, controlla ChatGPT prima di chiudere il tentativo.";
            case PREPARING_AUDIO->"Risposta ricevuta. Il testo è consultabile; l’audio è in preparazione.";
            case ERROR->dictationFailure(d.error());
            case AUDIO->!p.error().isEmpty()?"La lettura è stata interrotta. Il testo è conservato. Consulta i dettagli e riprova lo stesso audio.":(p.complete()?"Audio completo. Pausa conserva il punto. Ferma lettura torna all’inizio. Ricomincia riproduce lo stesso file.":"Lettura progressiva. Puoi mettere in pausa. Spostamento e velocità disponibili dopo il download.")+"\n"+clock(p.positionMs())+" / "+(p.durationMs()>0?clock(p.durationMs()):"—")+" · "+speedLabel(p.speed());
        };
        return VoxoraPresentation.dictationStatus(d,p)+"\n\n"+hint+"\n\nChat: "+currentTitle();
    }
    private void statusView(){
        shell.head(tool("close","","Chiudi stato",this::returnPage,false),tool(activityIcon(),activityTag().isEmpty()?"Opzioni":activityTag(),"Stato corrente",()->{},false),tool("back","Attività","Torna all’attività",()->navigate(SESSION),false));
        indicatorFacts=text(activityFacts(),12);
        if(VoiceService.running){
            shell.actions(tool(VoiceService.snapshot.muted?"mic":"mute",VoiceService.snapshot.muted?"Riattiva":"Disattiva","Microfono",()->voiceCommand(VoiceService.MUTE),true),tool("stop","Termina","Termina chiamata",()->voiceCommand(VoiceService.STOP),true),tool("volume","Volume","Volume chiamata",()->showPage(UiNavigation.VOLUME),true));
            row("Scambi Voice","Testo della chiamata",this::readCaptions);
        }else{
            DictationState.Snapshot d=DictationService.snapshot;ReadAloudPlayer.Snapshot p=DictationService.playback;
            if(d.phase()!=DictationState.Phase.READY){
                String command=VoxoraPresentation.primary(d,p);Button primary=tool(d.assistantId()!=null?"play":d.phase()==DictationState.Phase.DRAFT?"send":"record",VoxoraPresentation.primaryLabel(d,p),VoxoraPresentation.primaryLabel(d,p),()->dictationUi(command,1,d.generation()),true);primary.setEnabled(!command.isEmpty());
                shell.actions(null,primary,tool("volume","Volume","Volume lettura",()->showPage(UiNavigation.VOLUME),true));
            }
            if(d.assistantId()!=null){row("Ferma lettura","Ferma e riporta lo stesso audio all’inizio",()->dictationUi("STOP_AUDIO",1,d.generation()));Button replay=action("Ricomincia lettura",()->dictationUi("REPLAY",1,d.generation()));replay.setEnabled(p.complete());if(!p.complete())text("Ricomincia dopo il download completo.",11);row("Testo ChatGPT","",()->readTurn(true));}
            if(d.phase()==DictationState.Phase.DRAFT){row("Registra di nuovo","Sostituisce la bozza attuale",()->dictationUi("RETAKE",1,d.generation()));row("Scarta bozza","Elimina solo il testo non inviato",()->dictationUi("CANCEL",1,d.generation()));}
            if(d.phase()==DictationState.Phase.UNCERTAIN)row("Chiudi dopo controllo","Solo dopo aver verificato ChatGPT",this::acknowledge);
            if(!d.input().isEmpty())row("Trascrizione","",()->readTurn(false));
            if(DictationService.accepts("CANCEL",d.generation(),d)&&d.phase()!=DictationState.Phase.DRAFT)row("Annulla tentativo","Nessun messaggio sarà inviato",()->dictationUi("CANCEL",1,d.generation()));
            if(d.phase()==DictationState.Phase.READY){if(!dictationMode()){Button start=action("Avvia chiamata",this::startVoice);start.setEnabled(ChatGptClient.voiceRetrySeconds()==0);}row("Nuova chat","",()->select(null));row("Conversazioni","",()->navigate(CHATS));}
        }
        row("Uscita audio","Scegli il dispositivo",()->showPage(UiNavigation.ROUTE));row("Impostazioni","Preferenze per le prossime aperture",()->navigate(SETTINGS));row("Diagnostica","Fase, rete e ultimo errore",()->showPage(UiNavigation.DIAGNOSTICS));
    }
    private String effectiveOutput(){return VoxoraPresentation.output(VoiceService.running,DictationService.running&&DictationService.snapshot.assistantId()!=null,observedRoute(),DictationService.outputLabel);}
    private String observedRoute(){
        AudioManager audio=getSystemService(AudioManager.class);if(Build.VERSION.SDK_INT>=31){AudioDeviceInfo device=audio.getCommunicationDevice();if(device!=null)return device.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER?"Speaker":device.getProductName().toString();}return "Non confermata";
    }
    private String dictationFailure(String code){
        if(code.startsWith("model_"))return "Invio non eseguito. Modello o livello di ragionamento non verificabile: controlla la scelta in ChatGPT. La bozza è conservata.";
        if(code.contains("429"))return "Limite temporaneo del servizio. Riprova più tardi; nessun reinvio automatico.";
        if(code.contains("401")||code.contains("403")||code.startsWith("auth_")||code.contains("session"))return "Accesso al servizio non disponibile. Verifica la configurazione dal computer.";
        if(code.contains("404"))return "Conversazione o messaggio non disponibile. Aggiorna l’elenco o controlla ChatGPT.";
        if(code.contains("permission"))return "Permesso microfono necessario. Consenti l’accesso nelle impostazioni di Voxora.";
        if(code.contains("transcription")||code.equals("recording_missing"))return "Trascrizione non disponibile. Riprova se la registrazione è conservata, oppure registra un nuovo messaggio.";
        if(code.contains("storage")||code.contains("size")||code.contains("limit"))return "Contenuto non elaborabile entro i limiti locali. Nessun testo viene troncato o inviato in parte.";
        return "L’operazione non è riuscita. Consulta i dettagli prima di riprovare.";
    }
    private void diagnosticsView(){
        text("Voxora "+version(),12);text("Attività: "+(VoiceService.running?VoxoraPresentation.voiceStatus(VoiceService.snapshot):VoxoraPresentation.dictationStatus(DictationService.snapshot,DictationService.playback)),12);
        android.net.ConnectivityManager network=getSystemService(android.net.ConnectivityManager.class);android.net.NetworkCapabilities caps=network.getNetworkCapabilities(network.getActiveNetwork());
        text("Rete osservata: "+(caps==null?"non disponibile":caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)?"Wi-Fi":caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH)?"Bluetooth tramite telefono":"rete di sistema")+"\nQuesto dato non garantisce accesso a Internet.",11);
        text(VoiceService.diagnostics==null?"Nessun errore Voice registrato.":VoiceService.diagnostics.label(),12);
        if(!DictationService.snapshot.error().isEmpty())text("Dettatura · fase: "+DictationService.snapshot.phase()+"\nCodice: "+DictationService.snapshot.error(),12);
        if(!DictationService.playback.error().isEmpty())text("Audio: "+DictationService.playback.error(),12);
        text("Canale chiamate: "+observedRoute()+"\nPreferenza: "+routeLabel(),11);
    }
    private void settingsView() {
        row("Voce", options.voice, () -> showPage(UiNavigation.VOICE));
        row("Lingua", options.language, () -> showPage(UiNavigation.LANGUAGE));
        volumeControl();
        divider(); row("Uscita", routeLabel(), () -> showPage(UiNavigation.ROUTE));
        text("Voce e lingua: prossima chiamata o registrazione. Non cambiano l’audio già scaricato.", 11);
        setting("Vibrazioni", options.haptics, value -> options.haptics = value, false);
        setting("Testo grande", options.largeText, value -> options.largeText = value, true);
        setting("Didascalie", options.captions, value -> options.captions = value, false);
        setting("Schermo acceso in chiamata", options.keepScreenOn, value -> options.keepScreenOn = value, false);
        VoiceOptions successful = store.successfulOptions();
        action("Ripristina preferenze confermate", () -> { restorePending=true;showPage(UiNavigation.CONFIRM); }).setEnabled(successful != null);
        if (successful == null) text("Nessuna configurazione confermata disponibile", 12);
        row("Diagnostica","Rete e dettagli degli errori",()->showPage(UiNavigation.DIAGNOSTICS));
        text("Voxora "+version(),11);text("Le voci richiedono accettazione del servizio. Nessun cambio automatico.",11);
    }
    private void row(String name, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL); row.setMinimumHeight(dp(48));
        row.setPadding(dp(3), dp(3), dp(3), dp(3));
        TextView label = label(name, 14); label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, value.isEmpty() ? 1 : .55f));
        if (!value.isEmpty()) {
            TextView current = label(value + " ›", 14); current.setGravity(Gravity.END); current.setTextColor(RoundUi.ACCENT); current.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            row.addView(current, new LinearLayout.LayoutParams(0, -2, .45f));
        }
        row.setContentDescription(name + (value.isEmpty() ? "" : ": " + value)); row.setClickable(true); row.setFocusable(true);
        android.util.TypedValue selectable = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true); row.setBackgroundResource(selectable.resourceId);
        row.setOnClickListener(v -> action.run()); root.addView(row, new LinearLayout.LayoutParams(-1, -2)); divider();
    }
    private String version() { try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { return ""; } }
    private interface Choice { void selected(String value); }
    private void voiceChoices() {
        text("Si applica alla prossima chiamata", 12);
        VoiceOptions successful = store.successfulOptions();
        for (String voice : VoiceOptions.VOICES) {
            boolean accepted = VoiceService.acceptedVoices.contains(voice) || (successful != null && successful.voice.equals(voice));
            String subtitle = accepted ? "Accettata dal servizio" : "Non provata in questa sessione";
            choiceRow(voice, subtitle, voice.equals(options.voice), () -> { options.voice = voice; saveOptions(false); returnPage(); });
        }
    }
    private interface Toggle { void selected(boolean value); }
    private void choiceRows(String[] ids, String[] labels, String current, Choice callback) {
        text("Si applica alla prossima chiamata", 12);
        for (int i=0; i<ids.length; i++) { String id = ids[i]; choiceRow(labels[i], "", id.equals(current), () -> callback.selected(id)); }
    }
    private void choiceRow(String name, String note, boolean selected, Runnable action) {
        LinearLayout choice = new LinearLayout(this); choice.setOrientation(LinearLayout.VERTICAL); choice.setMinimumHeight(dp(48)); choice.setPadding(dp(3), dp(4), dp(3), dp(4));
        TextView nameText = label(name + (selected ? " ✓" : ""), 15); nameText.setTextColor(selected ? RoundUi.ACCENT : Color.WHITE); nameText.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        choice.addView(nameText, new LinearLayout.LayoutParams(-1, -2));
        if (!note.isEmpty()) { TextView hint = label(note, 11); hint.setTextColor(RoundUi.DIM); hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); choice.addView(hint, new LinearLayout.LayoutParams(-1, -2)); }
        choice.setSelected(selected); choice.setContentDescription(name + (selected ? ", selezionata" : "") + (note.isEmpty() ? "" : ", " + note)); choice.setClickable(true); choice.setFocusable(true);
        android.util.TypedValue selectable = new android.util.TypedValue(); getTheme().resolveAttribute(android.R.attr.selectableItemBackground, selectable, true); choice.setBackgroundResource(selectable.resourceId);
        choice.setOnClickListener(v -> action.run()); root.addView(choice, new LinearLayout.LayoutParams(-1, -2)); divider();
    }
    private void setting(String text, boolean current, Toggle toggle, boolean rerender) {
        Switch control = new Switch(this); control.setText(text); control.setTextColor(Color.WHITE); control.setTextSize(options.largeText ? 17 : 14); control.setMinHeight(dp(48)); control.setChecked(current); root.addView(control, new LinearLayout.LayoutParams(-1, -2)); divider();
        control.setOnCheckedChangeListener((v, checked) -> { toggle.selected(checked); saveOptions(rerender); });
    }
    private void saveOptions(boolean rerender) { store.saveOptions(options); if (VoiceService.running) voiceCommand(VoiceService.APPLY); if (rerender) { rememberPosition(); render(); } }
    private void voiceCommand(String action) {
        if (!VoiceService.running) return;
        startService(new Intent(this, VoiceService.class).setAction(action));
        if (options.haptics) getSystemService(Vibrator.class).vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
    }
    private boolean sessionPresentationCurrent() {
        if (VoiceService.running) return true;
        if (hiddenSessionStartedAt == VoiceService.startedAt) return false;
        String id = selected == null ? null : selected.id;
        return Objects.equals(id, VoiceService.requestedId)
            || (id != null && id.equals(VoiceService.confirmedId))
            || VoiceService.retryTarget.resolve(id) != null;
    }
    private String routeLabel() {
        if (options.route.equals("speaker")) return "Speaker";
        if (Build.VERSION.SDK_INT >= 31) for (AudioDeviceInfo device : getSystemService(AudioManager.class).getAvailableCommunicationDevices()) if (options.route.equals("device_" + device.getId())) return device.getProductName().toString();
        return "Dispositivo non disponibile";
    }
    private void routeChoices() {
        text("Uscita effettiva: "+effectiveOutput(),11);text("Voice verifica il cambio durante la chiamata. Read Aloud applica la scelta al prossimo audio.",11);
        List<String> ids = new ArrayList<>(), labels = new ArrayList<>(); ids.add("speaker"); labels.add("Speaker");
        if (Build.VERSION.SDK_INT >= 31) for (AudioDeviceInfo device : getSystemService(AudioManager.class).getAvailableCommunicationDevices()) if (device.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) { ids.add("device_" + device.getId()); labels.add(device.getProductName().toString()); }
        for (int i=0; i<ids.size(); i++) { String id = ids.get(i); choiceRow(labels.get(i), "", id.equals(options.route), () -> { options.route = id; saveOptions(false); returnPage(); }); }
    }
    private void startVoice() {
        RetryTarget.Target retry=VoiceService.retryTarget.resolve(selected==null?null:selected.id);
        if(retry!=null)startVoiceFor(retry.id,retry.title);
        else startVoiceFor(selected==null?null:selected.id,selected==null?"Nuova chat":selected.title);
    }
    private void startVoiceFor(String id, String chatTitle) {
        if (VoiceService.running || DictationService.running) return;
        if(ChatGptClient.voiceRetrySeconds()>0){Toast.makeText(this,"ChatGPT richiede ancora "+ChatGptClient.voiceRetrySeconds()+" s di attesa",Toast.LENGTH_SHORT).show();return;}
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { permissionTarget = new RetryTarget.Target(id, chatTitle); requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1); return; }
        Intent intent = new Intent(this, VoiceService.class).setAction(VoiceService.START)
            .putExtra("selectionNew", selected == null);
        if (selected != null) intent.putExtra("selectionId", selected.id);
        if (id != null) intent.putExtra("conversationId", id).putExtra("chatTitle", chatTitle);
        VoiceService.uiVisible = true;
        startForegroundService(intent);
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] result) { super.onRequestPermissionsResult(code, permissions, result); if (code == 1) {
        RetryTarget.Target target = permissionTarget; permissionTarget = null;
        if (target != null && result.length > 0 && result[0] == PackageManager.PERMISSION_GRANTED) startVoiceFor(target.id, target.title);
    } else if(code==2){boolean pending=dictationPermission;dictationPermission=false;if(pending&&result.length>0&&result[0]==PackageManager.PERMISSION_GRANTED&&dictationMode())dictationCommand("RECORD",1);} }
    private boolean dictationMode(){return "dictation".equals(store.mode());}
    private boolean dictationLocked(){DictationState.Snapshot value=DictationService.snapshot;return value.phase()!=DictationState.Phase.READY&&value.phase()!=DictationState.Phase.AUDIO&&!(value.phase()==DictationState.Phase.ERROR&&value.assistantId()!=null);}
    private void changeMode(){requestChange(dictationMode()?"voice":"dictation",selected);}
    private void dictationUi(String command,float speed,long generation){
        DictationState.Snapshot current=DictationService.snapshot;
        if(DictationService.accepts(command,generation,current))dictationCommand(command,speed);
    }
    private void dictationCommand(String command,float speed){
        if(VoiceService.running||command==null||command.isEmpty())return;
        if(command.equals("RECORD")&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){dictationPermission=true;requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},2);return;}
        DictationState.Snapshot value=DictationService.snapshot;
        Intent request=new Intent(this,DictationService.class).setAction(DictationService.ACTION).putExtra("command",command).putExtra("generation",value.generation()).putExtra("speed",speed);
        if(command.equals("RECORD")){if(selected!=null)request.putExtra("conversationId",selected.id);request.putExtra("chatTitle",selected==null?"Nuova conversazione":selected.title);}
        if(command.equals("END")&&!DictationService.running)return;
        if(DictationService.running)startService(request);else startForegroundService(request);
    }
    private void setUiVisible(boolean visible) {
        main.removeCallbacks(update); VoiceService.uiVisible = visible;
        if (visible) main.post(update); else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (VoiceService.running) startService(new Intent(this, VoiceService.class).setAction(VoiceService.VISIBLE).putExtra("visible", visible));
    }
    @Override public void onResume() { super.onResume(); resumed = true; setUiVisible(hasWindowFocus()); loadChats(true, true); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (store != null) setUiVisible(resumed && focus); }
    @Override public void onPause() { resumed = false; rememberPosition(); setUiVisible(false); super.onPause(); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
            ScrollView target = scroll;
            if (target != null) {
                int pixels = Math.round(-event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(this).getScaledVerticalScrollFactor());
                target.scrollBy(0, pixels); return true;
            }
        }
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public void onDestroy() { main.removeCallbacks(liveVolume);main.removeCallbacks(update);if(indicatorAnimation!=null)indicatorAnimation.cancel();destroyed = true; cancelList(); cancelDetail(); io.shutdownNow(); super.onDestroy(); }
    @Override public void onSaveInstanceState(Bundle state) {
        rememberPosition(); state.putBoolean("newChat", selected == null);
        if (selected != null) { state.putString("selectedId", selected.id); state.putString("selectedTitle", selected.title); state.putString("selectedDate", selected.updatedAt); }
        state.putString("pendingMode",pendingMode);state.putBoolean("acknowledgePending",acknowledgePending);state.putBoolean("restorePending",restorePending);
        if(detailChat!=null){state.putString("detailId",detailChat.id);state.putString("detailTitle",detailChat.title);state.putString("detailDate",detailChat.updatedAt);}
        state.putIntArray("path", navigation.path()); state.putIntArray("positions", navigation.positions());
        if (chatAnchor != null) { state.putString("chatAnchorId", chatAnchor.id); state.putInt("chatAnchorOffset", chatAnchor.withinRow); }
        state.putString("query", query); state.putBoolean("favorites", favoritesOnly); state.putBoolean("captionsExpanded", captionsExpanded);
        if (changeTarget != null) { state.putString("changeId", changeTarget.id); state.putString("changeTitle", changeTarget.title); state.putString("changeDate", changeTarget.updatedAt); }
        super.onSaveInstanceState(state);
    }
    @Override public void onBackPressed() { rememberPosition(); if(transitionPending)return;if (navigation.back()) render(); else super.onBackPressed(); }
}





