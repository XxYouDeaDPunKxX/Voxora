package local.watchvoice;

import androidx.wear.tiles.TileService;
import androidx.wear.tiles.RequestBuilders;
import androidx.wear.tiles.TileBuilders;
import androidx.wear.protolayout.ActionBuilders;
import androidx.wear.protolayout.ColorBuilders;
import androidx.wear.protolayout.DimensionBuilders;
import androidx.wear.protolayout.LayoutElementBuilders;
import androidx.wear.protolayout.ModifiersBuilders;
import androidx.wear.protolayout.ResourceBuilders;
import androidx.wear.protolayout.TimelineBuilders;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import com.google.common.util.concurrent.ListenableFuture;

/** Offline shortcuts. The Activity resolves the current call and last selection at tap time. */
public final class VoxoraTileService extends TileService {
    @Override protected ListenableFuture<TileBuilders.Tile> onTileRequest(RequestBuilders.TileRequest request) {
        boolean lastAvailable = new WatchStore(this).lastChat() != null;
        LayoutElementBuilders.Column column = new LayoutElementBuilders.Column.Builder()
            .setWidth(DimensionBuilders.dp(160))
            .addContent(new LayoutElementBuilders.Text.Builder().setText(getString(R.string.voice_title))
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(18))
                    .setColor(ColorBuilders.argb(0xff8ce0ad)).build()).build())
            .addContent(gap())
            .addContent(shortcut(getString(R.string.tile_resume), "resume", lastAvailable ? "Riprendi ultima chat" : "Scegli chat da riprendere", 160))
            .addContent(gap())
            .addContent(new LayoutElementBuilders.Row.Builder()
                .addContent(shortcut(getString(R.string.tile_new), "new", "Nuova conversazione", 76))
                .addContent(new LayoutElementBuilders.Spacer.Builder().setWidth(DimensionBuilders.dp(8)).build())
                .addContent(shortcut(getString(R.string.tile_open), "session", getString(R.string.open_voxora), 76)).build())
            .build();
        LayoutElementBuilders.Box root = new LayoutElementBuilders.Box.Builder()
            .setWidth(DimensionBuilders.expand()).setHeight(DimensionBuilders.expand())
            .setModifiers(new ModifiersBuilders.Modifiers.Builder().setBackground(new ModifiersBuilders.Background.Builder()
                .setColor(ColorBuilders.argb(0xff0c171c)).build()).build()).addContent(column).build();
        TileBuilders.Tile tile = new TileBuilders.Tile.Builder().setResourcesVersion("1")
            .setTileTimeline(new TimelineBuilders.Timeline.Builder().addTimelineEntry(new TimelineBuilders.TimelineEntry.Builder()
                .setLayout(new LayoutElementBuilders.Layout.Builder().setRoot(root).build()).build()).build()).build();
        return CallbackToFutureAdapter.getFuture(completer -> { completer.set(tile); return "Voxora tile"; });
    }
    private LayoutElementBuilders.Spacer gap() { return new LayoutElementBuilders.Spacer.Builder().setHeight(DimensionBuilders.dp(8)).build(); }
    private LayoutElementBuilders.Box shortcut(String label, String action, String description, int width) {
        ActionBuilders.AndroidActivity activity = new ActionBuilders.AndroidActivity.Builder()
            .setPackageName(getPackageName()).setClassName(MainActivity.class.getName())
            .addKeyToExtraMapping(WearEntry.EXTRA, new ActionBuilders.AndroidStringExtra.Builder().setValue(action).build()).build();
        ModifiersBuilders.Modifiers modifiers = new ModifiersBuilders.Modifiers.Builder()
            .setClickable(new ModifiersBuilders.Clickable.Builder().setId(action)
                .setOnClick(new ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity).build()).build())
            .setSemantics(new ModifiersBuilders.Semantics.Builder().setContentDescription(description).build())
            .setBackground(new ModifiersBuilders.Background.Builder().setColor(ColorBuilders.argb(0xff20343b))
                .setCorner(new ModifiersBuilders.Corner.Builder().setRadius(DimensionBuilders.dp(24)).build()).build()).build();
        return new LayoutElementBuilders.Box.Builder().setWidth(DimensionBuilders.dp(width)).setHeight(DimensionBuilders.dp(48))
            .setModifiers(modifiers).addContent(new LayoutElementBuilders.Text.Builder().setText(label)
                .setFontStyle(new LayoutElementBuilders.FontStyle.Builder().setSize(DimensionBuilders.sp(15))
                    .setColor(ColorBuilders.argb(0xffffffff)).build()).setMaxLines(1).build()).build();
    }
    @Override protected ListenableFuture<ResourceBuilders.Resources> onTileResourcesRequest(RequestBuilders.ResourcesRequest request) {
        return CallbackToFutureAdapter.getFuture(completer -> { completer.set(new ResourceBuilders.Resources.Builder().setVersion("1").build()); return "Voxora tile resources"; });
    }
}
