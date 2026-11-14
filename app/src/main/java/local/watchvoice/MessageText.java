package local.watchvoice;

import java.util.*;

/** Derived presentation only. Source offsets always refer to the untouched message. */
final class MessageText {
    enum Kind { PARAGRAPH, HEADING, CODE, QUOTE, TABLE, LITERAL }
    enum Style { BOLD, ITALIC, MONO }
    record Span(int start,int end,Style style) {}
    record Block(Kind kind,int sourceStart,int sourceEnd,String text,List<Span> spans) {}
    record Piece(Kind kind,String text,List<Span> spans) {}
    record Page(List<Piece> pieces,int nextCursor,boolean end) {}
    private record Formatted(String text,List<Span> spans) {}
    private record Line(int start,int end,String text) {}

    static List<Block> parse(String source) {
        Objects.requireNonNull(source);List<Line> lines=new ArrayList<>();
        for(int start=0;start<source.length();){int end=source.indexOf('\n',start);end=end<0?source.length():end+1;lines.add(new Line(start,end,source.substring(start,end)));start=end;}
        List<Block> blocks=new ArrayList<>();
        for(int i=0;i<lines.size();) {
            Line line=lines.get(i);String trimmed=line.text().trim();int last=i;Kind kind=Kind.PARAGRAPH;String value=null;
            if(trimmed.startsWith("```")||trimmed.startsWith("~~~")) {
                String fence=trimmed.substring(0,3);int close=i+1;
                while(close<lines.size()&&!lines.get(close).text().trim().startsWith(fence))close++;
                if(close<lines.size()){last=close;kind=Kind.CODE;value=source.substring(line.end(),lines.get(close).start());}
                else {last=lines.size()-1;kind=Kind.LITERAL;}
            } else if(i+1<lines.size()&&tableSeparator(lines.get(i+1).text())&&line.text().contains("|")) {
                last=i+1;while(last+1<lines.size()&&lines.get(last+1).text().contains("|")&&!lines.get(last+1).text().trim().isEmpty())last++;
                value=table(lines.subList(i,last+1));kind=value==null?Kind.LITERAL:Kind.TABLE;
            } else if(trimmed.matches("#{1,6} .*")) {kind=Kind.HEADING;value=line.text().replaceFirst("^\\s*#{1,6} +","");}
            else if(trimmed.startsWith("> ")) {kind=Kind.QUOTE;value=line.text().replaceFirst("^\\s*> ?","│ ");}
            else {
                while(last+1<lines.size()) {String next=lines.get(last+1).text().trim();if(next.startsWith("```")||next.startsWith("~~~")||next.startsWith("#")||next.startsWith("> ")||(last+2<lines.size()&&tableSeparator(lines.get(last+2).text())))break;last++;}
            }
            int end=lines.get(last).end();if(value==null)value=source.substring(line.start(),end);
            Formatted formatted=kind==Kind.CODE||kind==Kind.LITERAL?new Formatted(value,List.of()):inline(value);
            blocks.add(new Block(kind,line.start(),end,formatted.text(),formatted.spans()));i=last+1;
        }
        return List.copyOf(blocks);
    }
    static Page page(List<Block> blocks,int cursor,int budget) {
        if(cursor<0||budget<2)throw new IllegalArgumentException("page_bounds");
        List<Piece> pieces=new ArrayList<>();int offset=0,next=cursor,left=budget,total=0;
        for(Block block:blocks)total+=block.text().length();
        for(Block block:blocks){int length=block.text().length();if(cursor>=offset+length){offset+=length;continue;}
            int start=Math.max(0,cursor-offset),end=Math.min(length,start+left);
            if(end<length&&end>start&&Character.isHighSurrogate(block.text().charAt(end-1))&&Character.isLowSurrogate(block.text().charAt(end)))end--;
            if(end<=start)break;List<Span> spans=new ArrayList<>();
            for(Span span:block.spans()){int a=Math.max(start,span.start()),b=Math.min(end,span.end());if(a<b)spans.add(new Span(a-start,b-start,span.style()));}
            pieces.add(new Piece(block.kind(),block.text().substring(start,end),List.copyOf(spans)));int taken=end-start;left-=taken;next+=taken;offset+=length;
            if(left<2||end<length)break;
        }
        return new Page(List.copyOf(pieces),next,next>=total);
    }
    private static boolean tableSeparator(String line){List<String> cells=cells(line);return cells!=null&&cells.size()>1&&cells.stream().allMatch(c->c.trim().matches(":?-{3,}:?"));}
    private static String table(List<Line> lines){
        List<String> headers=cells(lines.get(0).text());if(headers==null)return null;StringBuilder out=new StringBuilder();
        for(int i=2;i<lines.size();i++){List<String> row=cells(lines.get(i).text());if(row==null||row.size()!=headers.size())return null;
            for(int c=0;c<headers.size();c++){String header=headers.get(c).trim();String value=row.get(c).trim();out.append(header.isEmpty()?"Colonna "+(c+1):header).append(": ").append(value.isEmpty()?"(vuoto)":value).append('\n');}out.append('\n');}
        if(lines.size()==2)for(int c=0;c<headers.size();c++)out.append(headers.get(c)).append('\n');return out.toString();
    }
    private static List<String> cells(String line){
        String s=line.trim();if(s.contains("`"))return null;List<String> values=new ArrayList<>();StringBuilder cell=new StringBuilder();
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='\\'&&i+1<s.length()&&(s.charAt(i+1)=='|'||s.charAt(i+1)=='\\')){cell.append(s.charAt(++i));continue;}
            if(c=='|'){values.add(cell.toString());cell.setLength(0);}else cell.append(c);}
        values.add(cell.toString());if(s.startsWith("|"))values.remove(0);if(s.endsWith("|")&&!s.endsWith("\\|"))values.remove(values.size()-1);return values;
    }
    private static Formatted inline(String source){
        StringBuilder out=new StringBuilder();List<Span> spans=new ArrayList<>();
        for(int i=0;i<source.length();){char c=source.charAt(i);
            if(c=='`'||c=='*') {String marker=c=='*'&&i+1<source.length()&&source.charAt(i+1)=='*'?"**":String.valueOf(c);int close=source.indexOf(marker,i+marker.length());
                if(close>i+marker.length()){int start=out.length();out.append(source,i+marker.length(),close);spans.add(new Span(start,out.length(),c=='`'?Style.MONO:marker.length()==2?Style.BOLD:Style.ITALIC));i=close+marker.length();continue;}}
            if(c=='['||c=='!'&&i+1<source.length()&&source.charAt(i+1)=='['){int label=i+(c=='!'?2:1),close=source.indexOf("](",label);if(close>=label){int end=source.indexOf(')',close+2);if(end>close+2){if(c=='!')out.append("Immagine: ");out.append(source,label,close).append(" (").append(source,close+2,end).append(')');i=end+1;continue;}}}
            out.append(c);i++;
        }
        return new Formatted(out.toString(),List.copyOf(spans));
    }
}
