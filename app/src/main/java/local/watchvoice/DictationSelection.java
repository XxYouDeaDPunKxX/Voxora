package local.watchvoice;

import java.io.IOException;
import org.json.*;

/** Fresh account defaults for every send, never conversation metadata or catalog order. */
final class DictationSelection {
    record Selection(String model,String effort) {}

    static Selection resolve(JSONObject catalog,JSONObject preferences)throws IOException {
        try {
            JSONObject settings=preferences==null?null:preferences.getJSONObject("settings");
            JSONObject last=settings==null?null:settings.optJSONObject("last_used_model_config");
            if(settings==null)throw new IOException("model_preferences_missing");
            JSONObject defaults=settings.optJSONObject("default_model_config");
            Object configured=defaults==null?null:defaults.opt("default_model_slug");
            String model=configured!=null&&configured!=JSONObject.NULL?required(configured):required(last==null||last.optJSONObject("slugs")==null?null:last.getJSONObject("slugs").opt("default"));
            JSONArray models=catalog.getJSONArray("models");JSONObject selected=null;
            for(int i=0;i<models.length();i++){
                JSONObject candidate=models.getJSONObject(i);
                if(model.equals(candidate.optString("slug"))){if(selected!=null)throw new IOException("model_configuration_invalid");selected=candidate;}
            }
            if(selected==null||selected.optBoolean("is_work_mode_model"))throw new IOException("model_configuration_unavailable");
            if(!(selected.opt("configurable_thinking_effort") instanceof Boolean))throw new IOException("model_configuration_invalid");
            if(!selected.getBoolean("configurable_thinking_effort"))return new Selection(model,null);
            JSONObject juices=last==null?null:last.optJSONObject("juices");
            JSONObject shared=juices==null?null:juices.optJSONObject("default");
            String effort=required(shared==null?null:shared.opt(model));
            JSONArray efforts=selected.getJSONArray("thinking_efforts");
            for(int i=0;i<efforts.length();i++)if(effort.equals(efforts.getJSONObject(i).opt("thinking_effort")))return new Selection(model,effort);
            throw new IOException("model_effort_unavailable");
        }catch(IOException error){throw error;}catch(Exception invalid){throw new IOException("model_configuration_invalid");}
    }

    private static String required(Object value)throws IOException {
        if(!(value instanceof String s)||!s.matches("[a-zA-Z0-9_.-]{1,100}"))throw new IOException("model_preferences_missing");
        return (String)value;
    }

}
