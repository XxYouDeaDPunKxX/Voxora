package local.watchvoice;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static local.watchvoice.DictationState.*;

/** Bounded authenticated HTTP; no body logging, automatic POST retries, or browser bridge. */
final class DictationClient {
    static final String WEB="https://chatgpt.com",ANDROID="https://android.chat.openai.com";
    interface Authentication {JSONObject load(long deadlineNanos)throws Exception;}
    interface Connections {HttpsURLConnection open(URL url)throws IOException;}
    private interface Response<T>{T read(HttpsURLConnection connection,InputStream input)throws Exception;}
    private interface Writer{void write(OutputStream output)throws Exception;}
    static final class Failure extends IOException {
        final String code;final boolean sendPossible;
        Failure(String code,boolean sendPossible){super(code);this.code=code;this.sendPossible=sendPossible;}
    }
    private final Authentication authentication;
    private final DictationTurnStore journal;
    private final Connections connections;
    private final ChatGptClient session;
    private volatile HttpsURLConnection active;
    private volatile boolean cancelled;
    DictationClient(CredentialStore store,DictationTurnStore journal){session=new ChatGptClient(store);authentication=session::authenticateForOperation;this.journal=journal;connections=url->(HttpsURLConnection)url.openConnection();}
    DictationClient(Authentication authentication,DictationTurnStore journal,Connections connections){this.authentication=authentication;this.journal=journal;this.connections=connections;session=null;}
    void cancel(){cancelled=true;if(session!=null)session.cancel();HttpsURLConnection connection=active;if(connection!=null){Thread thread=new Thread(connection::disconnect,"Voxora-dictation-cancel");thread.setDaemon(true);thread.start();}}
    private void check(Cancellation external)throws IOException{if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("cancelled");external.check();}
    private JSONObject auth(int seconds,Cancellation external)throws Exception{check(external);JSONObject result=authentication.load(System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds));check(external);return result;}
    private <T>T request(String origin,String path,String method,JSONObject auth,String contentType,long length,Writer writer,String requirements,boolean sse,int seconds,Cancellation external,Response<T> response)throws Exception{
        if(!WEB.equals(origin)&&!ANDROID.equals(origin))throw new IOException("host_invalid");check(external);
        HttpsURLConnection connection=connections.open(new URL(origin+path));active=connection;
        ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"Voxora-dictation-deadline");t.setDaemon(true);return t;});
        Future<?> deadline=timer.schedule(connection::disconnect,seconds,TimeUnit.SECONDS);
        try {
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(60000);connection.setRequestMethod(method);
            connection.setRequestProperty("User-Agent",ANDROID.equals(origin)?"ChatGPT/1.2026.223 (Android 16; sdk_gphone64_arm64; build 2622307)":VoiceProtocol.UA);
            connection.setRequestProperty("Authorization","Bearer "+VoiceProtocol.headerValue(auth.getString("accessToken")));
            connection.setRequestProperty("OAI-Device-Id",VoiceProtocol.headerValue(auth.getString("deviceId")));
            if(!auth.optString("accountId").isEmpty())connection.setRequestProperty("ChatGPT-Account-ID",VoiceProtocol.headerValue(auth.getString("accountId")));
            connection.setRequestProperty("OAI-Language","it-IT");connection.setRequestProperty("Accept-Language","it-IT,it;q=0.9");
            connection.setRequestProperty("Accept",sse?"text/event-stream, application/json":"application/json, audio/aac");
            connection.setRequestProperty("X-OpenAI-Target-Path",path.split("\\?",2)[0]);
            if(ANDROID.equals(origin)){connection.setRequestProperty("OAI-Package-Name","com.openai.chatgpt");connection.setRequestProperty("OAI-Client-Type","android");connection.setRequestProperty("ChatGPT-Residency-Region","no_constraint");connection.setRequestProperty("X-Device-Tier","lower_mid");}
            if(requirements!=null)connection.setRequestProperty("openai-sentinel-chat-requirements-token",VoiceProtocol.headerValue(requirements));
            if(sse){String id=UUID.randomUUID().toString();connection.setRequestProperty("oai-session-id",id);connection.setRequestProperty("x-oai-convo-session-id",id);connection.setRequestProperty("x-oai-turn-trace-id",UUID.randomUUID().toString());connection.setRequestProperty("Cache-Control","no-cache");}
            if(writer!=null){connection.setDoOutput(true);connection.setRequestProperty("Content-Type",contentType);connection.setFixedLengthStreamingMode(length);try(OutputStream output=connection.getOutputStream()){check(external);writer.write(output);}}
            check(external);int status=connection.getResponseCode();
            if(status!=200)throw new IOException("http_"+status);
            if("challenge".equals(connection.getHeaderField("cf-mitigated")))throw new IOException("edge_challenge");
            try(InputStream source=connection.getInputStream()){
                InputStream guarded=new FilterInputStream(source){public int read()throws IOException{check(external);int n=super.read();check(external);return n;}public int read(byte[] b,int off,int len)throws IOException{check(external);int n=super.read(b,off,len);check(external);return n;}};
                return response.read(connection,guarded);
            }
        }finally{deadline.cancel(false);timer.shutdownNow();connection.disconnect();if(active==connection)active=null;}
    }
    private JSONObject json(String origin,String path,String method,JSONObject auth,JSONObject body,int limit,Cancellation cancel)throws Exception{
        byte[] bytes=body==null?null:body.toString().getBytes(StandardCharsets.UTF_8);
        return request(origin,path,method,auth,"application/json",bytes==null?0:bytes.length,bytes==null?null:out->out.write(bytes),null,false,120,cancel,(connection,input)->{
            if(!mime(connection).equals("application/json"))throw new IOException("json_mime");return new JSONObject(new String(CredentialStore.readLimited(input,limit),StandardCharsets.UTF_8));});
    }
    String transcribe(Recording recording,String language,Destination destination,Cancellation cancel)throws Exception{
        try {
            if(recording==null||!recording.file().isFile()||recording.file().length()>24L*1024*1024||recording.file().length()<44)throw new IOException("recording_size");
            if(language==null||!language.matches("[a-z]{2}(-[A-Z]{2})?"))throw new IOException("language_invalid");
            JSONObject auth=auth(180,cancel);String boundary="Voxora"+UUID.randomUUID().toString().replace("-","");
            byte[] prefix=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            StringBuilder fields=new StringBuilder("\r\n");
            Map<String,String> values=new LinkedHashMap<>();values.put("language",language.substring(0,2));values.put("dictation_session_id",UUID.randomUUID().toString());values.put("attempt_id",UUID.randomUUID().toString());
            if(recording.durationMs()<0)throw new IOException("recording_duration");values.put("duration_ms",Long.toString(recording.durationMs()));
            if(destination.id()!=null){
                JSONObject graph=json(WEB,"/backend-api/conversation/"+DictationProtocol.uuid(destination.id()),"GET",auth,null,8*1024*1024,cancel);
                if(!destination.id().equals(graph.optString("conversation_id")))throw new IOException("chat_identity");
                values.put("conversation_id",destination.id());values.put("parent_message_id",DictationProtocol.uuid(graph.getString("current_node")));
            }
            for(var entry:values.entrySet())fields.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(entry.getKey()).append("\"\r\n\r\n").append(entry.getValue()).append("\r\n");
            fields.append("--").append(boundary).append("--\r\n");byte[] suffix=fields.toString().getBytes(StandardCharsets.UTF_8);
            String text=request(WEB,"/backend-api/transcribe","POST",auth,"multipart/form-data; boundary="+boundary,prefix.length+recording.file().length()+suffix.length,out->{out.write(prefix);try(FileInputStream in=new FileInputStream(recording.file())){byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){check(cancel);out.write(buffer,0,n);}}out.write(suffix);},null,false,180,cancel,(connection,input)->{
                if(!mime(connection).equals("application/json"))throw new IOException("transcription_mime");JSONObject value=new JSONObject(new String(CredentialStore.readLimited(input,2*1024*1024),StandardCharsets.UTF_8));
                if(!(value.opt("text") instanceof String))throw new IOException("transcription_schema");return value.getString("text");});
            if(text.trim().isEmpty()||text.getBytes(StandardCharsets.UTF_8).length>1048576)throw new IOException("transcription_size_or_empty");return text;
        }catch(Exception error){throw failure(error,false);}
    }
    VerifiedReply send(String text,Destination destination,String user,Cancellation cancel)throws Exception{
        boolean claimed=false;
        try {
            if(journal.read()!=null)throw new Failure("send_unresolved",true);
            DictationProtocol.uuid(user);JSONObject auth=auth(120,cancel);String parent=UUID.randomUUID().toString();
            JSONObject graph=null;
            if(destination.id()!=null){graph=json(WEB,"/backend-api/conversation/"+DictationProtocol.uuid(destination.id()),"GET",auth,null,8*1024*1024,cancel);
                if(!destination.id().equals(graph.optString("conversation_id")))throw new IOException("chat_identity");parent=DictationProtocol.uuid(graph.getString("current_node"));}
            JSONObject models=json(ANDROID,"/backend-api/models?iim=false&supports_model_picker_upgrade_presets=true","GET",auth,null,2*1024*1024,cancel);
            JSONObject settings=json(WEB,"/backend-api/settings/user","GET",auth,null,2*1024*1024,cancel);
            DictationSelection.Selection selection=DictationSelection.resolve(models,settings);
            long requirementsAt=System.nanoTime();JSONObject requirements=json(ANDROID,"/backend-api/sentinel/chat-requirements","POST",auth,new JSONObject(),131072,cancel);
            if(DictationProtocol.requirements(requirements)!=DictationProtocol.Decision.READY)throw new IOException("requirements_not_ready");
            JSONObject payload=DictationProtocol.body(user,parent,destination.id(),selection.model(),text,TimeZone.getDefault(),System.currentTimeMillis());
            if(selection.effort()!=null)payload.put("thinking_effort",selection.effort());
            byte[] body=payload.toString().getBytes(StandardCharsets.UTF_8);
            check(cancel);if(!DictationProtocol.fresh(requirements,requirementsAt,System.nanoTime(),System.currentTimeMillis()))throw new IOException("requirements_expired");
            if(!journal.claim(new SendRecord(user,destination.id(),null,"pending")))throw new Failure("send_unresolved",true);claimed=true;
            if(!DictationProtocol.fresh(requirements,requirementsAt,System.nanoTime(),System.currentTimeMillis()))throw new IOException("requirements_expired");
            DictationProtocol.Stream stream=request(ANDROID,"/backend-api/f/conversation","POST",auth,"application/json",body.length,out->{if(!DictationProtocol.fresh(requirements,requirementsAt,System.nanoTime(),System.currentTimeMillis()))throw new IOException("requirements_expired");out.write(body);},requirements.getString("token"),true,600,cancel,(connection,input)->{
                if(!mime(connection).equals("text/event-stream"))throw new IOException("send_mime");return DictationProtocol.consume(input,()->check(cancel));});
            if(destination.id()!=null&&!destination.id().equals(stream.conversationId()))throw new IOException("chat_identity");
            SendRecord known=new SendRecord(user,stream.conversationId(),stream.assistantId(),"uncertain");journal.update(known);
            VerifiedReply reply=readReply(known,auth,cancel);journal.update(new SendRecord(user,reply.conversationId(),reply.assistantId(),"verified"));return reply;
        }catch(Exception error){if(claimed){SendRecord current=journal.read();if(current!=null&&current.userId()!=null)try{journal.update(new SendRecord(current.userId(),current.conversationId(),current.assistantId(),"uncertain"));}catch(IOException ignored){}}
            throw failure(error,claimed);}
    }
    VerifiedReply verify(SendRecord record,Cancellation cancel)throws Exception{try{VerifiedReply reply=readReply(record,auth(120,cancel),cancel);journal.update(new SendRecord(reply.userId(),reply.conversationId(),reply.assistantId(),"verified"));return reply;}catch(Exception error){throw failure(error,true);}}
    private VerifiedReply readReply(SendRecord record,JSONObject auth,Cancellation cancel)throws Exception{
        if(record==null||record.conversationId()==null||record.userId()==null)throw new IOException("send_identity_missing");
        JSONObject graph=json(WEB,"/backend-api/conversation/"+DictationProtocol.uuid(record.conversationId()),"GET",auth,null,8*1024*1024,cancel);
        if(!record.conversationId().equals(graph.optString("conversation_id")))throw new IOException("chat_identity");
        String assistant=record.assistantId()==null?DictationProtocol.findReply(graph,record.userId()):record.assistantId();
        return DictationProtocol.verifyReply(graph,record.userId(),assistant);
    }
    void download(VerifiedReply reply,String voice,File target,Cancellation cancel,Consumer<AudioProgress> progress)throws Exception{
        try {
            if(voice==null||!voice.matches("[a-z]{1,32}"))throw new IOException("voice_invalid");JSONObject auth=auth(120,cancel);
            String path="/backend-api/synthesize?conversation_id="+DictationProtocol.uuid(reply.conversationId())+"&message_id="+DictationProtocol.uuid(reply.assistantId())+"&voice="+voice+"&format=aac";
            request(WEB,path,"GET",auth,null,0,null,null,false,600,cancel,(connection,input)->{
                if(!mime(connection).equals("audio/aac"))throw new IOException("audio_mime");long bytes=0,first=0;int first0=-1,first1=-1;byte[] buffer=new byte[32768];int n;
                long contentLength=connection.getContentLengthLong();if(contentLength>64L*1024*1024)throw new IOException("audio_size");
                try(FileOutputStream output=new FileOutputStream(target)){while((n=input.read(buffer))!=-1){if(n==0)continue;if(bytes+n>64L*1024*1024)throw new IOException("audio_size");
                        if(first0<0)first0=buffer[0]&255;if(first1<0){if(bytes==1)first1=buffer[0]&255;else if(n>1)first1=buffer[1]&255;}
                        if(first==0)first=android.os.SystemClock.elapsedRealtime();output.write(buffer,0,n);output.flush();bytes+=n;progress.accept(new AudioProgress(bytes,false,first,0));}
                    output.getFD().sync();}
                if(bytes<32||(contentLength>=0&&contentLength!=bytes)||first0!=255||(first1&246)!=240)throw new IOException("audio_incomplete");
                progress.accept(new AudioProgress(bytes,true,first,android.os.SystemClock.elapsedRealtime()));return null;
            });
        }catch(Exception error){throw failure(error,false);}
    }
    private static String mime(HttpsURLConnection connection){String type=connection.getContentType();return type==null?"":type.split(";",2)[0].trim().toLowerCase(Locale.ROOT);}
    private static Failure failure(Exception error,boolean possible){if(error instanceof Failure)return (Failure)error;String code=error instanceof IOException?error.getMessage():"protocol_error";if(code==null||!code.matches("[a-zA-Z0-9_]{1,80}"))code="transport_error";return new Failure(code,possible);}
}
