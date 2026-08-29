package com.djmanri3.Walkman;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.RemoteViews;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/**
 * Widget de pantalla de inicio para controlar la reproducción. Muestra título,
 * artista, carátula, barra de progreso y botones anterior/play-pausa/siguiente.
 * Cuando hay reproducción activa, un efecto de partículas sube desde la barra
 * de progreso hacia la zona superior (ver {@link ParticleRenderer}).
 *
 * <p>Los widgets no pueden inflar vistas personalizadas, así que el efecto se
 * dibuja como frames ({@link Bitmap}) en el proceso de la app y se publica en
 * un ImageView estándar usando updates parciales ({@code partiallyUpdateAppWidget}).</p>
 *
 * <p>Los botones envían pulsaciones de tecla multimedia a
 * {@link androidx.media.session.MediaButtonReceiver}, que las dirige a la
 * {@link MediaSessionCompat} del {@link MediaService}; desde ahí los comandos
 * llegan al JavaScript de la web vía {@link MainActivity}.</p>
 */
public class WalkmanWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_UPDATE = "com.djmanri3.Walkman.action.WIDGET_UPDATE";

    private static final long FRAME_MS = 90L;
    private static final Object LOCK = new Object();
    private static final Set<Integer> sWidgetIds = new HashSet<>();
    private static final float START_X_FRAC = 0.23f;
    private static ParticleRenderer sRenderer;
    private static boolean sAnimating = false;
    private static boolean sPlaying = false;
    private static int sAccentColor = 0xFFAA00FF;
    private static float sProgressFrac = 0f;
    private static long sDuration = 0L;
    private static float sBaseProgress = 0f;
    private static long sBaseProgressMs = 0L;

    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    private static final Runnable sAnimation = new Runnable() {
        @Override
        public void run() {
            synchronized (LOCK) {
                if (!sAnimating) {
                    return;
                }
                if (sRenderer == null) {
                    sRenderer = new ParticleRenderer();
                }
                // Avanza el progreso de forma fluida según el tiempo real para
                // que las partículas estén sincronizadas con la barra.
                if (sPlaying && sDuration > 0) {
                    long now = SystemClock.elapsedRealtime();
                    sProgressFrac = Math.min(1f,
                            sBaseProgress + (float) (now - sBaseProgressMs) / sDuration);
                } else {
                    sProgressFrac = sBaseProgress;
                }
                AppWidgetManager mgr = AppWidgetManager.getInstance(sAppContext);
                RemoteViews partial = new RemoteViews(
                        sAppContext.getPackageName(), R.layout.walkman_widget);
                Bitmap frame = sRenderer.nextFrame(320, 300, sPlaying, sAccentColor, sProgressFrac, START_X_FRAC);
                if (frame != null) {
                    partial.setImageViewBitmap(R.id.widget_particles, frame);
                    for (int id : sWidgetIds) {
                        mgr.partiallyUpdateAppWidget(id, partial);
                    }
                }
                if (sAnimating) {
                    sHandler.postDelayed(sAnimation, FRAME_MS);
                }
            }
        }
    };

    private static Context sAppContext;

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            sAppContext = app;
            for (int id : appWidgetIds) {
                sWidgetIds.add(id);
            }
        }
        for (int id : appWidgetIds) {
            updateWidget(context, appWidgetManager, id);
        }
        updateAnimation();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_UPDATE.equals(intent.getAction())) {
            AppWidgetManager mgr = AppWidgetManager.getInstance(context);
            ComponentName cn = new ComponentName(context, WalkmanWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            Context app = context.getApplicationContext();
            synchronized (LOCK) {
                sAppContext = app;
                for (int id : ids) {
                    sWidgetIds.add(id);
                }
            }
            for (int id : ids) {
                updateWidget(context, mgr, id);
            }
            updateAnimation();
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        synchronized (LOCK) {
            for (int id : appWidgetIds) {
                sWidgetIds.remove(id);
            }
        }
        updateAnimation();
    }

    @Override
    public void onDisabled(Context context) {
        synchronized (LOCK) {
            sWidgetIds.clear();
            sAnimating = false;
            sHandler.removeCallbacks(sAnimation);
        }
    }

    private static synchronized void updateAnimation() {
        synchronized (LOCK) {
            MediaStateStore st = sAppContext != null ? new MediaStateStore(sAppContext) : null;
            sPlaying = st != null && st.isPlaying();
            boolean shouldRun = sPlaying && !sWidgetIds.isEmpty();
            if (shouldRun && !sAnimating) {
                sAnimating = true;
                sHandler.removeCallbacks(sAnimation);
                sHandler.post(sAnimation);
            } else if (!shouldRun && sAnimating) {
                sAnimating = false;
                sHandler.removeCallbacks(sAnimation);
            }
        }
    }

    /** Reconstruye y publica el widget con el estado actual de reproducción. */
    public static void updateWidget(Context context, AppWidgetManager mgr, int widgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.walkman_widget);

        MediaStateStore st = new MediaStateStore(context);

        String title = st.getTitle();
        String artist = st.getArtist();
        boolean playing = st.isPlaying();
        long position = st.getPosition();
        long duration = st.getDuration();

        views.setTextViewText(R.id.widget_title,
                title != null && !title.isEmpty() ? title : context.getString(R.string.app_name));
        views.setTextViewText(R.id.widget_artist,
                artist != null && !artist.isEmpty() ? artist
                        : context.getString(R.string.default_artist));

        // Barra de progreso renderizada como bitmap (el ProgressBar nativo no
        // admite tinte dinámico por RemoteViews en este launcher). Se dibuja el
        // track y el relleno con el color dominante de la carátula.
        int accent = st.getAccentColor();
        synchronized (LOCK) {
            sAccentColor = accent;
        }
        // Título con el color dominante de la carátula (setTextColor es nativo
        // de RemoteViews; a diferencia del tint del ProgressBar, sí funciona).
        views.setTextColor(R.id.widget_title, accent);
        // Logo teñido con el color dominante de la carátula.
        views.setInt(R.id.widget_logo, "setColorFilter", accent);
        float progressFrac = 0f;
        if (duration > 0) {
            progressFrac = (float) position / duration;
        }
        synchronized (LOCK) {
            sProgressFrac = progressFrac;
            sDuration = duration;
            sBaseProgress = progressFrac;
            sBaseProgressMs = SystemClock.elapsedRealtime();
        }
        synchronized (LOCK) {
            if (sRenderer == null) {
                sRenderer = new ParticleRenderer();
            }
            Bitmap bar = sRenderer.progressFrame(600, 12, progressFrac, accent);
            if (bar != null) {
                views.setImageViewBitmap(R.id.widget_progress, bar);
            }
        }

        // Carátula en caché (guardada por MediaService) si existe.
        String artPath = st.getArtworkPath();
        if (artPath != null && !artPath.isEmpty()) {
            Bitmap art = decodeArtwork(artPath);
            if (art != null) {
                views.setImageViewBitmap(R.id.widget_art, art);
            } else {
                views.setImageViewResource(R.id.widget_art, R.drawable.ic_walkman);
            }
        } else {
            views.setImageViewResource(R.id.widget_art, R.drawable.ic_walkman);
        }

        // Botones -> MediaService con acciones explícitas (más fiable que el
        // routing de media buttons del sistema).
        views.setOnClickPendingIntent(R.id.widget_prev,
                mediaServicePi(context, 11, MediaService.ACTION_PREV));
        views.setOnClickPendingIntent(R.id.widget_play,
                mediaServicePi(context, 12, MediaService.ACTION_TOGGLE));
        views.setOnClickPendingIntent(R.id.widget_next,
                mediaServicePi(context, 13, MediaService.ACTION_NEXT));

        // Icono play/pausa.
        views.setImageViewResource(R.id.widget_play,
                playing ? R.drawable.ic_pause : R.drawable.ic_play);

        // Primer frame de partículas (si procede).
        synchronized (LOCK) {
            if (sRenderer == null) {
                sRenderer = new ParticleRenderer();
            }
            Bitmap frame = sRenderer.nextFrame(320, 300, playing, accent, progressFrac, START_X_FRAC);
            if (frame != null) {
                views.setImageViewBitmap(R.id.widget_particles, frame);
            }
        }

        mgr.updateAppWidget(widgetId, views);
    }

    private static PendingIntent mediaServicePi(Context context, int reqCode, String action) {
        Intent i = new Intent(context, MediaService.class).setAction(action);
        return PendingIntent.getService(context, reqCode, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static Bitmap decodeArtwork(String path) {
        try {
            File f = new File(path);
            if (!f.exists()) {
                return null;
            }
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (bmp != null && bmp.getWidth() > 256) {
                int h = (int) (bmp.getHeight() * (256.0 / bmp.getWidth()));
                return Bitmap.createScaledBitmap(bmp, 256, h, true);
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
