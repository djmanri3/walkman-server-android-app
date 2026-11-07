package androidx.media;

import android.support.v4.media.MediaBrowserCompat;
import java.util.List;
import java.util.function.Consumer;

/** Receives real detached browse results without installing a test APK. */
public final class BrowseTestResult extends MediaBrowserServiceCompat.Result<List<MediaBrowserCompat.MediaItem>> {
    private final Consumer<List<MediaBrowserCompat.MediaItem>> callback;
    public BrowseTestResult(Consumer<List<MediaBrowserCompat.MediaItem>> callback) {
        super("Android Auto smoke test");
        this.callback = callback;
    }
    @Override void onResultSent(List<MediaBrowserCompat.MediaItem> items) {
        callback.accept(items);
    }
}
