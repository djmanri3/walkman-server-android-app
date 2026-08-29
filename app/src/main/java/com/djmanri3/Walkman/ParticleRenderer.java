package com.djmanri3.Walkman;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

import java.util.Random;

/**
 * Genera, en el proceso de la app, los frames (bitmaps) del efecto de
 * partículas que suben desde la barra de reproducción (base) hacia la zona
 * superior del widget. Como un AppWidget no puede inflar vistas personalizadas,
 * el {@link WalkmanWidgetProvider} produce estos bitmaps con
 * {@link #nextFrame(int, int)} y los publica en un ImageView estándar.
 */
public class ParticleRenderer {

    private static final int MAX_PARTICLES = 220;

    // Paleta generada a partir del color dominante de la carátula.
    private static final int[][] COLORS = new int[4][3];

    private final Particle[] mParticles = new Particle[MAX_PARTICLES];
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random mRandom = new Random();
    private long mFrame = 0;
    private int mLastW = -1;
    private int mLastH = -1;
    private int mLastAccent = 0;
    private float mSpawnWidth = 0f;
    private float mStartX = 0f;

    private static class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float size;
        int color;
        int age;
        int life;
    }

    private void ensure(int w, int h, int accent) {
        if (accent != mLastAccent) {
            buildPalette(accent);
            mLastAccent = accent;
        }
        if (w == mLastW && h == mLastH && mParticles[0] != null) {
            return;
        }
        mLastW = w;
        mLastH = h;
        for (int i = 0; i < MAX_PARTICLES; i++) {
            Particle p = new Particle();
            respawn(p, w, h, true);
            mParticles[i] = p;
        }
    }

    /** Construye la paleta de partículas a partir del color dominante. */
    private void buildPalette(int accent) {
        int r = (accent >> 16) & 0xFF;
        int g = (accent >> 8) & 0xFF;
        int b = accent & 0xFF;
        // Aclara cada color hacia el blanco para que las partículas sean
        // brillantes y visibles sobre el fondo oscuro.
        COLORS[0][0] = mix(r, 255, 0.55f);  COLORS[0][1] = mix(g, 255, 0.55f);  COLORS[0][2] = mix(b, 255, 0.55f);
        COLORS[1][0] = mix(r, 255, 0.80f);  COLORS[1][1] = mix(g, 255, 0.80f);  COLORS[1][2] = mix(b, 255, 0.80f);
        COLORS[2][0] = mix(r, 255, 0.30f);  COLORS[2][1] = mix(g, 255, 0.30f);  COLORS[2][2] = mix(b, 255, 0.30f);
        COLORS[3][0] = 255;                 COLORS[3][1] = 255;                 COLORS[3][2] = 255;
    }

    private static int mix(int a, int b, float t) {
        return (int) (a + (b - a) * t);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private void respawn(Particle p, int w, int h, boolean scatter) {
        // Las partículas nacen justo donde empieza la barra de progreso
        // (mStartX, tras la carátula) y dentro del área ya reproducida.
        float sx = Math.max(mStartX, mSpawnWidth);
        if (sx > mStartX) {
            p.x = mStartX + mRandom.nextFloat() * (sx - mStartX);
        } else {
            p.x = mStartX;
        }
        p.y = scatter ? mRandom.nextFloat() * h : h * (0.92f + mRandom.nextFloat() * 0.07f);
        float base = (h <= 0) ? 1f : h / 320f;
        p.vy = (0.5f + mRandom.nextFloat() * 1.1f) * base;
        p.vx = (mRandom.nextFloat() - 0.5f) * base * 0.4f;
        p.size = 1f + mRandom.nextFloat() * 1.5f; // más pequeñas (1-2.5px)
        p.age = 0;
        p.life = 40 + mRandom.nextInt(50);
        p.color = mRandom.nextInt(COLORS.length);
    }

    /**
     * Devuelve un bitmap (con canal alfa transparente) que dibuja las
     * partículas en su posición del frame actual. Si hayReproduccion es
     * false devuelve un bitmap transparente (sin partículas). El color de las
     * partículas procede del color dominante de la carátula (accent).
     */
    public Bitmap nextFrame(int w, int h, boolean hayReproduccion, int accent,
                            float progress, float startXFrac) {
        if (w <= 0 || h <= 0) {
            return null;
        }
        float startXF = Math.max(0f, Math.min(1f, startXFrac));
        mStartX = w * startXF;
        // El ancho de la zona de partículas crece con el progreso pero
        // proporcional al ancho útil (desde el inicio de la barra hasta el
        // final), igual que la barra de progreso, para mantener sincronía.
        float pClamped = Math.max(0f, Math.min(1f, progress));
        mSpawnWidth = mStartX + pClamped * (w - mStartX);
        ensure(w, h, accent);
        mFrame++;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        if (!hayReproduccion) {
            return bmp; // transparente
        }

        // Número de partículas proporcional al progreso de la canción: pocas
        // al empezar y más según avanza la reproducción.
        float pFrac = Math.max(0f, Math.min(1f, progress));
        int active = (int) (MAX_PARTICLES * Math.max(0.10f, pFrac));

        for (int i = 0; i < active; i++) {
            Particle p = mParticles[i];
            p.y -= p.vy;
            p.x += p.vx;
            p.age++;
            // Si la partícula sale del área de reproducción (por el viento X),
            // la devolvemos a la zona reproducida a partir del inicio de la barra.
            if (p.y < -p.size || p.age > p.life) {
                respawn(p, w, h, false);
            }
            if (p.x < mStartX - p.size || p.x > mSpawnWidth + p.size) {
                p.x = mStartX + mRandom.nextFloat() * Math.max(0f, mSpawnWidth - mStartX);
            }

            // Más brillantes cerca de la base; se desvanecen al subir.
            float fade = Math.min(1f, (h - p.y) / (float) h);
            float a = fade * fade * (3f - 2f * fade);
            int alpha = (int) (255f * (0.70f + 0.30f * a));
            if (alpha <= 0) {
                continue;
            }
            int[] rgb = COLORS[p.color];
            mPaint.setARGB(alpha, rgb[0], rgb[1], rgb[2]);
            // Píxel cuadrado pequeño (estilo pixel art), rotado 45°.
            float half = p.size / 2f;
            canvas.save();
            canvas.translate(p.x, p.y);
            canvas.rotate(45f);
            canvas.drawRect(-half, -half, half, half, mPaint);
            canvas.restore();
        }
        return bmp;
    }

    /**
     * Dibuja la barra de progreso (track translúcido + relleno del color
     * dominante) en un bitmap pequeño que se publica en un ImageView estándar
     * con {@code fitXY}; la fracción {@code progress} (0..1) se escala
     * proporcionalmente al ancho real. Evita el ProgressBar nativo porque el
     * launcher no admite su tinte dinámico por RemoteViews.
     */
    public Bitmap progressFrame(int w, int h, float progress, int accent) {
        if (w <= 0 || h <= 0) {
            return null;
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        float radius = h / 2f;

        Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        track.setColor(0x33FFFFFF);
        canvas.drawRoundRect(0, 0, w, h, radius, radius, track);

        if (progress > 0f) {
            Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
            fill.setColor(accent);
            float fillWidth = w * Math.max(0f, Math.min(1f, progress));
            canvas.drawRoundRect(0, 0, fillWidth, h, radius, radius, fill);
        }
        return bmp;
    }
}
