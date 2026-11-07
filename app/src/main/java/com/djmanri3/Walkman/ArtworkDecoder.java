package com.djmanri3.Walkman;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Decodifica sólo la resolución necesaria, incluso para carátulas muy grandes. */
final class ArtworkDecoder {
    private ArtworkDecoder() { }

    static Bitmap decodeFile(String path, int maxSide) {
        if (maxSide <= 0) throw new IllegalArgumentException("maxSide debe ser positivo");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int side = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (side / options.inSampleSize > (long) maxSide * 2) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        Bitmap decoded = BitmapFactory.decodeFile(path, options);
        if (decoded == null) return null;
        int decodedSide = Math.max(decoded.getWidth(), decoded.getHeight());
        if (decodedSide <= maxSide) return decoded;
        float scale = (float) maxSide / decodedSide;
        Bitmap scaled = Bitmap.createScaledBitmap(decoded,
                Math.max(1, Math.round(decoded.getWidth() * scale)),
                Math.max(1, Math.round(decoded.getHeight() * scale)), true);
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }
}
