package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
public class MessageTextTest {
    @Test public void longSourceHasNoLostTailOrSplitEmoji() {
        String source="a".repeat(99990)+"😀FINE_COMPLETA";var blocks=MessageText.parse(source);StringBuilder original=new StringBuilder();
        for(var block:blocks)original.append(source,block.sourceStart(),block.sourceEnd());assertEquals(source,original.toString());
        StringBuilder shown=new StringBuilder();int cursor=0;
        while(true){var page=MessageText.page(blocks,cursor,4000);assertTrue(page.nextCursor()>cursor);for(var piece:page.pieces()){String s=piece.text();assertFalse(!s.isEmpty()&&Character.isLowSurrogate(s.charAt(0)));assertFalse(!s.isEmpty()&&Character.isHighSurrogate(s.charAt(s.length()-1)));shown.append(s);}cursor=page.nextCursor();if(page.end())break;}
        assertEquals(source,shown.toString());assertTrue(shown.toString().endsWith("FINE_COMPLETA"));
    }
    @Test public void tablePreservesEmptyAndEscapedCells() {
        String source="| Nome | Nota |\n|---|---|\n| Uno | a\\|b |\n| Due | |\n";
        var blocks=MessageText.parse(source);StringBuilder display=new StringBuilder();for(var b:blocks)display.append(b.text());
        assertTrue(display.toString().contains("Nome: Uno"));assertTrue(display.toString().contains("Nota: a|b"));assertTrue(display.toString().contains("Nome: Due"));assertTrue(display.toString().contains("Nota: (vuoto)"));
    }
    @Test public void irregularTableFallsBackWithoutLosingExtraCell() {
        String source="| A | B |\n|---|---|\n| one | two | extra |\n";
        var blocks=MessageText.parse(source);assertEquals(source,blocks.get(0).text());
    }
    @Test public void codeRemainsLiteralAndLinksKeepTheirDestination() {
        String source="# Titolo\n**grassetto** e `a * b`\n[label](https://example.com/path)\n```java\n<script> a * b </script>\n```\n";
        var blocks=MessageText.parse(source);StringBuilder shown=new StringBuilder();for(var block:blocks)shown.append(block.text());
        assertTrue(shown.toString().contains("grassetto"));assertTrue(shown.toString().contains("a * b"));assertTrue(shown.toString().contains("https://example.com/path"));assertTrue(shown.toString().contains("<script> a * b </script>"));
        assertTrue(blocks.stream().anyMatch(b->!b.spans().isEmpty()));
    }
}
