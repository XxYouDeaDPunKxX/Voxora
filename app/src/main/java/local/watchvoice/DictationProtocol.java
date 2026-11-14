package local.watchvoice;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static local.watchvoice.DictationState.*;

/** Private protocol shapes observed by the native Watch probe; unknown controls stop sends. */
final class DictationProtocol {
    enum Decision {READY,BLOCKED,UNKNOWN}
    record Stream(String conversationId,String assistantId) {}
    static boolean fresh(JSONObject value,long acquiredNanos,long nowNanos,long nowMillis){
        if(requirements(value)!=Decision.READY || nowNanos<acquiredNanos)return false;
        if(value.has("expire_after") && (nowNanos-acquiredNanos)/1000000000.0>=value.optDouble("expire_after",0))return false;
        if(value.has("expire_at")){
            double at=value.optDouble("expire_at",0);double millis=at>=1000000000000.0?at:at*1000.0;
            if(!Double.isFinite(millis)||millis<=nowMillis)return false;
        }
        return true;
    }
    static Decision requirements(JSONObject value){
        Set<String> allowed=Set.of("token","persona","expire_after","expire_at","proofofwork","turnstile","arkose","so");
        if(value==null || unknown(value,allowed) || !(value.opt("token") instanceof String) || value.optString("token").trim().isEmpty())return Decision.UNKNOWN;
        if(value.has("persona") && !(value.opt("persona") instanceof String))return Decision.UNKNOWN;
        boolean ttl=false;
        for(String key:new String[]{"expire_after","expire_at"})if(value.has(key)){Object n=value.opt(key);if(!(n instanceof Number)||!Double.isFinite(((Number)n).doubleValue())||((Number)n).doubleValue()<=0)return Decision.UNKNOWN;ttl=true;}
        if(!ttl)return Decision.UNKNOWN;boolean blocked=false;
        for(String key:new String[]{"proofofwork","turnstile","arkose","so"}){
            if(!value.has(key))continue;JSONObject control=value.optJSONObject(key);
            if(control==null || unknown(control,Set.of("required","seed","difficulty")) || !(control.opt("required") instanceof Boolean))return Decision.UNKNOWN;
            if(control.has("seed") && !(control.opt("seed") instanceof String))return Decision.UNKNOWN;
            if(control.has("difficulty")){Object d=control.opt("difficulty");if(!(d instanceof String&&!((String)d).trim().isEmpty()) && !(d instanceof Number&&Double.isFinite(((Number)d).doubleValue())))return Decision.UNKNOWN;}
            blocked|=control.optBoolean("required");
        }
        return blocked?Decision.BLOCKED:Decision.READY;
    }
    private static boolean unknown(JSONObject value,Set<String> allowed){for(Iterator<String> keys=value.keys();keys.hasNext();)if(!allowed.contains(keys.next()))return true;return false;}
    static String uuid(String value)throws IOException{if(value==null||!value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))throw new IOException("identity_invalid");return value;}
    static JSONObject body(String user,String parent,String conversation,String model,String text,TimeZone zone,long now)throws Exception{
        uuid(user);uuid(parent);if(conversation!=null)uuid(conversation);
        if(model==null || !model.matches("[a-zA-Z0-9_.-]{1,100}") || text==null || text.trim().isEmpty() || text.getBytes(StandardCharsets.UTF_8).length>1048576)throw new IOException("send_payload");
        JSONObject metadata=new JSONObject().put("model_slug",model).put("default_model_slug",model).put("is_visually_hidden_from_conversation",false).put("exclude_after_next_user_message",false)
            .put("content_references",new JSONArray()).put("search_result_groups",new JSONArray()).put("search_queries",new JSONArray()).put("image_results",new JSONArray()).put("real_time_audio_has_video",false)
            .put("system_hints",new JSONArray()).put("dictation",false).put("voice_mode_message",false).put("image_gen_async",false).put("trigger_async_ux",false).put("writing_blocks",new JSONObject());
        JSONObject message=new JSONObject().put("id",user).put("author",new JSONObject().put("role","user")).put("content",new JSONObject().put("parts",new JSONArray().put(text)).put("content_type","text"))
            .put("status","finished_successfully").put("recipient","all").put("metadata",metadata);
        JSONObject body=new JSONObject().put("action","next").put("parent_message_id",parent).put("messages",new JSONArray().put(message)).put("model",model).put("history_and_training_disabled",false)
            .put("fork_from_shared_post",false).put("enable_message_followups",true).put("force_use_sse",true).put("force_paragen",false)
            .put("supported_encodings",new JSONArray().put("v1")).put("supports_buffering",true).put("timezone",zone.getID()).put("timezone_offset_min",-zone.getOffset(now)/60000).put("system_hints",new JSONArray())
            .put("is_onboarding_conversation",false).put("no_auth_ad_preferences",new JSONObject().put("personalization_enabled",true).put("history_enabled",true)).put("client_prepare_state","none").put("stream",true);
        if(conversation!=null)body.put("conversation_id",conversation);return body;
    }
    static Stream consume(InputStream input,Cancellation cancellation)throws Exception{
        BufferedInputStream buffered=new BufferedInputStream(input,32768);String event="",conversation="",assistant="";StringBuilder data=new StringBuilder();int total=0;boolean complete=false;
        while(!complete){
            ByteArrayOutputStream line=new ByteArrayOutputStream();int b;
            while((b=buffered.read())!=-1){if(++total>16*1024*1024 || line.size()>1048576)throw new IOException("stream_size");if((total&4095)==0)cancellation.check();if(b=='\n')break;line.write(b);}
            if(b==-1&&line.size()==0){if(data.length()==0)break;}
            String value=new String(line.toByteArray(),StandardCharsets.UTF_8);if(value.endsWith("\r"))value=value.substring(0,value.length()-1);
            if(value.isEmpty()||b==-1){
                if(data.length()>0){String payload=data.toString().trim();
                    if("error".equals(event))throw new IOException("stream_error");
                    if("delta_encoding".equals(event)){if(!"\"v1\"".equals(payload))throw new IOException("encoding_unknown");}
                    else if("[DONE]".equals(payload))complete=true;
                    else {JSONObject object;try{object=new JSONObject(payload);}catch(JSONException invalid){throw new IOException("stream_schema");}
                        if(object.has("error")||"error".equals(object.optString("type")))throw new IOException("stream_error");
                        for(JSONObject current:new JSONObject[]{object,object.optJSONObject("v")}){if(current==null)continue;
                            if(current.has("conversation_id")){String id=uuid(current.getString("conversation_id"));if(!conversation.isEmpty()&&!conversation.equals(id))throw new IOException("stream_identity");conversation=id;}
                            JSONObject message=current.optJSONObject("message");if(message!=null&&message.optJSONObject("author")!=null&&"assistant".equals(message.getJSONObject("author").optString("role"))&&message.has("id"))assistant=uuid(message.getString("id"));
                        }
                        if("message_stream_complete".equals(object.optString("type")))complete=true;
                    }
                }
                event="";data.setLength(0);if(b==-1)break;
            }else if(value.startsWith("event:"))event=value.substring(6).trim();
            else if(value.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(value.substring(5).stripLeading());if(data.length()>1048576)throw new IOException("event_size");}
        }
        cancellation.check();if(!complete)throw new IOException("stream_incomplete");return new Stream(uuid(conversation),uuid(assistant));
    }
    static String findReply(JSONObject graph,String user)throws IOException {
        try {
            uuid(user);JSONObject mapping=graph.getJSONObject("mapping");String cursor=uuid(graph.getString("current_node")),candidate=null;Set<String> seen=new HashSet<>();
            while(cursor!=null){
                if(!seen.add(cursor)||seen.size()>4096)throw new IOException("graph_cycle");
                if(cursor.equals(user)){if(candidate==null)throw new IOException("reply_not_ready");verifyReply(graph,user,candidate);return candidate;}
                JSONObject node=mapping.getJSONObject(cursor),message=node.optJSONObject("message");
                if(message!=null && "assistant".equals(message.getJSONObject("author").optString("role")) && "final".equals(message.optString("channel")) && "finished_successfully".equals(message.optString("status")))candidate=uuid(cursor);
                if(!node.has("parent"))throw new IOException("parent_missing");cursor=node.isNull("parent")?null:uuid(node.getString("parent"));
            }
            throw new IOException("reply_other_branch");
        }catch(IOException error){throw error;}catch(Exception error){throw new IOException("graph_schema");}
    }
    static VerifiedReply verifyReply(JSONObject graph,String user,String assistant)throws IOException{
        try{
            uuid(user);uuid(assistant);String conversation=uuid(graph.getString("conversation_id"));JSONObject mapping=graph.getJSONObject("mapping");
            if(mapping.length()>4096)throw new IOException("graph_size");
            JSONObject node=mapping.getJSONObject(assistant),message=node.getJSONObject("message");
            if(!assistant.equals(message.getString("id")) || !"assistant".equals(message.getJSONObject("author").getString("role")) || !"final".equals(message.optString("channel"))
                || !"all".equals(message.optString("recipient")) || !"finished_successfully".equals(message.optString("status")))throw new IOException("reply_unfinished");
            String cursor=assistant;Set<String> seen=new HashSet<>();boolean found=false;
            while(cursor!=null){
                uuid(cursor);if(!seen.add(cursor)||seen.size()>4096)throw new IOException("graph_cycle");
                JSONObject current=mapping.getJSONObject(cursor),item=current.optJSONObject("message");
                if(item!=null&&!cursor.equals(item.getString("id")))throw new IOException("graph_identity");
                if(!current.has("parent"))throw new IOException("parent_missing");
                if(cursor.equals(user)){if(item==null||!"user".equals(item.getJSONObject("author").getString("role")))throw new IOException("user_identity");found=true;break;}
                cursor=current.isNull("parent")?null:current.getString("parent");
            }
            if(!found)throw new IOException("reply_other_branch");
            JSONObject content=message.getJSONObject("content");if(!"text".equals(content.getString("content_type")))throw new IOException("reply_text_schema");
            JSONArray parts=content.getJSONArray("parts");StringBuilder text=new StringBuilder();
            for(int i=0;i<parts.length();i++){if(!(parts.get(i) instanceof String))throw new IOException("reply_text_schema");if(i>0)text.append('\n');text.append(parts.getString(i));if(text.length()>1048576)throw new IOException("reply_size");}
            if(text.toString().getBytes(StandardCharsets.UTF_8).length>1048576)throw new IOException("reply_size");return new VerifiedReply(conversation,user,assistant,text.toString());
        }catch(IOException error){throw error;}catch(Exception error){throw new IOException("graph_schema");}
    }
}
