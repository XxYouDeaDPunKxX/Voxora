package local.watchvoice;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.media.*;
import android.net.*;
import android.os.*;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.wear.ongoing.OngoingActivity;
import androidx.wear.ongoing.Status;
import java.util.concurrent.*;

public final class VoiceService extends Service {
    static final String START = "local.watchvoice.START", STOP = "local.watchvoice.STOP", MUTE = "local.watchvoice.MUTE", APPLY = "local.watchvoice.APPLY", VISIBLE = "local.watchvoice.VISIBLE", VOLUME = "local.watchvoice.VOLUME";
    static volatile boolean running;
    static volatile String requestedVoice="",requestedLanguage="";
    static volatile String status = "Pronto";
    static volatile String requestedId, confirmedId, chatTitle = "Nuova conversazione";
    static volatile VoiceState snapshot = new VoiceState("idle", "", false, null);
    static volatile CaptionBuffer captions = new CaptionBuffer();
    static volatile float microphoneLevel;
    static volatile long startedAt;
    static volatile String outputLabel = "";
    static volatile boolean uiVisible;
    static final RetryTarget retryTarget = new RetryTarget();
    static volatile VoiceDiagnostics diagnostics;
    static final java.util.Set<String> acceptedVoices = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CallGate gate = new CallGate();
    private VoiceEngine engine;
    private AudioManager audioManager;
    private AudioFocusRequest focus;
    private int oldMode, oldVolume;
    private boolean audioOwned, oldSpeaker;
    private PowerManager.WakeLock wakeLock;
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private CallNetwork callNetwork;
    private boolean callbackRegistered;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private ChatGptClient resumeClient;
    private String parentMessageId, selectionAtStart;
    private VoiceOptions options;
    private String phase = "idle", serverState = "";
    private boolean muted, acceptedOptions, routeReady, routePending;
    private int selectedDeviceId = -1;
    private int desiredVolumePercent;
    private final java.util.Map<Integer, Integer> priorRouteVolumes = new java.util.HashMap<>();
    private long callGeneration, selectionGeneration;
    private AudioDeviceCallback deviceCallback;
    private AudioManager.OnCommunicationDeviceChangedListener routeListener;
    private Runnable routeTimeout;
    private NotificationCompat.Builder notificationBuilder;
    private OngoingActivity ongoingActivity;
    private boolean foregroundPublished;

    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("voice", "Conversazione vocale", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || STOP.equals(intent.getAction())) { finish("Pronto", false); return START_NOT_STICKY; }
        String action = intent.getAction();
        if (MUTE.equals(action) || APPLY.equals(action) || VISIBLE.equals(action) || VOLUME.equals(action)) {
            if (!running) { stopSelf(); return START_NOT_STICKY; }
            if (VOLUME.equals(action) && intent.getLongExtra("startedAt",-1)==startedAt) {
                desiredVolumePercent=Math.max(0,Math.min(100,intent.getIntExtra("volume",desiredVolumePercent)));options.volumePercent=desiredVolumePercent;
                if(audioOwned&&routeReady&&!routePending){
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL,Math.round(audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)*desiredVolumePercent/100f),0);
                    Log.i("WatchVoice","live_volume requested="+desiredVolumePercent+" actual="+audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)+" max="+audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL));
                }
            }
            if (MUTE.equals(action) && engine != null) { muted = !muted; engine.setMuted(muted); refreshState(); }
            if (VISIBLE.equals(action)) { uiVisible = intent.getBooleanExtra("visible", false); if (engine != null) engine.setLevelsVisible(uiVisible); }
            if (APPLY.equals(action) && audioOwned) applyAudioOptions(new WatchStore(this).options(), callGeneration, false);
            return START_NOT_STICKY;
        }
        if (!START.equals(action)) { if (!running) stopSelf(); return START_NOT_STICKY; }
        if (DictationService.running) { if (!running) stopSelf(); return START_NOT_STICKY; }
        if(!running&&ChatGptClient.voiceRetrySeconds()>0){status="ChatGPT richiede attesa: "+ChatGptClient.voiceRetrySeconds()+" s";stopSelf();return START_NOT_STICKY;}
        Log.i("WatchVoice", "call_start_requested active=" + running);
        long generation = gate.start();
        if (generation == -1) return START_NOT_STICKY;
        callGeneration = generation; selectionGeneration = retryTarget.generation();
        requestedId = intent.getStringExtra("conversationId"); confirmedId = null; parentMessageId = null;
        selectionAtStart = intent.hasExtra("selectionNew")
            ? intent.getBooleanExtra("selectionNew", false) ? null : intent.getStringExtra("selectionId") : requestedId;
        chatTitle = requestedId == null ? "Nuova conversazione" : intent.getStringExtra("chatTitle");
        if (chatTitle == null || chatTitle.isEmpty()) chatTitle = "Conversazione selezionata";
        options = new WatchStore(this).options();
        requestedVoice=options.voice;requestedLanguage=options.language;
        running = true; muted = false; acceptedOptions = false; serverState = ""; phase = "network"; callNetwork = new CallNetwork();
        captions = new CaptionBuffer(); microphoneLevel = 0f; outputLabel = ""; retryTarget.clear(); startedAt = SystemClock.elapsedRealtime(); refreshState();
        main.postDelayed(() -> { if (gate.accepts(generation) && !phase.equals("connected")) finish(VoiceState.failure("connessione_timeout"), true, "connessione_timeout"); }, 75000);
        try {
            startForeground(1, notification()); foregroundPublished = true;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) throw new IllegalStateException("microfono_permesso");
            wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WatchVoice:conversation");
            wakeLock.acquire(4 * 60 * 60 * 1000L);
            connectivity = getSystemService(ConnectivityManager.class);
            awaitNetwork(generation);
        } catch (Exception e) { finish(VoiceState.failure(safeFailure(e)), true, safeFailure(e)); }
        return START_NOT_STICKY;
    }
    private void awaitNetwork(long generation) {
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { main.post(() -> {
                if (!gate.accepts(generation)) return;
                boolean usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
                switch (callNetwork.available(network.getNetworkHandle(), usable)) {
                    case START -> {
                        Log.i("WatchVoice", "network_ready wifi=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                            + " bluetooth=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH));
                        prepareConversation(generation);
                    }
                    case STOP -> finish("Rete cambiata: premi Avvia per ricollegarti", true, "network_changed");
                    default -> { }
                }
            }); }
            @Override public void onLost(Network network) { main.post(() -> {
                if (gate.accepts(generation) && callNetwork.lost(network.getNetworkHandle()))
                    finish("Connessione persa: verifica telefono o Wi-Fi e riprova", true, "network_lost");
            }); }
        };
        connectivity.registerDefaultNetworkCallback(networkCallback);
        callbackRegistered = true;
        main.postDelayed(() -> {
            if (gate.accepts(generation) && !callNetwork.started())
                finish("Internet non disponibile: verifica telefono o Wi-Fi", true, "network_unavailable");
        }, 15000);
    }
    private void prepareConversation(long generation) {
        if (requestedId == null) { beginAudio(generation); return; }
        phase = "resuming"; refreshState();
        String id = requestedId;
        ChatGptClient client = new ChatGptClient(new CredentialStore(this)); resumeClient = client;
        io.execute(() -> {
            try {
                ConversationData.Detail detail = client.detail(id);
                if ((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                    byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(detail.currentNode.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    StringBuilder fingerprint = new StringBuilder();
                    for (byte value : digest) fingerprint.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
                    Log.i("WatchVoice", "resume_parent_sha256=" + fingerprint);
                }
                main.post(() -> {
                    if (!gate.accepts(generation)) return;
                    resumeClient = null; parentMessageId = detail.currentNode; chatTitle = detail.title;
                    Log.i("WatchVoice", "resume_detail_current=true"); beginAudio(generation);
                });
            } catch (Exception e) {
                String reason = safeFailure(e);
                main.post(() -> { if (gate.accepts(generation)) finish("Ripresa fallita: " + VoiceState.failure(reason), true, reason); });
            }
        });
    }
    static String safeFailure(Exception e) {
        String reason = e.getMessage(); return reason != null && reason.matches("[a-z_]+(?:[0-9]{3})?") ? reason : e.getClass().getSimpleName();
    }
    private void beginAudio(long generation) {
        try {
            phase = "connecting"; refreshState();
            audioManager = getSystemService(AudioManager.class);
            oldMode = audioManager.getMode(); oldVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL);
            oldSpeaker = audioManager.isSpeakerphoneOn();
            audioOwned = true;
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(change -> {
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
                        main.post(() -> { if (gate.accepts(generation)) finish("Audio occupato", true, "audio_focus_lost"); });
                }).build();
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw new IllegalStateException("focus_denied");
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            deviceCallback = new AudioDeviceCallback() {
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) {
                    if (!gate.accepts(generation)) return;
                    for (AudioDeviceInfo device : devices) if (device.getId() == selectedDeviceId) {
                        finish("Uscita audio disconnessa. Chiamata terminata", true, "audio_route_removed"); return;
                    }
                }
            };
            audioManager.registerAudioDeviceCallback(deviceCallback, main);
            if (Build.VERSION.SDK_INT >= 31) {
                routeListener = device -> {
                    if (!gate.accepts(generation)) return;
                    if (device != null && device.getId() == selectedDeviceId) {
                        if (routePending) routeConfirmed(generation);
                    } else if (routeReady && !routePending) finish("Uscita audio cambiata. Chiamata terminata", true, "audio_route_changed");
                };
                audioManager.addOnCommunicationDeviceChangedListener(getMainExecutor(), routeListener);
            }
            applyAudioOptions(options, generation, true);
        } catch (Exception e) { finish(VoiceState.failure(safeFailure(e)), true, safeFailure(e)); }
    }
    private void applyAudioOptions(VoiceOptions preferred, long generation, boolean initial) {
        if (!gate.accepts(generation)) return;
        try {
            desiredVolumePercent = preferred.volumePercent;
            if (Build.VERSION.SDK_INT < 31) {
                if (!preferred.route.equals("speaker")) throw new IllegalStateException("audio_route_unavailable");
                audioManager.setSpeakerphoneOn(true); outputLabel = "Speaker"; routeReady = true;
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, Math.round(audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * desiredVolumePercent / 100f), 0);
                if (initial) startEngine(generation); return;
            }
            AudioDeviceInfo chosen = null;
            java.util.List<AudioDeviceInfo> devices = audioManager.getAvailableCommunicationDevices();
            Log.i("WatchVoice", "communication_devices_count=" + devices.size());
            for (AudioDeviceInfo device : devices) {
                if (preferred.route.equals("speaker") ? device.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER : preferred.route.equals("device_" + device.getId())) { chosen = device; break; }
            }
            if (chosen == null) { finish("Uscita audio scelta non disponibile. Chiamata terminata", true, "audio_route_unavailable"); return; }
            AudioDeviceInfo current = audioManager.getCommunicationDevice();
            selectedDeviceId = chosen.getId(); outputLabel = chosen.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER ? "Speaker" : "Auricolari / dispositivo";
            Log.i("WatchVoice", "communication_route_type=" + chosen.getType());
            if (current != null && current.getId() == selectedDeviceId) { routeConfirmed(generation); return; }
            if (engine != null) engine.setOutputPaused(true);
            if (current != null && priorRouteVolumes.containsKey(current.getId())) audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, priorRouteVolumes.get(current.getId()), 0);
            routePending = true;
            if (!audioManager.setCommunicationDevice(chosen)) { finish("Uscita audio rifiutata. Chiamata terminata", true, "audio_route_rejected"); return; }
            if (routeTimeout != null) main.removeCallbacks(routeTimeout);
            routeTimeout = () -> { if (gate.accepts(generation) && routePending) finish("Timeout uscita audio. Chiamata terminata", true, "audio_route_timeout"); };
            main.postDelayed(routeTimeout, 10000);
            current = audioManager.getCommunicationDevice();
            if (current != null && current.getId() == selectedDeviceId) routeConfirmed(generation);
        } catch (Exception e) { finish(VoiceState.failure(safeFailure(e)), true, safeFailure(e)); }
    }
    private void routeConfirmed(long generation) {
        routePending = false; routeReady = true;
        if (routeTimeout != null) main.removeCallbacks(routeTimeout);
        priorRouteVolumes.putIfAbsent(selectedDeviceId, audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL));
        audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, Math.round(audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL) * desiredVolumePercent / 100f), 0);
        Log.i("WatchVoice", "communication_route_confirmed=true previous_volume=" + priorRouteVolumes.get(selectedDeviceId) + " volume=" + audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) + " max=" + audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL));
        if (engine != null) engine.setOutputPaused(false);
        if (engine == null) startEngine(generation);
    }
    private void startEngine(long generation) {
        if (!gate.accepts(generation) || engine != null) return;
        try {
            engine = new VoiceEngine(this, new VoiceEngine.Listener() {
                public void caption(String kind, String text) { if (gate.accepts(generation)) captions.add(kind, text); }
                public void level(float level) { if (gate.accepts(generation)) microphoneLevel = muted ? 0f : level; }
                public void state(String state) {
                    if (!gate.accepts(generation)) return;
                    if (state.equals("connected") && !phase.equals("connected")) { phase = "connected"; haptic(VibrationEffect.EFFECT_CLICK); }
                    serverState = state;
                    refreshState(); markAcceptedOptions();
                }
                public void ended(String reason, String code) { if (gate.accepts(generation)) finish(reason, !reason.equals("Terminata"), code); }
                public void conversation(String id) {
                    if (!gate.accepts(generation)) return;
                    if (requestedId != null && !requestedId.equals(id)) { finish("Identità chat diversa", true, "identity_mismatch"); return; }
                    confirmedId = id;
                    new WatchStore(VoiceService.this).saveLastChat(new ConversationData.Chat(id, chatTitle, ""));
                    refreshState(); markAcceptedOptions();
                    Log.i("WatchVoice", "conversation_confirmed=true requested_match=" + (requestedId == null || requestedId.equals(id)));
                }
            });
            engine.setLevelsVisible(uiVisible);
            engine.start(requestedId, parentMessageId, options);
        } catch (Exception e) { finish(VoiceState.failure(safeFailure(e)), true, safeFailure(e)); }
    }
    private void markAcceptedOptions() {
        if (!acceptedOptions && phase.equals("connected") && confirmedId != null) {
            new WatchStore(this).saveSuccessfulOptions(options); acceptedOptions = true;
            acceptedVoices.add(options.voice);
            Log.i("WatchVoice", "voice_configuration_accepted=true");
        }
    }
    private void refreshState() {
        snapshot = new VoiceState(phase, serverState, muted, confirmedId); status = snapshot.label();
        if (running && foregroundPublished && (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)) {
            notificationBuilder.setContentText(status);
            ongoingActivity.update(this, ongoingStatus());
        }
    }
    private Status ongoingStatus() { return new Status.Builder().addPart("state", new Status.TextPart(status)).build(); }
    private Notification notification() {
        if (notificationBuilder == null) {
            PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class).putExtra(WearEntry.EXTRA, "session")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, VoiceService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            notificationBuilder = new NotificationCompat.Builder(this, "voice").setSmallIcon(R.drawable.ic_voxora_notification).setContentTitle("Voxora")
                .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).addAction(0, "Stop", stop);
            ongoingActivity = new OngoingActivity.Builder(this, 1, notificationBuilder).setStaticIcon(R.drawable.ic_voxora_notification)
                .setTouchIntent(open).setStatus(ongoingStatus()).build();
            ongoingActivity.apply(this);
        }
        return notificationBuilder.setContentText(status).build();
    }
    private void haptic(int effect) {
        if (new WatchStore(this).options().haptics) getSystemService(Vibrator.class).vibrate(VibrationEffect.createPredefined(effect));
    }
    private void finish(String reason) {
        finish(reason, true);
    }
    private void finish(String reason, boolean retry) {
        finish(reason, retry, null);
    }
    private void finish(String reason, boolean retry, String code) {
        boolean wasRunning = running;
        if (wasRunning && retry) diagnostics = new VoiceDiagnostics(phase, code);
        gate.stop(); running = false; foregroundPublished = false; status = reason;
        retryTarget.failed(selectionAtStart, confirmedId != null ? confirmedId : requestedId, chatTitle, retry, selectionGeneration);
        snapshot = new VoiceState("idle", "", false, confirmedId); microphoneLevel = 0f;
        main.removeCallbacksAndMessages(null);
        cleanup(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        if (wasRunning) haptic(retry ? VibrationEffect.EFFECT_DOUBLE_CLICK : VibrationEffect.EFFECT_TICK);
        Log.i("WatchVoice", "stopped retry=" + retry + " phase=" + phase + " code=" + (retry ? VoiceDiagnostics.safeCode(code) : "none"));
    }
    private void cleanup() {
        if (resumeClient != null) { resumeClient.cancel(); resumeClient = null; }
        if (engine != null) { engine.close(); engine = null; }
        if (audioOwned) {
            try {
                if (deviceCallback != null) audioManager.unregisterAudioDeviceCallback(deviceCallback);
                if (Build.VERSION.SDK_INT >= 31 && routeListener != null) audioManager.removeOnCommunicationDeviceChangedListener(routeListener);
                if (Build.VERSION.SDK_INT >= 31) {
                    AudioDeviceInfo current = audioManager.getCommunicationDevice();
                    if (current != null && priorRouteVolumes.containsKey(current.getId())) audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, priorRouteVolumes.get(current.getId()), 0);
                } else audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, oldVolume, 0);
                if (Build.VERSION.SDK_INT >= 31) {
                    audioManager.clearCommunicationDevice();
                } else { audioManager.setSpeakerphoneOn(oldSpeaker); }
                audioManager.setMode(oldMode);
                if (focus != null) audioManager.abandonAudioFocusRequest(focus);
                Log.i("WatchVoice", "audio_restore_requested=true volume=" + audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) + " mode=" + audioManager.getMode());
            } catch (Exception ignored) { Log.w("WatchVoice", "audio_restore_failed"); }
            audioOwned = false;
            priorRouteVolumes.clear();
            routeReady = false; routePending = false; deviceCallback = null; routeListener = null;
        }
        if (connectivity != null) {
            if (callbackRegistered) { connectivity.unregisterNetworkCallback(networkCallback); callbackRegistered = false; }
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }
    @Override public void onDestroy() { gate.stop(); running = false; cleanup(); io.shutdownNow(); super.onDestroy(); }
}

