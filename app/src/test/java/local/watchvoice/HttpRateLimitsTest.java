package local.watchvoice;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;
public class HttpRateLimitsTest {
    @Test public void observedThirtySecondsBlocksOnlyMatchingReadAndExpiresWithoutRetry(){
        var limits=new HttpRateLimits();limits.record("detail","30",null,100000,500);
        assertEquals(30,limits.remaining("detail",500));assertEquals(30,limits.remaining("detail",501));assertEquals(1,limits.remaining("detail",30499));assertEquals(0,limits.remaining("detail",30500));assertEquals(0,limits.remaining("list",500));
    }
    @Test public void retryDateUsesServerClockRatherThanWrongWatchWallClock(){
        var limits=new HttpRateLimits();limits.record("detail","Thu, 15 Oct 2026 02:00:30 GMT","Thu, 15 Oct 2026 02:00:00 GMT",0,1000);
        assertEquals(30,limits.remaining("detail",1000));
    }
    @Test public void missingInvalidOrExpiredHeaderDoesNotInventServerWait(){
        var limits=new HttpRateLimits();for(String header:new String[]{"","-4","garbage","0","999999999999999999999999"})limits.record("detail",header,null,1000,500);
        assertEquals(0,limits.remaining("detail",500));
    }
    @Test public void shorterLaterWaitCannotShortenExistingServerDeadline(){
        var limits=new HttpRateLimits();limits.record("detail","30",null,0,1000);limits.record("detail","1",null,0,2000);assertEquals(29,limits.remaining("detail",2000));
    }
    @Test public void diagnosticCategoriesNeverReturnArbitraryRemoteText(){
        assertEquals("too_many_requests",HttpRateLimits.category("{\"detail\":\"Too many requests\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("unknown",HttpRateLimits.category("{\"detail\":\"private message and token abc\"}".getBytes(StandardCharsets.UTF_8)));
        assertEquals("non_json",HttpRateLimits.category("<html>private</html>".getBytes(StandardCharsets.UTF_8)));
    }
}
