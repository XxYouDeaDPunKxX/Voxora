package local.watchvoice;
final class WearEntry {
    static final String EXTRA = "wearEntry";
    static String action(String action, boolean callActive, boolean lastChatAvailable) {
        if (callActive) return "session";
        if ("resume".equals(action)) return lastChatAvailable ? "resume" : "chats";
        if ("new".equals(action) || "chats".equals(action)) return action;
        return "session";
    }
}
