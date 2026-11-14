package local.watchvoice;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
/** In-memory per-operation server Retry-After; never schedules a request. */
final class HttpRateLimits {
    private final Map<String,Long> until=new HashMap<>();
    synchronized void record(String kind,String retryAfter,String serverDate,long wallMs,long elapsedMs){
        if(retryAfter==null)return;long delay;
        try{
            String value=retryAfter.trim();
            if(value.matches("[0-9]{1,15}"))delay=Math.multiplyExact(Long.parseLong(value),1000);
            else {long now=wallMs;if(serverDate!=null)try{now=date(serverDate);}catch(RuntimeException ignored){}delay=Math.subtractExact(date(value),now);}
            if(delay<=0)return;long end=Math.addExact(elapsedMs,delay);until.merge(kind,end,Math::max);
        }catch(RuntimeException ignored){}
    }
    private static long date(String value){return ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();}
    synchronized long remaining(String kind,long elapsedMs){long ms=until.getOrDefault(kind,elapsedMs)-elapsedMs;return ms<=0?0:1+(ms-1)/1000;}
    static String category(byte[] body){
        try{JSONObject value=new JSONObject(new String(body,StandardCharsets.UTF_8));
            if("Too many requests".equals(value.optString("detail")))return "too_many_requests";
            JSONObject error=value.optJSONObject("error");String code=error==null?"":error.optString("code");
            return Set.of("rate_limit_exceeded","too_many_requests","slow_down","insufficient_quota").contains(code)?code:"unknown";
        }catch(Exception ignored){return "non_json";}
    }
}
