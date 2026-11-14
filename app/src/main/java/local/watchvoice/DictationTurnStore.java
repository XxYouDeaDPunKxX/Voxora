package local.watchvoice;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONObject;
import static local.watchvoice.DictationState.*;
final class DictationTurnStore {
    private final File directory;
    DictationTurnStore(File directory){this.directory=directory;if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("private_directory");}
    File file(String name){if(!name.matches("recording\\.wav|audio\\.aac|input\\.txt|output\\.txt|audio-state\\.json"))throw new IllegalArgumentException("private_file");return new File(directory,name);}
    synchronized boolean claim(SendRecord record)throws IOException {
        byte[] content=encode(record);File claim=new File(directory,"send.claim");
        if(!claim.createNewFile())return false;
        try(FileOutputStream out=new FileOutputStream(claim)){out.write(content);out.getFD().sync();}
        return true;
    }
    synchronized SendRecord read()throws IOException {
        File claim=new File(directory,"send.claim");if(!claim.exists())return null;
        try(FileInputStream in=new FileInputStream(claim)) {
            byte[] bytes=CredentialStore.readLimited(in,8192);
            JSONObject value=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            SendRecord result=new SendRecord(nullable(value,"user"),nullable(value,"chat"),nullable(value,"assistant"),value.getString("phase"));
            encode(result);return result;
        }catch(Exception invalid){return new SendRecord(null,null,null,"uncertain");}
    }
    synchronized void update(SendRecord record)throws IOException {
        SendRecord current=read();
        if(current==null || current.userId()==null || !current.userId().equals(record.userId()))throw new IOException("send_record_identity");
        byte[] bytes=encode(record);File next=new File(directory,"send.next");
        try(FileOutputStream out=new FileOutputStream(next)){out.write(bytes);out.getFD().sync();}
        Files.move(next.toPath(),new File(directory,"send.claim").toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    synchronized void clearCache()throws IOException {for(String name:new String[]{"recording.wav","audio.aac","input.txt","output.txt","audio-state.json"}){File f=file(name);if(f.exists()&&!f.delete())throw new IOException("private_cleanup");}}
    synchronized void clearResolvedClaim()throws IOException {SendRecord record=read();if(record!=null&&!"verified".equals(record.phase()))throw new IOException("send_unresolved");File file=new File(directory,"send.claim");if(file.exists()&&!file.delete())throw new IOException("claim_cleanup");}
    /** Explicit manual acknowledgement only; does not contact the server or retry a message. */
    synchronized void acknowledge()throws IOException {File claim=new File(directory,"send.claim");if(claim.exists()&&!claim.delete())throw new IOException("claim_cleanup");}
    private static String nullable(JSONObject value,String key){return value.isNull(key)?null:value.optString(key,null);}
    private static byte[] encode(SendRecord record)throws IOException {
        try {
            if(record.userId()==null || !record.phase().matches("pending|uncertain|verified"))throw new IllegalArgumentException();
            ConversationData.identifier(record.userId());if(record.conversationId()!=null)ConversationData.identifier(record.conversationId());if(record.assistantId()!=null)ConversationData.identifier(record.assistantId());
            return new JSONObject().put("user",record.userId()).put("chat",record.conversationId()==null?JSONObject.NULL:record.conversationId()).put("assistant",record.assistantId()==null?JSONObject.NULL:record.assistantId()).put("phase",record.phase()).toString().getBytes(StandardCharsets.UTF_8);
        }catch(Exception invalid){throw new IOException("send_record_invalid");}
    }
}
