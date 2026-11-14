package local.watchvoice;
import org.junit.Test;
import static org.junit.Assert.*;
public class RoundLayoutTest {
    @Test public void adjacentControlTargetsDoNotOverlap(){
        for(int d:new int[]{216,240})for(boolean bottom:new boolean[]{false,true})for(int i=0;i<2;i++){
            RoundLayout.Box a=RoundLayout.control(d,bottom,i),b=RoundLayout.control(d,bottom,i+1);
            assertTrue(a.x()+a.width()<=b.x());
        }
    }
    @Test public void visibleGlyphsFitCircleAtBothSizes(){
        for(int d:new int[]{216,240})for(boolean bottom:new boolean[]{false,true})for(int i=0;i<3;i++){
            RoundLayout.Box b=RoundLayout.glyph(d,bottom,i);
            for(float x:new float[]{b.x(),b.x()+b.width()})for(float y:new float[]{b.y(),b.y()+b.height()})
                assertTrue("glyph "+d+"/"+bottom+"/"+i,Math.hypot(x-d/2f,y-d/2f)<=d/2f-1);
        }
    }
    @Test public void bodyUsesWideMiddleAndReservesControls(){
        RoundLayout.Box b=RoundLayout.body(216,true);
        assertTrue(b.width()>200);assertEquals(56,b.y(),.01);assertTrue(b.y()+b.height()<=160);
    }
}
