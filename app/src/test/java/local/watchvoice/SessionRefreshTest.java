package local.watchvoice;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class SessionRefreshTest {
    // The refresh endpoint can return a stale token alongside this error; it must not be used.
    @Test public void refreshAccessTokenErrorRejectsStaleToken() throws Exception {
        JSONObject response = new JSONObject()
            .put("error", "RefreshAccessTokenError")
            .put("accessToken", "stale-token");
        IOException error = assertThrows(IOException.class, () -> SessionRefresh.accessToken(response));
        assertEquals("session_expired", error.getMessage());
    }

    // Arbitrary remote error text is classified without being reflected to UI or logs.
    @Test public void otherRefreshErrorsUseSafeClassification() throws Exception {
        IOException error = assertThrows(IOException.class, () -> SessionRefresh.accessToken(
            new JSONObject().put("error", "private-account-detail")
                .put("accessToken", "stale-token")));
        assertEquals("auth_refresh_failed", error.getMessage());
        assertFalse(error.getMessage().contains("private-account-detail"));
    }

    @Test public void missingNullEmptyAndNonStringTokensAreRejected() throws Exception {
        JSONObject[] responses = {
            new JSONObject(),
            new JSONObject().put("accessToken", JSONObject.NULL),
            new JSONObject().put("accessToken", ""),
            new JSONObject().put("accessToken", 17)
        };
        for (JSONObject response : responses) {
            IOException error = assertThrows(IOException.class, () -> SessionRefresh.accessToken(response));
            assertEquals("auth_response_invalid", error.getMessage());
        }
    }

    @Test public void validTokenIsReturnedUnchanged() throws Exception {
        assertEquals("eyJ.synthetic.token", SessionRefresh.accessToken(
            new JSONObject().put("accessToken", "eyJ.synthetic.token")));
    }
}
