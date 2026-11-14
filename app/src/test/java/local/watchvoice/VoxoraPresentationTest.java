package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
import static local.watchvoice.DictationState.*;
public class VoxoraPresentationTest {
    @Test public void playerFailureIsNotPresentedAsBufferingOrPaused(){
        ReadAloudPlayer.Snapshot failed=new ReadAloudPlayer.Snapshot("error",0,10000,1,true,"audio_decoder");
        assertEquals("Lettura non disponibile",VoxoraPresentation.dictationStatus(state(Phase.AUDIO),failed));
        assertEquals("Errore",VoxoraPresentation.tag(false,null,state(Phase.AUDIO),failed));
        assertEquals("Riprova",VoxoraPresentation.primaryLabel(state(Phase.AUDIO),failed));
    }
    @Test public void newChatIsAnActionEvenWhenCurrentDestinationIsStillNew(){
        assertFalse(VoxoraPresentation.sameExistingChat(null,null));
        assertFalse(VoxoraPresentation.sameExistingChat(null,"old"));
        assertTrue(VoxoraPresentation.sameExistingChat("old","old"));
    }
    @Test public void audioFailureConfirmationNeverClaimsMessageWasUnsent(){
        Snapshot reply=new Snapshot(4,Phase.ERROR,new Destination("chat","Titolo"),"input","reply","chat","user","assistant","audio_failed");
        assertTrue(VoxoraPresentation.changeConsequence(false,reply).contains("ChatGPT"));
        assertFalse(VoxoraPresentation.changeConsequence(false,reply).contains("non inviato"));
        assertTrue(VoxoraPresentation.changeConsequence(false,state(Phase.DRAFT)).contains("non inviato"));
    }
    @Test public void mediaOutputNeverFallsBackToCommunicationRoute(){
        assertEquals("Auricolari",VoxoraPresentation.output(false,true,"Speaker","Auricolari"));
        assertEquals("Non confermata",VoxoraPresentation.output(false,true,"Speaker",""));
        assertEquals("Speaker",VoxoraPresentation.output(true,false,"Speaker",""));
    }
    @Test public void confirmedNewVoiceChatKeepsIdentityWithoutOverwritingLaterSelection(){
        assertEquals("confirmed",VoxoraPresentation.boundChat(null,null,"confirmed",true));
        assertEquals("other",VoxoraPresentation.boundChat("other",null,"confirmed",true));
        assertNull(VoxoraPresentation.boundChat(null,null,"confirmed",false));
        assertEquals("old",VoxoraPresentation.boundChat("old","old",null,true));
    }
    private Snapshot state(Phase p){return new Snapshot(4,p,new Destination("chat","Titolo"),"bozza","risposta","chat",null,null,"");}
    @Test public void sendAndUncertainCannotBeAbandoned(){
        for(Phase p:new Phase[]{Phase.SENDING,Phase.WAITING,Phase.UNCERTAIN})
            assertEquals(VoxoraPresentation.Change.BLOCK,VoxoraPresentation.change(false,state(p)));
    }
    @Test public void contentsRequireConfirmationButIdleDoesNot(){
        assertEquals(VoxoraPresentation.Change.DIRECT,VoxoraPresentation.change(false,state(Phase.READY)));
        for(Phase p:new Phase[]{Phase.RECORDING,Phase.CAPTURED,Phase.TRANSCRIBING,Phase.DRAFT,Phase.AUDIO,Phase.PREPARING_AUDIO})
            assertEquals(VoxoraPresentation.Change.CONFIRM,VoxoraPresentation.change(false,state(p)));
        assertEquals(VoxoraPresentation.Change.CONFIRM,VoxoraPresentation.change(true,state(Phase.READY)));
    }
    @Test public void mutedSpeakingStillReportsSpeechAndMutedInput(){
        assertEquals("Risposta vocale",VoxoraPresentation.voiceStatus(new VoiceState("connected","speaking",true,"chat")));
        assertEquals("Chiamata connessa",VoxoraPresentation.voiceStatus(new VoiceState("connected","unknown",false,"chat")));
    }
    @Test public void sendStateNeverOffersRecordingAndCompletedAudioOffersReplay(){
        ReadAloudPlayer.Snapshot idle=new ReadAloudPlayer.Snapshot("idle",0,0,1,false,"");
        assertEquals("",VoxoraPresentation.primary(state(Phase.SENDING),idle));
        assertEquals("VERIFY",VoxoraPresentation.primary(state(Phase.UNCERTAIN),idle));
        assertEquals("Riascolta",VoxoraPresentation.primaryLabel(state(Phase.AUDIO),new ReadAloudPlayer.Snapshot("finished",10000,10000,1,true,"")));
    }
    @Test public void pendingTransitionWaitsForBothOwnersToStop(){
        assertFalse(VoxoraPresentation.canApplyChange(true,false));
        assertFalse(VoxoraPresentation.canApplyChange(false,true));
        assertTrue(VoxoraPresentation.canApplyChange(false,false));
    }
}
