package local.watchvoice;

import org.json.JSONObject;
import java.io.IOException;

/** Classifies the auth refresh response before a returned token can be used. */
final class SessionRefresh {
    private SessionRefresh() {}

    static String accessToken(JSONObject response) throws IOException {
        if (response == null) throw new IOException("auth_response_invalid");

        Object error = response.opt("error");
        if ("RefreshAccessTokenError".equals(error)) throw new IOException("session_expired");
        if (error != null && error != JSONObject.NULL
            && (!(error instanceof String) || !((String) error).isEmpty())) {
            throw new IOException("auth_refresh_failed");
        }

        Object token = response.opt("accessToken");
        if (!(token instanceof String) || ((String) token).isEmpty()) {
            throw new IOException("auth_response_invalid");
        }
        return (String) token;
    }
}
