package local.watchvoice;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONObject;
import org.webrtc.*;
import org.webrtc.audio.JavaAudioDeviceModule;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** All peer operations run on main; WebRTC callbacks only post work to it. */
final class VoiceEngine {
    interface Listener { void state(String state); void caption(String kind, String text); void level(float level); void ended(String reason, String code); void conversation(String id); }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ChatGptClient client;
    private volatile boolean closed, muted, levelsVisible;
    private boolean sentOffer, localReady, channelOpen;
    private long lastLevelNanos;
    private int muteRevision;
    private PeerConnectionFactory factory;
    private JavaAudioDeviceModule audio;
    private PeerConnection peer;
    private DataChannel channel;
    private AudioSource source;
    private org.webrtc.AudioTrack track;
    private String conversationId, parentMessageId;
    private VoiceOptions options;
    private final Set<String> observedTypes = new HashSet<>();
    private final Set<String> observedSchemas = new HashSet<>();
    private final VoiceProtocol.LiveCaptions liveCaptions = new VoiceProtocol.LiveCaptions();

    VoiceEngine(Context context, Listener listener) {
        this.context = context; this.listener = listener;
        client = new ChatGptClient(new CredentialStore(context));
    }
    private void post(Runnable action) { main.post(() -> { if (!closed) action.run(); }); }
    void start(String conversationId, String parentMessageId, VoiceOptions options) {
        this.conversationId = conversationId; this.parentMessageId = parentMessageId; this.options = options;
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(false).createInitializationOptions());
            Logging.enableLogToDebugOutput(Logging.Severity.LS_ERROR);
            AudioAttributes attributes = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
            audio = JavaAudioDeviceModule.builder(context).setAudioAttributes(attributes).setEnableVolumeLogger(false)
                // Keep the duplex stream clocked while the local track sends silence.
                // Avoid releasing AudioRecord during a microphone-only mute.
                .setStopRecordingOnMute(false)
                .setSamplesReadyCallback(samples -> {
                    long now = System.nanoTime();
                    if (closed || muted || !levelsVisible || now - lastLevelNanos < 100_000_000L) return;
                    lastLevelNanos = now;
                    float level = samples.getAudioFormat() == android.media.AudioFormat.ENCODING_PCM_16BIT ? AudioLevel.pcm16(samples.getData()) : 0f;
                    post(() -> { if (levelsVisible && !muted) listener.level(level); });
                })
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setAudioRecordErrorCallback(new JavaAudioDeviceModule.AudioRecordErrorCallback() {
                    public void onWebRtcAudioRecordInitError(String error) { post(() -> fail("microfono_init")); }
                    public void onWebRtcAudioRecordStartError(JavaAudioDeviceModule.AudioRecordStartErrorCode code, String error) { post(() -> fail("microfono_start")); }
                    public void onWebRtcAudioRecordError(String error) { post(() -> fail("microfono")); }
                }).setAudioTrackErrorCallback(new JavaAudioDeviceModule.AudioTrackErrorCallback() {
                    public void onWebRtcAudioTrackInitError(String error) { post(() -> fail("speaker_init")); }
                    public void onWebRtcAudioTrackStartError(JavaAudioDeviceModule.AudioTrackStartErrorCode code, String error) { post(() -> fail("speaker_start")); }
                    public void onWebRtcAudioTrackError(String error) { post(() -> fail("speaker")); }
                }).createAudioDeviceModule();
            factory = PeerConnectionFactory.builder().setAudioDeviceModule(audio)
                .setVideoEncoderFactory(new SoftwareVideoEncoderFactory()).setVideoDecoderFactory(new SoftwareVideoDecoderFactory()).createPeerConnectionFactory();
            PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(Collections.emptyList());
            config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
            config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;
            // Wear OS companion proxy exposes loopback; allow the default-route ports too.
            config.enableIceGatheringOnAnyAddressPorts = true;
            peer = factory.createPeerConnection(config, observer);
            if (peer == null) throw new IllegalStateException("peer_missing");
            DataChannel.Init init = new DataChannel.Init(); init.negotiated = true; init.id = 0;
            channel = peer.createDataChannel("", init); channel.registerObserver(dataObserver);
            source = factory.createAudioSource(new MediaConstraints());
            track = factory.createAudioTrack("voice-mic", source);
            peer.addTrack(track, List.of("voice"));
            // Protocol requires an inert video m-line; no camera capture/permission.
            peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                new RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY));
            peer.createOffer(new SdpCallbacks() {
                @Override public void onCreateSuccess(SessionDescription description) {
                    post(() -> peer.setLocalDescription(new SdpCallbacks() {
                        @Override public void onSetSuccess() { post(() -> {
                            localReady = true;
                            if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) sendOffer();
                            else main.postDelayed(() -> { if (!closed) sendOffer(); }, 2500);
                        }); }
                    }, description));
                }
            }, new MediaConstraints());
            main.postDelayed(() -> { if (!closed && !channelOpen) fail("connessione_timeout"); }, 45000);
        } catch (Exception e) { fail("rtc_" + e.getClass().getSimpleName()); }
    }
    private void sendOffer() {
        if (sentOffer || !localReady) return;
        sentOffer = true;
        String sdp = peer.getLocalDescription().description;
        Log.i("WatchVoice", "sdp_offer_ready");
        io.execute(() -> {
            try {
                String answer = client.answer(sdp, conversationId, parentMessageId, options);
                post(() -> peer.setRemoteDescription(new SdpCallbacks() {
                    @Override public void onSetSuccess() { post(() -> Log.i("WatchVoice", "sdp_answer_set")); }
                }, new SessionDescription(SessionDescription.Type.ANSWER, answer)));
            } catch (Exception e) {
                String message = e.getMessage();
                String safe = message != null && message.matches("[a-z_]+(?:[0-9]{3})?") ? message : e.getClass().getSimpleName();
                post(() -> fail(safe));
            }
        });
    }
    private final DataChannel.Observer dataObserver = new DataChannel.Observer() {
        public void onBufferedAmountChange(long amount) {}
        public void onStateChange() { post(() -> {
            Log.i("WatchVoice", "data_channel=" + channel.state());
            if (channel.state() == DataChannel.State.OPEN) { channelOpen = true; listener.state("connected"); }
            if (channel.state() == DataChannel.State.CLOSED) fail("canale_chiuso");
        }); }
        public void onMessage(DataChannel.Buffer buffer) {
            if (buffer.data.remaining() > 131072) return;
            byte[] copy = new byte[buffer.data.remaining()]; buffer.data.get(copy);
            post(() -> handleMessage(new String(copy, StandardCharsets.UTF_8)));
        }
    };
    private void handleMessage(String text) {
        try {
            JSONObject event = VoiceProtocol.event(text);
            if (event == null) return;
            String type = event.optString("type");
            if (Set.of("state_update", "conversation_update", "streaming_message_update", "full_chat_message", "chat_message_delta", "live_captioning_text", "speaking_update", "user_transcription_text", "error").contains(type) && observedTypes.add(type))
                Log.i("WatchVoice", "event_schema=" + type + ":" + schema(event, 0));
            JSONObject payload = event.optJSONObject("payload"); if (payload == null) payload = event;
            VoiceProtocol.Caption caption = liveCaptions.accept(event);
            if (caption != null) {
                if (caption.fresh) listener.caption(caption.kind + "_start", "");
                listener.caption(caption.kind, caption.text);
                Log.i("WatchVoice", "caption_received role=" + caption.kind + " boundary=" + caption.fresh + " length=" + caption.text.length());
            }
            if (type.equals("chat_message_delta") || type.equals("full_chat_message") || type.equals("live_captioning_text") || type.equals("user_transcription_text") || type.equals("streaming_message_update")) {
                String shape = type + ":" + schema(event, 0);
                if (observedSchemas.size() < 16 && observedSchemas.add(shape)) Log.i("WatchVoice", "caption_schema=" + shape);
                JSONObject delta = payload.optJSONObject("delta");
                if (delta != null) {
                    String op = delta.optString("o"), path = delta.optString("p");
                    if (Set.of("add", "append", "replace", "patch", "remove").contains(op) && Set.of("", "/", "/message", "/message/content", "/message/content/parts/0", "/content/parts/0", "/message/status", "/status").contains(path))
                        Log.i("WatchVoice", "caption_delta op=" + op + " path=" + path);
                }
            }
            if (type.equals("state_update")) {
                String next = payload.optString("new_state");
                if (Set.of("listening", "listening_intently", "idle", "connected", "thinking", "speaking", "halted").contains(next)) {
                    Log.i("WatchVoice", "voice_state=" + next); listener.state(next);
                } else listener.state("");
            } else if (type.equals("conversation_update")) {
                Log.i("WatchVoice", "conversation_schema=" + schema(event, 0));
                String id = VoiceProtocol.conversationId(event);
                if (id != null) listener.conversation(id);
            } else if (type.equals("goodbye") || type.equals("close_ready")) {
                listener.ended("Terminata", null);
            }
        } catch (Exception ignored) { Log.w("WatchVoice", "data_event_unparsed"); }
    }
    // Diagnostic schema only: keys and types, never values or dynamic message IDs.
    private static String schema(JSONObject object, int depth) {
        StringBuilder result = new StringBuilder("{");
        Iterator<String> keys = object.keys(); int count = 0;
        while (keys.hasNext() && count++ < 40) {
            String key = keys.next(); if (!SCHEMA_KEYS.contains(key)) continue;
            Object value = object.opt(key); result.append(key).append(':');
            if (value instanceof JSONObject nested) result.append(depth < 7 ? schema(nested, depth + 1) : "object");
            else if (value instanceof org.json.JSONArray array) {
                result.append("array[");
                for (int i = 0; i < Math.min(3, array.length()); i++) {
                    Object item = array.opt(i);
                    result.append(item instanceof JSONObject nested && depth < 7 ? schema(nested, depth + 1) : item instanceof String ? "string" : item instanceof Number ? "number" : "value").append(',');
                }
                result.append(']');
            }
            else if (value instanceof String) {
                result.append("string");
                if (Set.of("role", "channel", "recipient", "content_type", "status", "o", "p").contains(key) && Set.of("user", "assistant", "tool", "system", "all", "final", "analysis", "commentary", "text", "multimodal_text", "text_audio", "audio", "audio_transcription", "real_time_user_audio", "finished_successfully", "in_progress", "add", "append", "replace", "patch", "remove", "", "/", "/message", "/message/content", "/message/content/parts/0", "/message/content/parts/0/text", "/message/content/parts/0/audio/text", "/message/update_time", "/message/content/parts/0/end", "/content/parts/0", "/message/status", "/status").contains(value)) result.append('=').append(value);
            }
            else if (value instanceof Boolean) result.append("boolean").append(key.equals("is_visually_hidden_from_conversation") || key.equals("end_turn") ? "=" + value : "");
            else if (value instanceof Number) result.append("number");
            else result.append("null");
            result.append(',');
        }
        result.append('}'); return result.substring(0, Math.min(3072, result.length()));
    }
    private static final Set<String> SCHEMA_KEYS = Set.of("type", "payload", "delta", "c", "p", "o", "v", "message", "id", "message_id", "conversation_id", "parent_message_id", "response", "author", "role", "name", "metadata", "is_visually_hidden_from_conversation", "recipient", "channel", "content", "content_type", "parts", "text", "transcript", "status", "end_turn", "new_state", "old_state", "is_final", "final", "turn_id", "audio", "text_length", "start", "end", "finished", "done", "update", "messages", "create_time", "update_time", "error", "code", "reason", "details");
    private final PeerConnection.Observer observer = new PeerConnection.Observer() {
        public void onSignalingChange(PeerConnection.SignalingState state) {}
        public void onIceConnectionChange(PeerConnection.IceConnectionState state) { post(() -> {
            Log.i("WatchVoice", "ice=" + state);
            if (state == PeerConnection.IceConnectionState.FAILED) fail("rete_rtc");
        }); }
        public void onConnectionChange(PeerConnection.PeerConnectionState state) { post(() -> {
            Log.i("WatchVoice", "peer=" + state);
            if (state == PeerConnection.PeerConnectionState.FAILED) fail("rete_rtc");
        }); }
        public void onIceConnectionReceivingChange(boolean receiving) {}
        public void onIceGatheringChange(PeerConnection.IceGatheringState state) { post(() -> {
            if (state == PeerConnection.IceGatheringState.COMPLETE) sendOffer();
        }); }
        public void onIceCandidate(IceCandidate candidate) {}
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {}
        public void onAddStream(MediaStream stream) {}
        public void onRemoveStream(MediaStream stream) {}
        public void onDataChannel(DataChannel channel) {}
        public void onRenegotiationNeeded() {}
        public void onTrack(RtpTransceiver transceiver) { post(() -> {
            // Do not retain callback-owned transceiver wrappers across threads.
            for (RtpReceiver receiver : peer.getReceivers()) {
                MediaStreamTrack remote = receiver.track();
                if (remote instanceof org.webrtc.AudioTrack) {
                    remote.setEnabled(true); Log.i("WatchVoice", "remote_audio_track");
                }
            }
        }); }
    };
    private class SdpCallbacks implements SdpObserver {
        public void onCreateSuccess(SessionDescription sdp) {}
        public void onSetSuccess() {}
        public void onCreateFailure(String error) { post(() -> fail("sdp_create")); }
        public void onSetFailure(String error) { post(() -> fail("sdp_set")); }
    }
    public void setMuted(boolean muted) {
        if (closed) return;
        this.muted = muted;
        int revision = ++muteRevision;
        if (track != null) track.setEnabled(!muted);
        // In this ADM mode track mute owns the same state as setMicrophoneMute;
        // a second writer races with the native track callback on unmute.
        listener.level(0f);
        Log.i("WatchVoice", "microphone_muted=" + muted);
        logAudioCounters("mute_now");
        main.postDelayed(() -> { if (!closed && muteRevision == revision) logAudioCounters("mute_after_5s"); }, 5000);
    }
    private void logAudioCounters(String moment) {
        if (closed || peer == null) return;
        boolean mutedAtRequest = muted;
        peer.getStats(report -> {
            if (closed) return;
            long sent = 0, received = 0;
            int sendStreams = 0, receiveStreams = 0;
            for (RTCStats stat : report.getStatsMap().values()) {
                Map<String, Object> values = stat.getMembers();
                if (!"audio".equals(values.get("kind")) && !"audio".equals(values.get("mediaType"))) continue;
                if (stat.getType().equals("outbound-rtp") && values.get("packetsSent") instanceof Number) {
                    sent += ((Number) values.get("packetsSent")).longValue();
                    sendStreams++;
                }
                if (stat.getType().equals("inbound-rtp") && values.get("packetsReceived") instanceof Number) {
                    received += ((Number) values.get("packetsReceived")).longValue();
                    receiveStreams++;
                }
            }
            Log.i("WatchVoice", "audio_counters=" + moment + " muted=" + mutedAtRequest
                + " sent=" + sent + " received=" + received + " send_streams=" + sendStreams
                + " receive_streams=" + receiveStreams);
        });
    }
    void setLevelsVisible(boolean visible) { levelsVisible = visible; }
    void setOutputPaused(boolean paused) { if (!closed && audio != null) audio.setSpeakerMute(paused); }
    private void fail(String reason) { if (!closed) { String code = VoiceDiagnostics.safeCode(reason); Log.e("WatchVoice", "failure=" + code); listener.ended(VoiceState.failure(reason), code); } }
    void close() {
        if (closed) return;
        closed = true; main.removeCallbacksAndMessages(null); client.cancel(); io.shutdownNow();
        if (track != null) track.setEnabled(false);
        if (channel != null) { channel.unregisterObserver(); channel.close(); channel.dispose(); }
        if (peer != null) { peer.close(); peer.dispose(); }
        if (track != null) track.dispose();
        if (source != null) source.dispose();
        if (factory != null) factory.dispose();
        if (audio != null) audio.release();
        Log.i("WatchVoice", "rtc_released");
    }
}
