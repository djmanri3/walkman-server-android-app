package com.djmanri3.Walkman;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * Extrae el color dominante de una carátula (bitmap) para poder teñir la
 * barra de progreso del widget y las partículas con el color principal del
 * álbum que se está reproduciendo.
 */
public final class AlbumArtColor {

    private AlbumArtColor() {
    }

    /** Color de respaldo si no se puede extraer. */
    public static final int FALLBACK = 0xFFAA00FF;

    /**
     * Devuelve el color dominante de la carátula (en formato ARGB) usando un
     * histograma cuantizado sobre una muestra reducida del bitmap. Ignora los
     * píxeles casi negros/casi blancos para no tildar la interfaz.
     */
    public static int dominantColor(Bitmap src) {
        if (src == null) {
            return FALLBACK;
        }
        try {
            // Reducimos la muestra para ir rápido (p.ej. 24x24).
            int w = Math.max(1, src.getWidth());
            int h = Math.max(1, src.getHeight());
            int sw = 24;
            int sh = Math.max(1, (int) (h * ((float) sw / w)));
            Bitmap small = Bitmap.createScaledBitmap(src, sw, sh, true);
            int[] pixels = new int[sw * sh];
            small.getPixels(pixels, 0, sw, 0, 0, sw, sh);
            if (small != src) {
                small.recycle();
            }

            long[] sums = new long[3]; // acumuladores RGB
            long count = 0;
            for (int p : pixels) {
                int a = (p >>> 24) & 0xFF;
                if (a < 60) {
                    continue;
                }
                int r = (p >> 16) & 0xFF;
                int g = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                // Descartar casi negro o casi blanco (sin "color").
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max - min < 12 || max < 20) {
                    continue;
                }
                sums[0] += r;
                sums[1] += g;
                sums[2] += b;
                count++;
            }
            if (count == 0) {
                return FALLBACK;
            }
            int r = (int) (sums[0] / count);
            int g = (int) (sums[1] / count);
            int b = (int) (sums[2] / count);
            // Un poco más de saturación para que se vea vivo sobre fondo oscuro.
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int satBoost = (max - min) / 2;
            r = clamp(r + satBoost);
            g = clamp(g + satBoost);
            b = clamp(b + satBoost);
            return Color.rgb(r, g, b);
        } catch (Exception e) {
            return FALLBACK;
        }
    }

    /** Oscurece/clarea un color: amt negativo oscurece, positivo aclara. */
    public static int shift(int color, int amt) {
        int r = clamp(((color >> 16) & 0xFF) + amt);
        int g = clamp(((color >> 8) & 0xFF) + amt);
        int b = clamp((color & 0xFF) + amt);
        return Color.rgb(r, g, b);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
