package local.watchvoice;
import java.util.function.IntConsumer;
import android.content.Context;
import android.view.Gravity;
import android.widget.*;
final class LiveVolumeControl {
    static final class Input {
        private int value;private boolean tracking;
        private final IntConsumer apply,persist;
        Input(int initial,IntConsumer apply,IntConsumer persist){value=initial;this.apply=apply;this.persist=persist;}
        void start(){tracking=true;}
        void progress(int value,boolean user){this.value=Math.max(0,Math.min(100,value));if(user){apply.accept(this.value);if(!tracking)persist.accept(this.value);}}
        void stop(){tracking=false;persist.accept(value);}
    }
    static LinearLayout create(Context context,int initial,boolean large,IntConsumer apply,IntConsumer persist){
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER_VERTICAL);int touch=Math.round(48*context.getResources().getDisplayMetrics().density);
        TextView label=new TextView(context);label.setTextColor(0xfff3f7f8);label.setTextSize(large?14:12);label.setText(context.getString(R.string.volume_percent,initial));row.addView(label,new LinearLayout.LayoutParams(-2,-2));
        SeekBar bar=new SeekBar(context);bar.setMax(100);bar.setProgress(initial);bar.setMinimumHeight(touch);bar.setContentDescription("Volume audio");row.addView(bar,new LinearLayout.LayoutParams(0,touch,1));
        Input input=new Input(initial,apply,persist);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar ignored){input.start();}
            public void onStopTrackingTouch(SeekBar ignored){input.stop();}
            public void onProgressChanged(SeekBar ignored,int value,boolean user){label.setText(context.getString(R.string.volume_percent,value));input.progress(value,user);}
        });return row;
    }
}
