package com.djmanri3.Walkman;

import android.graphics.Bitmap;
import java.io.File;
import java.io.FileOutputStream;

public class ArtworkSmokeTest {
    public static void main(String[] args) throws Exception {
        check(4096, 2048, 512, 512, 256);
        check(512, 4096, 256, 32, 256);
        check(1, 4096, 256, 1, 256);
        check(32, 16, 256, 32, 16);
        if (ArtworkDecoder.decodeFile("/data/local/tmp/missing-walkman-image", 256) != null)
            throw new AssertionError("Missing file must return null");
        System.out.println("PASS: landscape, portrait, extreme aspect ratio, small image and missing file");
    }
    private static void check(int w, int h, int max, int expectedW, int expectedH) throws Exception {
        File file = File.createTempFile("walkman-art-test-", ".png", new File("/data/local/tmp"));
        Bitmap original = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        original.eraseColor(0xffcc2244);
        try (FileOutputStream out = new FileOutputStream(file)) {
            original.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        original.recycle();
        Bitmap result = ArtworkDecoder.decodeFile(file.getPath(), max);
        if (result == null || result.getWidth() != expectedW || result.getHeight() != expectedH)
            throw new AssertionError("Wrong output dimensions for " + w + "x" + h);
        if (AlbumArtColor.dominantColor(result) == AlbumArtColor.FALLBACK)
            throw new AssertionError("Missing dominant color for " + w + "x" + h);
        result.recycle();
        file.delete();
    }
}
