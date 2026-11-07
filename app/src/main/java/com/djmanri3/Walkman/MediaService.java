package com.djmanri3.Walkman;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.Objects;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.KeyEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import androidx.media.session.MediaButtonReceiver;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Servicio multimedia encargado de la integración total con el sistema de
 * Android: MediaSession (widget), notificación MediaStyle, botones físicos y
 * bluetooth, Android Auto y audio focus.
 *
 * El audio real lo reproduce el elemento &lt;audio&gt; dentro del WebView;
 * este servicio se encarga de exponer ese estado al sistema operativo y de
 * traducir los comandos del sistema (play/pause/next/prev/seek...) en llamadas
 * al JavaScript de la web a través del {@link CommandListener}.
 */
public class MediaService extends MediaBrowserServiceCompat {

    private static final String TAG = "WalkmanMedia";
    public static final String CHANNEL_ID = "walkman_media";
    public static final int NOTIFICATION_ID = 1000;

    public static final String ACTION_PLAY = "com.djmanri3.Walkman.action.PLAY";
    public static final String ACTION_STOP = "com.djmanri3.Walkman.action.STOP";
    public static final String ACTION_PAUSE = "com.djmanri3.Walkman.action.PAUSE";
    public static final String ACTION_NEXT = "com.djmanri3.Walkman.action.NEXT";
    public static final String ACTION_PREV = "com.djmanri3.Walkman.action.PREV";
    public static final String ACTION_TOGGLE = "com.djmanri3.Walkman.action.TOGGLE";

    // IDs de categorías para Android Auto
    private static final String MEDIA_ROOT_ID = "walkman_root";
    private static final String MEDIA_ID_NOW_PLAYING = "walkman_now_playing";
    private static final String MEDIA_ID_PLAY_ALL = "walkman_play_all";
    private static final String MEDIA_ID_SHUFFLE = "walkman_shuffle";
    private static final String MEDIA_ID_QUEUE = "walkman_queue";
    private static final String MEDIA_ID_ALBUMS = "walkman_albums";
    private static final String MEDIA_ID_PLAYLISTS = "walkman_playlists";
    private static final String MEDIA_ID_ARTISTS = "walkman_artists";
    private static final String MEDIA_ID_SERVERS = "walkman_servers";

    // Modos shuffle/repeat actuales, para exponerlos en el PlaybackState y que
    // Android Auto muestre sus propios botones correctamente.
    private static volatile int sShuffleMode = PlaybackStateCompat.SHUFFLE_MODE_NONE;
    private static volatile int sRepeatMode = PlaybackStateCompat.REPEAT_MODE_NONE;

    /** Referencia al servicio corriendo (null si no está en marcha). */
    private static volatile MediaService sInstance;

    /** Escuchador de comandos que envía las órdenes del sistema a la web. */
    public interface CommandListener {
        void onCommand(String command, String argument);
    }

    private static CommandListener sCommandListener;

    private MediaSessionCompat mSession;
    private NotificationManager mNotificationManager;

    // Serializa descargas y escrituras de carátulas. Las tareas obsoletas
    // salen antes de abrir una conexión, sin crear hilos por cada cambio.
    private static final ExecutorService BROWSE_EXECUTOR = Executors.newSingleThreadExecutor();
    private final Map<String, PendingBrowse> mPendingBrowses = new LinkedHashMap<>();
    private final Map<String, Long> mRefreshTimes = new LinkedHashMap<>();

    private static class PendingBrowse {
        final List<Result<List<MediaBrowserCompat.MediaItem>>> results = new ArrayList<>();
        Runnable timeout;
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static volatile Handler sHandler = null;

    // Incrementa en cada actualización de pista para descartar carátulas
    // obsoletas cuando varias descargas quedan en vuelo (race condition).
    private static volatile long sArtGeneration = 0;
    private String mRequestedArtUrl = "";
    private boolean mArtworkPending;
    private long mArtworkRetryAfter;

    // Última URL de carátula realmente aplicada a la sesión.
    private static volatile String sAppliedArtUrl = null;

