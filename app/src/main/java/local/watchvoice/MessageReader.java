package local.watchvoice;
import android.content.Context;
import android.graphics.Typeface;
import android.text.*;
import android.text.style.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Read-only paged rendering. Never sends the displayed or shortened text. */
final class MessageReader {
    static void populate(Context context,LinearLayout body,ScrollView scroll,String source,boolean large,Runnable close){
        new MessageReader(context,body,scroll,source,large,close).append();
    }
    private final Context context;private final LinearLayout body;private final ScrollView scroll;private final boolean large;private final Runnable close;
    private final List<MessageText.Block> blocks;private int cursor;private Button more,bottom;
    private MessageReader(Context c,LinearLayout body,ScrollView scroll,String source,boolean large,Runnable close){
        context=c;this.body=body;this.scroll=scroll;this.large=large;this.close=close;
        List<MessageText.Block> parsed;
        try{parsed=MessageText.parse(source);}catch(RuntimeException error){parsed=List.of(new MessageText.Block(MessageText.Kind.LITERAL,0,source.length(),source,List.of()));}
        blocks=parsed;
    }
    private void append(){
        int y=scroll.getScrollY();if(more!=null)body.removeView(more);if(bottom!=null)body.removeView(bottom);
        MessageText.Page page=MessageText.page(blocks,cursor,4000);cursor=page.nextCursor();
        for(MessageText.Piece piece:page.pieces()){
            TextView view=new TextView(context);view.setTextColor(RoundUi.INK);view.setTextSize((piece.kind()==MessageText.Kind.HEADING?15:13)*(large?1.18f:1));view.setPadding(0,4,0,8);view.setTextIsSelectable(true);
            SpannableString content=new SpannableString(piece.text());for(MessageText.Span span:piece.spans())content.setSpan(span.style()==MessageText.Style.MONO?new TypefaceSpan("monospace"):new StyleSpan(span.style()==MessageText.Style.BOLD?Typeface.BOLD:Typeface.ITALIC),span.start(),span.end(),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(content);if(piece.kind()==MessageText.Kind.CODE)view.setTypeface(Typeface.MONOSPACE);body.addView(view,new LinearLayout.LayoutParams(-1,-2));
        }
        if(!page.end()){more=button("Mostra altro testo",this::append);body.addView(more,new LinearLayout.LayoutParams(-1,-2));}
        bottom=button("Chiudi testo",close);body.addView(bottom,new LinearLayout.LayoutParams(-1,-2));scroll.post(()->scroll.scrollTo(0,y));
    }
    private Button button(String label,Runnable action){Button b=new Button(context);b.setText(label);b.setAllCaps(false);b.setTextSize(large?14:12);b.setTextColor(RoundUi.ACCENT);b.setBackgroundColor(RoundUi.BG);int h=Math.round(48*context.getResources().getDisplayMetrics().density);b.setMinimumHeight(h);b.setOnClickListener(v->action.run());return b;}
}
