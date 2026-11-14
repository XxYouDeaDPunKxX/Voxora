package local.watchvoice;
import android.content.Context;
import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import static local.watchvoice.DictationState.*;
final class DictationView {
    static final class Panels {
        private long generation=-1;
        private final boolean[] expanded=new boolean[2];
        private final int[] cursor=new int[2];
        void generation(long value){if(generation==value)return;generation=value;Arrays.fill(expanded,false);Arrays.fill(cursor,0);}
        void open(boolean output){expanded[output?1:0]=true;}
        void close(boolean output){expanded[output?1:0]=false;cursor[output?1:0]=0;}
        boolean expanded(boolean output){return expanded[output?1:0];}
        int cursor(boolean output){return cursor[output?1:0];}
        void cursor(boolean output,int value){cursor[output?1:0]=value;}
    }
    static String primary(Snapshot value,ReadAloudPlayer.Snapshot playback){return switch(value.phase()){
        case READY->"RECORD";case RECORDING,CAPTURED->"FINISH";case DRAFT->"SEND";case UNCERTAIN->"VERIFY";
        case AUDIO->"playing".equals(playback.phase())?"PAUSE":"PLAY";
        case ERROR->value.assistantId()!=null?"RETRY_AUDIO":value.userId()==null?"RETRY_TRANSCRIPTION":"";
        default->"";
    };}
    interface CommandSink {void command(String command,float speed);}
    static String primaryLabel(Snapshot value,ReadAloudPlayer.Snapshot playback){return value.phase()==Phase.CAPTURED?"Trascrivi":caption(primary(value,playback));}
    static boolean shouldBindReply(Snapshot value,long alreadyHandled){return value.generation()!=alreadyHandled&&value.assistantId()!=null&&value.conversationId()!=null;}
    private static String caption(String command){return switch(command){case "RECORD"->"Registra";case "FINISH"->"Fine";case "SEND"->"Invia";case "VERIFY"->"Controlla invio";case "PAUSE"->"Pausa";case "PLAY"->"Riprendi";case "RETRY_AUDIO"->"Riprova lettura";case "RETRY_TRANSCRIPTION"->"Riprova trascrizione";default->command;};}
}
