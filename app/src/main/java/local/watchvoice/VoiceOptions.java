package local.watchvoice;
import org.json.JSONObject;
final class VoiceOptions {
    static final String[] VOICES = {"arbor", "breeze", "cove", "ember", "juniper", "maple", "sol", "spruce", "vale"};
    static final String[] LANGUAGES = {"it-IT", "en-US", "es-ES", "fr-FR", "de-DE"};
    String voice, language, route;
    int volumePercent;
    boolean haptics, largeText, captions, keepScreenOn;
    static VoiceOptions defaults() {
        VoiceOptions value = new VoiceOptions(); value.voice = "juniper"; value.language = "it-IT";
        value.route = "speaker"; value.volumePercent = 100; value.haptics = true; return value;
    }
    static VoiceOptions fromJson(JSONObject value) {
        VoiceOptions options = defaults(); if (value == null) return options;
        options.voice = supported(value.optString("voice", options.voice), VOICES, options.voice);
        options.language = supported(value.optString("language", options.language), LANGUAGES, options.language);
        options.route = choice(value.optString("route", options.route), "[a-zA-Z0-9_-]{1,64}", options.route);
        options.volumePercent = Math.max(0, Math.min(100, value.optInt("volumePercent", 100)));
        options.haptics = value.optBoolean("haptics", true); options.largeText = value.optBoolean("largeText", false);
        options.captions = value.optBoolean("captions", false); options.keepScreenOn = value.optBoolean("keepScreenOn", false);
        return options;
    }
    private static String choice(String value, String pattern, String fallback) { return value.matches(pattern) ? value : fallback; }
    private static String supported(String value, String[] choices, String fallback) { for (String item : choices) if (item.equals(value)) return value; return fallback; }
    JSONObject toJson() {
        try { return new JSONObject().put("voice", voice).put("language", language).put("route", route)
            .put("volumePercent", volumePercent).put("haptics", haptics).put("largeText", largeText)
            .put("captions", captions).put("keepScreenOn", keepScreenOn); }
        catch (Exception e) { throw new IllegalArgumentException("options_invalid"); }
    }
}
