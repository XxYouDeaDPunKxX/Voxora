package local.watchvoice;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.*;
import android.widget.*;

/** Fixed chrome occupies only the poles; content keeps the broad centre of the dial. */
final class RoundUi extends FrameLayout {
    static final int BG=0xff090e0b,INK=0xfff3f6f3,ACCENT=0xffb5e7c5,DIM=0xffa7b3a9;
    private final boolean large;
    final ScrollView scroll;
    final LinearLayout body;
    private boolean dock;
    private final View[] top=new View[3],bottom=new View[3];
    public RoundUi(Context c){this(c,false);}
    RoundUi(Context c,boolean large){
        super(c);this.large=large;setBackgroundColor(BG);
        scroll=new ScrollView(c);scroll.setFillViewport(false);scroll.setClipToPadding(true);scroll.setVerticalScrollBarEnabled(true);
        scroll.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);scroll.setFocusable(true);
        body=new LinearLayout(c);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(3),0,dp(3),dp(36));
        scroll.addView(body,new ScrollView.LayoutParams(-1,-2));addView(scroll);
    }
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    void head(View left,View centre,View right){for(View v:top)if(v!=null)removeView(v);top[0]=left;top[1]=centre;top[2]=right;for(View v:top)if(v!=null)addView(v);requestLayout();}
    void actions(View left,View centre,View right){for(View v:bottom)if(v!=null)removeView(v);bottom[0]=left;bottom[1]=centre;bottom[2]=right;dock=left!=null||centre!=null||right!=null;for(View v:bottom)if(v!=null)addView(v);requestLayout();}
    Button tool(String icon,String label,String description,Runnable action,boolean footer){
        Tool b=new Tool(icon,footer);b.setAllCaps(false);b.setText(label);b.setTextSize(large?10.5f:9);b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[0]},new int[]{0xff647168,ACCENT}));
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);b.setGravity(Gravity.CENTER);b.setIncludeFontPadding(false);b.setMaxLines(2);
        b.setContentDescription(description);b.setBackground(new RippleDrawable(ColorStateList.valueOf(0xff294b35),null,null));b.setOnClickListener(v->action.run());return b;
    }
    private final class Tool extends Button {
        final Drawable glyph;final boolean footer;boolean middle;
        final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
        Tool(String icon,boolean footer){super(RoundUi.this.getContext());glyph=icon(icon);this.footer=footer;}
        @Override protected void onDraw(Canvas canvas){
            // Native compound drawables place the lower glyph against the rectangular
            // edge. Explicit local coordinates keep the visible symbol inside the dial.
            int centreY=dp(footer?(middle?28:24):(middle?12:21)),size=dp(16),x=(getWidth()-size)/2;
            if(glyph!=null){glyph.setBounds(x,centreY-size/2,x+size,centreY+size/2);glyph.setAlpha(isEnabled()?255:100);glyph.draw(canvas);}
            String label=getText().toString().replace('\n',' ');if(label.isEmpty())return;
            ink.setColor(getCurrentTextColor());ink.setTypeface(getTypeface());ink.setTextSize(getTextSize());ink.setTextAlign(Paint.Align.CENTER);
            float top=dp(footer?1:middle?24:31);canvas.drawText(label,getWidth()/2f,top-ink.getFontMetrics().top,ink);
        }
    }
    Drawable icon(String name){int id=switch(name){case "add"->R.drawable.ui_add;case "back"->R.drawable.ui_back;case "chats"->R.drawable.ui_chats;case "check"->R.drawable.ui_check;case "close"->R.drawable.ui_close;case "gear"->R.drawable.ui_gear;case "info"->R.drawable.ui_info;case "mic"->R.drawable.ui_mic;case "more"->R.drawable.ui_more;case "mute"->R.drawable.ui_mute;case "pause"->R.drawable.ui_pause;case "phone"->R.drawable.ui_phone;case "play"->R.drawable.ui_play;case "record"->R.drawable.ui_record;case "redo"->R.drawable.ui_redo;case "refresh"->R.drawable.ui_refresh;case "search"->R.drawable.ui_search;case "send"->R.drawable.ui_send;case "speed"->R.drawable.ui_speed;case "stop"->R.drawable.ui_stop;case "text"->R.drawable.ui_text;case "transcribe"->R.drawable.ui_transcribe;case "volume"->R.drawable.ui_volume;case "wait"->R.drawable.ui_wait;case "warning"->R.drawable.ui_warning;default->0;};return id==0?null:getContext().getDrawable(id);}
    @Override protected void onMeasure(int ws,int hs){
        int w=MeasureSpec.getSize(ws),h=MeasureSpec.getSize(hs);setMeasuredDimension(w,h);int d=Math.round(Math.min(w,h)/getResources().getDisplayMetrics().density);
        RoundLayout.Box b=RoundLayout.body(d,dock);scroll.measure(MeasureSpec.makeMeasureSpec(dp(b.width()),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(b.height()),MeasureSpec.EXACTLY));
        for(int i=0;i<3;i++){
            RoundLayout.Box topBox=RoundLayout.control(d,false,i),bottomBox=RoundLayout.control(d,true,i);
            if(top[i]!=null)top[i].measure(MeasureSpec.makeMeasureSpec(dp(topBox.width()),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(topBox.height()),MeasureSpec.EXACTLY));
            if(bottom[i]!=null)bottom[i].measure(MeasureSpec.makeMeasureSpec(dp(bottomBox.width()),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(dp(bottomBox.height()),MeasureSpec.EXACTLY));
        }
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int w=getWidth(),h=getHeight(),d=Math.round(Math.min(w,h)/getResources().getDisplayMetrics().density);RoundLayout.Box content=RoundLayout.body(d,dock);
        int x=dp(content.x()),y=dp(content.y());scroll.layout(x,y,x+scroll.getMeasuredWidth(),y+scroll.getMeasuredHeight());
        for(int i=0;i<3;i++){
            if(top[i] instanceof Tool)((Tool)top[i]).middle=i==1;
            if(bottom[i] instanceof Tool)((Tool)bottom[i]).middle=i==1;
            if(top[i]!=null){RoundLayout.Box box=RoundLayout.control(d,false,i);int xx=dp(box.x()),yy=dp(box.y());top[i].layout(xx,yy,xx+top[i].getMeasuredWidth(),yy+top[i].getMeasuredHeight());}
            if(bottom[i]!=null){RoundLayout.Box box=RoundLayout.control(d,true,i);int xx=dp(box.x()),yy=dp(box.y());bottom[i].layout(xx,yy,xx+bottom[i].getMeasuredWidth(),yy+bottom[i].getMeasuredHeight());}
        }
    }
}
