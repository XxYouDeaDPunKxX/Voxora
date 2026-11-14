package local.watchvoice;

import java.util.ArrayList;
import java.util.List;

/** UI pages only; never owns or changes a voice call. */
final class UiNavigation {
    static final int SESSION=0, CHATS=1, SETTINGS=2, MENU=3, VOICE=4, LANGUAGE=5, ROUTE=6, CONFIRM=7,
        VOLUME=8, STATUS=9, READER=10, DETAIL=11, FILTER=12, DIAGNOSTICS=13, TITLE=14;
    private final List<Integer> path = new ArrayList<>(List.of(SESSION));
    private final int[] offsets = new int[15];
    int screen() { return path.get(path.size()-1); }
    void open(int destination) {
        if (destination < SESSION || destination > SETTINGS) throw new IllegalArgumentException("ui_destination");
        path.clear(); path.add(destination);
    }
    void show(int page) {
        if (page < MENU || page > TITLE) throw new IllegalArgumentException("ui_page");
        if (screen() != page) path.add(page);
    }
    boolean back() {
        if (path.size() > 1) path.remove(path.size()-1);
        else if (screen() != SESSION) open(SESSION);
        else return false;
        return true;
    }
    void remember(int pixels) { remember(screen(), pixels); }
    void remember(int page, int pixels) { offsets[page] = Math.max(0, pixels); }
    int position() { return offsets[screen()]; }
    int[] path() { return path.stream().mapToInt(Integer::intValue).toArray(); }
    int[] positions() { return offsets.clone(); }
    void restore(int[] savedPath, int[] positions) {
        if (positions != null)
            for (int i=0; i<Math.min(offsets.length,positions.length); i++) offsets[i] = Math.max(0, positions[i]);
        if (savedPath == null || savedPath.length == 0 || savedPath.length > 8) return;
        if (savedPath[0] < SESSION || savedPath[0] > SETTINGS) return;
        for (int i=1; i<savedPath.length; i++) if (savedPath[i] < MENU || savedPath[i] > TITLE) return;
        path.clear(); for (int page : savedPath) path.add(page);
    }
}
