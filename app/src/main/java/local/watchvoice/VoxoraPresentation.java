package local.watchvoice;
import static local.watchvoice.DictationState.*;

/** Presentation only: never owns a request, recording or audio session. */
final class VoxoraPresentation {
    enum Change { DIRECT, CONFIRM, BLOCK }
    static boolean sameExistingChat(String first,String second){return first!=null&&first.equals(second);}
    static String changeConsequence(boolean voice,Snapshot d){
        if(voice)return "La chiamata corrente verrà terminata.";
        if(d.assistantId()!=null)return "Audio e testi temporanei verranno rimossi dal Watch. I messaggi rimangono in ChatGPT.";
        return "Il tentativo locale non inviato verrà scartato.";
    }
    static String output(boolean voice,boolean media,String voiceRoute,String mediaRoute){
        String result=voice?voiceRoute:media?mediaRoute:"";
        return result==null||result.isEmpty()?"Non confermata":result;
    }
    static String boundChat(String selected,String requested,String confirmed,boolean running){
        return running&&confirmed!=null&&java.util.Objects.equals(selected,requested)?confirmed:selected;
    }
    static Change change(boolean voiceRunning,Snapshot s){
        if(s.phase()==Phase.SENDING||s.phase()==Phase.WAITING||s.phase()==Phase.UNCERTAIN)return Change.BLOCK;
        if(voiceRunning)return Change.CONFIRM;
        return s.phase()==Phase.READY?Change.DIRECT:Change.CONFIRM;
    }
    static boolean canApplyChange(boolean voiceRunning,boolean dictationRunning){return !voiceRunning&&!dictationRunning;}
    static String voiceStatus(VoiceState s){
        if(!s.phase.equals("connected"))return switch(s.phase){case "resuming"->"Recupero conversazione…";case "network","wifi"->"Preparazione rete…";default->"Connessione in corso…";};
        return switch(s.serverState){case "speaking"->"Risposta vocale";case "thinking"->"Elaborazione risposta";case "listening","listening_intently"->s.muted?"Microfono disattivato":"In ascolto";default->"Chiamata connessa";};
    }
    static String primary(Snapshot s,ReadAloudPlayer.Snapshot p){return DictationView.primary(s,p);}
    static String primaryLabel(Snapshot s,ReadAloudPlayer.Snapshot p){
        if(s.phase()==Phase.AUDIO&&!p.error().isEmpty())return "Riprova";
        if(s.phase()==Phase.AUDIO)return p.phase().equals("playing")?"Pausa":p.phase().equals("finished")?"Riascolta":"Riprendi";
        return switch(s.phase()){case READY->"Registra";case RECORDING->"Termina";case CAPTURED->"Trascrivi";case DRAFT->"Invia";case UNCERTAIN->"Controlla";case ERROR->s.assistantId()!=null?"Riprova audio":"Riprova";default->"Attendi";};
    }
    static String dictationStatus(Snapshot s,ReadAloudPlayer.Snapshot p){if(s.phase()==Phase.AUDIO&&!p.error().isEmpty())return "Lettura non disponibile";return switch(s.phase()){
        case READY->"Pronto a registrare";case RECORDING->"Registrazione in corso";case CAPTURED->"Limite registrazione raggiunto";
        case TRANSCRIBING->"Trascrizione in corso";case DRAFT->s.error().isEmpty()?"Bozza non inviata":"Messaggio non inviato";
        case SENDING->"Invio in corso";case WAITING->"Attesa della risposta";case PREPARING_AUDIO->"Preparazione audio";
        case UNCERTAIN->"Invio da controllare";case ERROR->s.assistantId()!=null?"Lettura non disponibile":"Operazione non riuscita";
        case AUDIO->switch(p.phase()){case "playing"->"Lettura in corso";case "paused"->"In pausa";case "stopped"->"Lettura fermata";case "finished"->"Lettura terminata";default->"Audio in caricamento";};
    };}
    static String tag(boolean voice,VoiceState v,Snapshot s,ReadAloudPlayer.Snapshot p){
        if(voice)return v.muted?"Mic spento":!v.phase.equals("connected")?"Collega":v.serverState.equals("speaking")?"Parla":v.serverState.equals("thinking")?"Elabora":"Voice";
        if(s.phase()==Phase.AUDIO&&!p.error().isEmpty())return "Errore";
        return switch(s.phase()){case READY->"";case RECORDING->"Registra";case CAPTURED->"Cattura";case TRANSCRIBING->"Trascrive";case DRAFT->"Bozza";case SENDING->"Invia";case WAITING->"Attesa";case PREPARING_AUDIO->"Carica";case UNCERTAIN->"Controlla";case ERROR->"Errore";case AUDIO->p.phase().equals("playing")?"Audio":p.phase().equals("finished")?"Fine":"Pausa";};
    }
    static boolean advancing(boolean voice,VoiceState v,Snapshot s,ReadAloudPlayer.Snapshot p){
        if(voice)return true;
        return switch(s.phase()){case RECORDING,TRANSCRIBING,SENDING,WAITING,PREPARING_AUDIO->true;case AUDIO->p.phase().equals("playing");default->false;};
    }
}
