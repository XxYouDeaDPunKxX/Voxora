package local.watchvoice;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import androidx.wear.watchface.complications.data.*;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** A static, private-title-free app shortcut; no history requests or microphone control. */
public final class VoxoraComplicationService extends ComplicationDataSourceService {
    @Override public void onComplicationRequest(ComplicationRequest request, ComplicationRequestListener listener) {
        try { listener.onComplicationData(data(request.getComplicationType())); }
        catch (android.os.RemoteException ignored) { /* The requesting watch face disconnected. */ }
    }
    @Override public ComplicationData getPreviewData(ComplicationType type) { return data(type); }
    private ComplicationData data(ComplicationType type) {
        ComplicationText description = new PlainComplicationText.Builder(getString(R.string.open_voxora)).build();
        MonochromaticImage image = new MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_voxora_notification)).build();
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, MainActivity.class).putExtra(WearEntry.EXTRA, "session")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        if (type == ComplicationType.MONOCHROMATIC_IMAGE) return new MonochromaticImageComplicationData.Builder(image, description).setTapAction(open).build();
        if (type == ComplicationType.SHORT_TEXT) return new ShortTextComplicationData.Builder(new PlainComplicationText.Builder("Voxora").build(), description)
            .setMonochromaticImage(image).setTapAction(open).build();
        return null;
    }
}