    // Ruta en caché (fichero) de la última carátula guardada, para el widget.
    private static volatile String sAppliedArtPath = null;

    // Timestamp de la última actualización del widget (para limitar la
    // frecuencia de broadcasts mientras se actualiza la posición).
    private static volatile long sLastWidgetBroadcast = 0L;

    public static String appliedArtUrl() {
        return sAppliedArtUrl;
    }

    private static void setAppliedArtUrl(String url) {
        sAppliedArtUrl = url;
    }

    private static Handler handler() {
        if (sHandler == null) {
            synchronized (MediaService.class) {
                if (sHandler == null) {
                    sHandler = new Handler(Looper.getMainLooper());
                }
            }
        }
        return sHandler;
    }

    public static MediaService instance() {
        return sInstance;
    }

    /**
     * Guarda el estado de reproducción para el widget y (si no se acaba de
     * hacer hace menos de 1 s) envía un broadcast para que el widget se
     * actualice en pantalla.
     */
    private static void persistAndNotify(Context context, String title, String artist,
                                         String artworkUrl, String artworkPath,
                                         boolean playing, long positionMs, long durationMs,
                                         boolean force) {
        if (context == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (force || now - sLastWidgetBroadcast >= 1000L) {
            new MediaStateStore(context).setPlayback(title, artist, artworkUrl,
                    artworkPath, playing, positionMs, durationMs);
            sLastWidgetBroadcast = now;
            Intent update = new Intent(WalkmanWidgetProvider.ACTION_UPDATE)
                    .setPackage(context.getPackageName());
            context.sendBroadcast(update);
        }
    }

    public static void setCommandListener(CommandListener listener) {
        sCommandListener = listener;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        sInstance = this;
        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
        initMediaSession();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        mNotificationManager.createNotificationChannel(channel);
    }

    private void initMediaSession() {
        mSession = new MediaSessionCompat(this, "WalkmanMediaSession");
        mSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() { dispatch("play", null); }

            @Override
            public void onPause() { dispatch("pause", null); }

            @Override
            public void onSkipToNext() { dispatch("next", null); }

            @Override
            public void onSkipToPrevious() { dispatch("prev", null); }

            @Override
            public void onSeekTo(long pos) { dispatch("seek", String.valueOf(pos)); }

            @Override
            public void onStop() { stopPlayback(); }

            @Override
            public void onPlayFromMediaId(String mediaId, Bundle extras) {
                handlePlayFromMediaId(mediaId);
            }

            @Override
            public void onPlayFromSearch(String query, Bundle extras) {
                android.util.Log.i("WALKMAN_AA", "onPlayFromSearch query=" + query);
                handlePlayFromSearch(query);
            }

            @Override
            public void onSetRepeatMode(int mode) {
                sRepeatMode = mode;
                int m = (mode == PlaybackStateCompat.REPEAT_MODE_ONE) ? 2
                        : (mode == PlaybackStateCompat.REPEAT_MODE_ALL) ? 1 : 0;
                dispatch("repeat", String.valueOf(m));
            }

            @Override
            public void onSetShuffleMode(int mode) {
                sShuffleMode = mode;
                dispatch("shuffle", (mode == PlaybackStateCompat.SHUFFLE_MODE_ALL) ? "1" : "0");
            }
        });

        mSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, getString(R.string.app_name))
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, getString(R.string.default_artist))
                .build());

        setPlaybackState(PlaybackStateCompat.STATE_NONE, 0, 0);
        mSession.setActive(true);

        // Registrar el token de la sesión con MediaBrowserServiceCompat para
        // que Android Auto y otros clientes puedan conectarse.
        setSessionToken(mSession.getSessionToken());

        // Botones físicos / bluetooth / teclado.
        PendingIntent buttonIntent = PendingIntent.getBroadcast(this, 0,
                new Intent(Intent.ACTION_MEDIA_BUTTON).setClass(this, MediaButtonReceiver.class),
                PendingIntent.FLAG_IMMUTABLE);
        mSession.setMediaButtonReceiver(buttonIntent);
    }

    private void stopPlayback() {
        dispatch("stop", null);
        stopForeground(true);
        stopSelf();
    }

    /** Envía una orden hacia la web a través de la Activity. */
    private void dispatch(String command, String arg) {
        if (sCommandListener != null) {
            sCommandListener.onCommand(command, arg);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getAction() != null) {
            switch (intent.getAction()) {
                case ACTION_PLAY:
                    dispatch("play", null);
                    break;
                case ACTION_PAUSE:
                    dispatch("pause", null);
                    break;
                case ACTION_NEXT:
                    dispatch("next", null);
                    break;
                case ACTION_PREV:
                    dispatch("prev", null);
                    break;
                case ACTION_TOGGLE:
                    updateWidgetToggle();
                    break;
                case ACTION_STOP:
                    stopPlayback();
                    break;
                default:
                    MediaButtonReceiver.handleIntent(mSession, intent);
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    /** Alterna play/pausa según el estado actual guardado para el widget. */
    private void updateWidgetToggle() {
        MediaStateStore st = new MediaStateStore(this);
        dispatch(st.isPlaying() ? "pause" : "play", null);
    }

    // ─── Android Auto / MediaBrowserService ───────────────────────────────

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid,
                                 @Nullable Bundle rootHints) {
        Bundle extras = new Bundle();
        extras.putInt(
                "android.media.browse.content.style",
                0x1 /* CONTENT_STYLE_LIST */);
        extras.putInt(
                "android.media.browse.content.style.browsable",
                0x1 /* CONTENT_STYLE_LIST */);
        extras.putInt(
                "android.media.browse.content.style.playable",
                0x1 /* CONTENT_STYLE_LIST */);
        return new BrowserRoot(MEDIA_ROOT_ID, extras);
    }

    // ─── Menú "Cambiar biblioteca" (Android Auto) ───────────────────────

    /** Cambia la biblioteca activa del servidor y vuelve a empujar los datos. */
    private void selectLibrary(String libId) {
        if (libId == null || libId.isEmpty()) return;
        dispatch("select_library", libId);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId,
                               @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        if (parentId.startsWith("library:")) {
            selectLibrary(parentId.substring("library:".length()));
            result.sendResult(Collections.emptyList());
            return;
        }
        result.detach();
        PendingBrowse pending = mPendingBrowses.get(parentId);
        if (pending != null) {
            pending.results.add(result);
            return;
        }
        pending = new PendingBrowse();
        pending.results.add(result);
        mPendingBrowses.put(parentId, pending);
        PendingBrowse request = pending;
        pending.timeout = () -> finishBrowse(parentId, request, Collections.emptyList());
        handler().postDelayed(pending.timeout, 12_000L);
        readBrowse(parentId, pending);
    }

    private void readBrowse(String parentId, PendingBrowse pending) {
        BROWSE_EXECUTOR.execute(() -> {
            try {
                LibraryStore.readLocked(() -> {
                    List<MediaBrowserCompat.MediaItem> items = loadBrowseItems(parentId);
                    boolean loaded = MEDIA_ROOT_ID.equals(parentId) || LibraryStore.isLoaded(this, parentId);
                    handler().post(() -> {
                        if (sInstance != this || mPendingBrowses.get(parentId) != pending) return;
                        if (loaded || !items.isEmpty()) finishBrowse(parentId, pending, items);
                        // Conservar el indicador de carga hasta que el puente responda.
                        if (!loaded || MEDIA_ROOT_ID.equals(parentId)) requestBrowseRefresh(parentId);
                    });
                    return null;
                });
            } catch (Exception e) {
                Log.w(TAG, "No se pudo cargar " + parentId, e);
                handler().post(() -> finishBrowse(parentId, pending, Collections.emptyList()));
            }
        });
    }

    private void finishBrowse(String parentId, PendingBrowse pending,
                              List<MediaBrowserCompat.MediaItem> items) {
        if (mPendingBrowses.get(parentId) != pending) return;
        mPendingBrowses.remove(parentId);
        handler().removeCallbacks(pending.timeout);
        for (Result<List<MediaBrowserCompat.MediaItem>> result : pending.results) result.sendResult(items);
    }

    private void requestBrowseRefresh(String parentId) {
        String command = "refresh", arg = null;
        if (parentId.startsWith("album:")) {
            command = "refresh_tracks"; arg = "album|" + parentId.substring(6);
        } else if (parentId.startsWith("playlist:")) {
            command = "refresh_tracks"; arg = "playlist|" + parentId.substring(9);
        } else if (parentId.startsWith("artist:")) {
            String id = parentId.substring(7);
            command = "refresh_artist_albums";
            arg = id + "|" + LibraryStore.artistName(this, id);
        } else if (MEDIA_ID_QUEUE.equals(parentId)) {
            command = "refresh_queue";
        }
        String key = command + "|" + arg;
        long now = SystemClock.elapsedRealtime();
        Long last = mRefreshTimes.get(key);
        if (last != null && now - last < 5000L) return;
        if (mRefreshTimes.size() >= 128) mRefreshTimes.clear();
        mRefreshTimes.put(key, now);
        dispatch(command, arg);
    }

    /** Construye las listas fuera del hilo principal, con una lectura coherente. */
    private List<MediaBrowserCompat.MediaItem> loadBrowseItems(String parentId) {
        List<MediaBrowserCompat.MediaItem> items = new ArrayList<>();
        if (MEDIA_ROOT_ID.equals(parentId)) {
            items.add(mediaItem(MEDIA_ID_NOW_PLAYING, getString(R.string.aa_now_playing),
                    getString(R.string.app_name), "", MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            items.add(mediaItem(MEDIA_ID_PLAY_ALL, getString(R.string.aa_play_all),
                    getString(R.string.app_name), "", MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            items.add(mediaItem(MEDIA_ID_SHUFFLE, getString(R.string.aa_shuffle),
                    getString(R.string.app_name), "", MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
            items.add(mediaItem(MEDIA_ID_QUEUE, getString(R.string.aa_queue),
                    "", "", MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            items.add(mediaItem(MEDIA_ID_ALBUMS, getString(R.string.aa_albums),
                    "", "", MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            items.add(mediaItem(MEDIA_ID_PLAYLISTS, getString(R.string.aa_playlists),
                    "", "", MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            items.add(mediaItem(MEDIA_ID_ARTISTS, getString(R.string.aa_artists),
                    "", "", MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));
            items.add(mediaItem(MEDIA_ID_SERVERS, getString(R.string.aa_servers),
                    getString(R.string.aa_servers_subtitle), "",
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE));

        } else if (MEDIA_ID_ALBUMS.equals(parentId)) {
            items = LibraryStore.albums(this);
        } else if (MEDIA_ID_PLAYLISTS.equals(parentId)) {
            items = LibraryStore.playlists(this);
        } else if (MEDIA_ID_ARTISTS.equals(parentId)) {
            items = LibraryStore.artists(this);
        } else if (MEDIA_ID_SERVERS.equals(parentId)) {
            items = LibraryStore.libraries(this);
        } else if (MEDIA_ID_QUEUE.equals(parentId)) {
            items = LibraryStore.queue(this);
        } else if (parentId.startsWith("artist:")) {
            items = LibraryStore.artistSongs(this, parentId.substring(7));
        } else if (parentId.startsWith("album:")) {
            items = LibraryStore.tracks(this, parentId.substring(6), "album");
        } else if (parentId.startsWith("playlist:")) {
            items = LibraryStore.tracks(this, parentId.substring(9), "playlist");
        }
        return items;
    }

    /** Traduce el mediaId elegido en Android Auto a un comando para la web. */
    private void handlePlayFromMediaId(String mediaId) {
        if (mediaId == null) {
            dispatch("play", null);
            return;
        }
        if (mediaId.startsWith("queue:")) {
            String idx = mediaId.substring("queue:".length());
            if (!idx.isEmpty()) dispatch("play_queue", idx);
        } else if (mediaId.startsWith("library:")) {
            selectLibrary(mediaId.substring("library:".length()));
        } else if (MEDIA_ID_NOW_PLAYING.equals(mediaId)) {
            dispatch("play", null);
        } else if (MEDIA_ID_PLAY_ALL.equals(mediaId)) {
            dispatch("play_all", null);
        } else if (MEDIA_ID_SHUFFLE.equals(mediaId)) {
            // "Aleatorio" del menú: no activa shuffle sobre la cola actual
            // (play podría pausar), sino que reproduce TODA la biblioteca en
            // orden aleatorio, igual que el botón de la web.
            dispatch("play_shuffle", null);
        } else if (mediaId.startsWith("album:")) {
            dispatch("play_album", mediaId.substring("album:".length()));
        } else if (mediaId.startsWith("playlist:")) {
            dispatch("play_playlist", mediaId.substring("playlist:".length()));
        } else if (mediaId.startsWith("artist:")) {
            String id = mediaId.substring("artist:".length());
            dispatch("play_artist", id + "|" + LibraryStore.artistName(this, id));
        } else if (mediaId.startsWith("track:")) {
            // Formato: track:<kind>:<contenedor>:<pista>
            String arg = mediaId.substring("track:".length());
            dispatch("play_track", arg);
        } else {
            dispatch("play", null);
        }
    }

    /**
     * Búsqueda por voz (Google Asistente / Gemini). Resuelve el hilo actual
     * contra la caché; si no hay nada para esta consulta, lanza una búsqueda
     * por servidor para que la siguiente vez haya resultados.
     */
    @Override
    public void onSearch(@NonNull String query, @Nullable Bundle extras,
                         @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.detach();
        BROWSE_EXECUTOR.execute(() -> {
            List<MediaBrowserCompat.MediaItem> loaded;
            try {
                loaded = LibraryStore.readLocked(() -> {
                    List<MediaBrowserCompat.MediaItem> found = LibraryStore.searchResults(this, query);
                    return found.isEmpty() ? LibraryStore.search(this, query) : found;
                });
            } catch (Exception e) {
                Log.w(TAG, "No se pudo buscar en la biblioteca", e);
                loaded = Collections.emptyList();
            }
            List<MediaBrowserCompat.MediaItem> items = loaded;
            handler().post(() -> {
                if (sInstance == this && items.isEmpty() && query != null && !query.trim().isEmpty()) {
                    dispatch("search", query);
                }
                result.sendResult(items);
            });
        });
    }

    /** Inicia la reproducción de la mejor coincidencia por voz. */
    private void handlePlayFromSearch(String query) {
        if (query == null || query.trim().isEmpty()) {
            dispatch("play", null);
            return;
        }
        BROWSE_EXECUTOR.execute(() -> {
            LibraryStore.PlayTarget target = LibraryStore.readLocked(() -> LibraryStore.resolveBest(this, query));
            handler().post(() -> {
                if (sInstance != this) return;
                if (target != null) playTarget(target);
                else dispatch("play_search", query);
            });
        });
    }

    private void playTarget(LibraryStore.PlayTarget t) {
        switch (t.kind) {
            case "album":
                dispatch("play_album", t.id);
                break;
            case "playlist":
                dispatch("play_playlist", t.id);
                break;
            case "artist":
                dispatch("play_artist", t.id + "|" + t.name);
                break;
            default:
                dispatch("play", null);
                break;
        }
    }

    private static MediaBrowserCompat.MediaItem mediaItem(String mediaId, String title,
                                                          String subtitle, String iconUrl,
                                                          int flags) {
        MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                .setMediaId(mediaId)
                .setTitle(title);
        if (subtitle != null && !subtitle.isEmpty()) b.setSubtitle(subtitle);
        if (iconUrl != null && !iconUrl.isEmpty()) {
            try {
                b.setIconUri(Uri.parse(iconUrl));
            } catch (Exception ignored) {
            }
        }
        return new MediaBrowserCompat.MediaItem(b.build(), flags);
    }

    /**
     * Notifica a los clientes conectados (p. ej. Android Auto) que cambió el
     * contenido de una categoría tras recibir datos nuevos de la web.
     */
    public static void childrenChanged(@Nullable final String parentId) {
        MediaService svc = sInstance;
        if (svc == null) return;
        handler().post(() -> {
            if (sInstance != svc) return;
            try {
                if (parentId != null) {
                    PendingBrowse pending = svc.mPendingBrowses.get(parentId);
                    if (pending != null) svc.readBrowse(parentId, pending);
                    svc.notifyChildrenChanged(parentId);
                }
            } catch (Exception ignored) {
            }
        });
    }

    // ─── Fin Android Auto ─────────────────────────────────────────────────

    /**
     * Actualiza la MediaSession y la notificación con el estado notificado por
     * la web. Si el service es null (nunca arrancado), no hace nada.
     */
    public static void updateMedia(MediaService svc, String title, String artist,
                                   String album, String artworkUrl, boolean playing,
                                   long positionMs, long durationMs) {
        if (svc == null) return;
        // El puente JS usa su propio hilo: toda la sesión se modifica en main.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler().post(() -> updateMedia(svc, title, artist, album, artworkUrl,
                    playing, positionMs, durationMs));
            return;
        }
        if (svc != sInstance || svc.mSession == null) return;
        String url = artworkUrl == null ? "" : artworkUrl;
        boolean artChanged = !url.equals(svc.mRequestedArtUrl);
        MediaMetadataCompat previous = svc.mSession.getController().getMetadata();
        if (!artChanged && previous != null
                && Objects.equals(title, previous.getString(MediaMetadataCompat.METADATA_KEY_TITLE))
                && Objects.equals(artist, previous.getString(MediaMetadataCompat.METADATA_KEY_ARTIST))
                && Objects.equals(album, previous.getString(MediaMetadataCompat.METADATA_KEY_ALBUM))
                && durationMs == previous.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)) {
            updatePlayback(svc, playing, positionMs, durationMs);
        } else {
            MediaMetadataCompat.Builder meta = new MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                    .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                    .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
            if (!artChanged && previous != null) {
                meta.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART,
                        previous.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART));
            }
            if (artChanged) {
                ++sArtGeneration;
                svc.mRequestedArtUrl = url;
                svc.mArtworkPending = false;
                svc.mArtworkRetryAfter = 0;
                sAppliedArtPath = null;
                setAppliedArtUrl("");
            }
            svc.mSession.setMetadata(meta.build());
            svc.setPlaybackState(playing ? PlaybackStateCompat.STATE_PLAYING
                    : (durationMs > 0 ? PlaybackStateCompat.STATE_PAUSED
                                      : PlaybackStateCompat.STATE_NONE), positionMs, durationMs);
            svc.postNotification(playing);
            persistAndNotify(svc, title, artist, url, sAppliedArtPath,
                    playing, positionMs, durationMs, true);
        }
        if (url.isEmpty() || url.startsWith("data:") || url.startsWith("blob:")) {
            setAppliedArtUrl(url);
            return;
        }
        if (svc.mArtworkPending || url.equals(sAppliedArtUrl)
                || SystemClock.elapsedRealtime() < svc.mArtworkRetryAfter) return;
        svc.mArtworkPending = true;
        final long generation = sArtGeneration;
        EXECUTOR.execute(() -> {
            if (svc != sInstance || generation != sArtGeneration) return;
            Bitmap art = svc.downloadArtwork(url);
            if (svc != sInstance || generation != sArtGeneration) return;
            String path = art == null ? null : svc.saveArtworkToCache(art, generation);
            int accent = art == null ? AlbumArtColor.FALLBACK : AlbumArtColor.dominantColor(art);
            handler().post(() -> {
                if (svc != sInstance || generation != sArtGeneration) {
                    if (path != null) EXECUTOR.execute(() -> new File(path).delete());
                    return;
                }
                svc.mArtworkPending = false;
                if (art == null) {
                    svc.mArtworkRetryAfter = SystemClock.elapsedRealtime() + 30_000L;
                    return;
                }
                setAppliedArtUrl(url);
                sAppliedArtPath = path;
                new MediaStateStore(svc).setAccentColor(accent);
                // Conservamos título, pausa y posición recibidos durante la descarga.
                MediaMetadataCompat current = svc.mSession.getController().getMetadata();
                svc.mSession.setMetadata(new MediaMetadataCompat.Builder(current)
                        .putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art).build());
                PlaybackStateCompat state = svc.mSession.getController().getPlaybackState();
                boolean nowPlaying = state != null && state.getState() == PlaybackStateCompat.STATE_PLAYING;
                svc.postNotification(nowPlaying);
                persistAndNotify(svc,
                        current.getString(MediaMetadataCompat.METADATA_KEY_TITLE),
                        current.getString(MediaMetadataCompat.METADATA_KEY_ARTIST), url, path,
                        nowPlaying, state == null ? 0 : state.getPosition(),
                        current.getLong(MediaMetadataCompat.METADATA_KEY_DURATION), true);
            });
        });
    }

    /** No reiniciar descargas en curso; reintentar fallos con una pausa. */
    public boolean needsArtworkUpdate(String url) {
        return !url.equals(mRequestedArtUrl) || (!mArtworkPending
                && !url.equals(sAppliedArtUrl)
                && SystemClock.elapsedRealtime() >= mArtworkRetryAfter);
    }

    /** Actualiza estado y posición (para el seek bar del widget) sin recargar
     *  la carátula, llamada periódicamente desde la Activity. */
    public static void updatePlayback(MediaService svc, boolean playing,
                                      long positionMs, long durationMs) {
        if (svc == null || svc.mSession == null) return;
        int state = playing ? PlaybackStateCompat.STATE_PLAYING
                : (durationMs > 0 || svc.mSession.getController().getMetadata() != null
                        ? PlaybackStateCompat.STATE_PAUSED
                        : PlaybackStateCompat.STATE_NONE);
        PlaybackStateCompat previousState = svc.mSession.getController().getPlaybackState();
        boolean stateChanged = previousState == null || previousState.getState() != state;
        svc.setPlaybackState(state, positionMs, durationMs);
        if (stateChanged) svc.postNotification(playing);
        // Actualizamos el widget (el throttling interno evita broadcasts a cada
        // poll). Lo hacemos siempre que haya una pista cargada.
        MediaMetadataCompat meta = svc.mSession.getController().getMetadata();
        if (meta != null) {
            persistAndNotify(svc,
                    meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE),
                    meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST),
                    svc.mRequestedArtUrl, sAppliedArtPath,
                    playing, positionMs, durationMs,
                    stateChanged);
        }
    }

    /** Devuelve verdadero si la pista mostrada en la sesión es distinta. */
    public static boolean isDifferentTrack(MediaService svc, String title, String artist) {
        if (svc == null || svc.mSession == null) return true;
        MediaMetadataCompat meta = svc.mSession.getController().getMetadata();
        if (meta == null) return true;
        String cur = meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE);
        String curArtist = meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST);
        boolean sameTitle = cur != null && cur.equals(title);
        boolean sameArtist = curArtist != null && curArtist.equals(artist);
        return !(sameTitle && sameArtist);
    }

    private void setPlaybackState(int state, long positionMs, long durationMs) {
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PAUSE
                | PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP
                | PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
                | PlaybackStateCompat.ACTION_SET_REPEAT_MODE
                | PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE;

        PlaybackStateCompat.Builder b = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(state, positionMs, 1.0f);
        if (durationMs > 0) {
            b.setBufferedPosition(durationMs);
        }
        mSession.setPlaybackState(b.build());
        mSession.setShuffleMode(sShuffleMode);
        mSession.setRepeatMode(sRepeatMode);
    }

    /** Publica o actualiza la notificación multimedia (debe llamarse en main). */
    private void postNotification(boolean playing) {
        Notification notification = buildNotification();
        if (playing) {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } else {
            mNotificationManager.notify(NOTIFICATION_ID, notification);
        }
    }

    private Notification buildNotification() {
        int state = mSession.getController().getPlaybackState() != null
                ? mSession.getController().getPlaybackState().getState()
                : PlaybackStateCompat.STATE_NONE;
        boolean playing = state == PlaybackStateCompat.STATE_PLAYING;

        MediaMetadataCompat meta = mSession.getController().getMetadata();
        String title = meta != null ? meta.getString(MediaMetadataCompat.METADATA_KEY_TITLE) : getString(R.string.app_name);
        String artist = meta != null ? meta.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) : getString(R.string.default_artist);
        Bitmap art = meta != null ? meta.getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART) : null;

        Intent main = new Intent(this, MainActivity.class);
        main.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, main,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        PendingIntent prevPi = mediaButtonPi(1, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        PendingIntent playPausePi = mediaButtonPi(2, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        PendingIntent nextPi = mediaButtonPi(3, KeyEvent.KEYCODE_MEDIA_NEXT);
        PendingIntent cancelPi = PendingIntent.getService(this, 4,
                new Intent(this, MediaService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(artist)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPi)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(new MediaStyle()
                        .setMediaSession(mSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2)
                        .setShowCancelButton(true))
                .setDeleteIntent(cancelPi)
                .addAction(new NotificationCompat.Action(
                        R.drawable.ic_skip_previous, getString(R.string.prev), prevPi))
                .addAction(new NotificationCompat.Action(
                        playing ? R.drawable.ic_pause : R.drawable.ic_play,
                        getString(playing ? R.string.pause : R.string.play), playPausePi))
                .addAction(new NotificationCompat.Action(
                        R.drawable.ic_skip_next, getString(R.string.next), nextPi));

        if (art != null) {
            b.setLargeIcon(art);
        }
        return b.build();
    }

    private PendingIntent mediaButtonPi(int reqCode, int keyCode) {
        Intent i = new Intent(this, MediaButtonReceiver.class).setAction(Intent.ACTION_MEDIA_BUTTON);
        i.putExtra(Intent.EXTRA_KEY_EVENT,
                new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        return PendingIntent.getBroadcast(this, reqCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Descarga la carátula desde una URL remota (se llama en segundo plano). */
    private Bitmap downloadArtwork(String url) {
        HttpURLConnection conn = null;
        File download = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "WalkmanAndroid/1.0");
            conn.setRequestProperty("Accept", "image/*");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            download = File.createTempFile("artwork-", ".tmp", getCacheDir());
            try (InputStream in = conn.getInputStream();
                 FileOutputStream out = new FileOutputStream(download)) {
                byte[] buffer = new byte[8192];
                int total = 0, count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > 16 * 1024 * 1024) return null;
                    out.write(buffer, 0, count);
                }
            }
            return ArtworkDecoder.decodeFile(download.getAbsolutePath(), 512);
        } catch (Exception e) {
            Log.w(TAG, "No se pudo cargar la carátula", e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
            if (download != null) download.delete();
        }
    }

    /** Guarda la carátula en un fichero de caché para que el widget pueda
     *  mostrarla sin volver a descargarla (se llama en segundo plano). */
    private String saveArtworkToCache(Bitmap art, long generation) {
        try {
            File dir = new File(getCacheDir(), "artwork");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File[] oldFiles = dir.listFiles();
            if (oldFiles != null) {
                for (File old : oldFiles) {
                    if (old.getName().startsWith("current") && old.getName().endsWith(".png")) old.delete();
                }
            }
            File out = new File(dir, "current-" + generation + ".png");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                art.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            Log.w(TAG, "No se pudo guardar la carátula en caché", e);
            return null;
        }
    }

    @Override
    public void onDestroy() {
        for (String parent : new ArrayList<>(mPendingBrowses.keySet())) {
            finishBrowse(parent, mPendingBrowses.get(parent), Collections.emptyList());
        }
        sInstance = null;
        ++sArtGeneration;
        sAppliedArtUrl = null;
        sAppliedArtPath = null;
        if (mSession != null) {
            mSession.release();
        }
        super.onDestroy();
    }
}
