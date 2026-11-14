package com.djmanri3.Walkman;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.*;
import java.net.*;
import java.util.*;

/** Run with dalvikvm -Xmx64m and disk-only; artwork totals 300 MB. */
public final class LargeArtworkSmokeTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        File directory = new File("/data/local/tmp/walkman-large-art-" + android.os.Process.myPid());
        check(directory.mkdirs(), "Create isolated test directory");
        Context context = new ContextWrapper(null) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return directory; }
        };
        MiniHttpServer server = new MiniHttpServer(context);
        try {
            for (int i = 0; i < 100; i++) {
                byte[] cover = new byte[3 * 1024 * 1024];
                Arrays.fill(cover, (byte) (i + 1));
                server.addBytes("c" + i, cover);
            }
            check(server.artworkFor("c99").length() == 3 * 1024 * 1024, "Persist full artwork");
            // Restore references as LocalLibraryCache does, without reading images into memory.
            MiniHttpServer restored = new MiniHttpServer(context);
            for (int i = 0; i < 100; i++) restored.addArtwork("c" + i, server.artworkFor("c" + i));
            server = restored;
            if (args.length > 0 && args[0].equals("disk-only")) {
                System.out.println("PASS: 300 MB artwork imported and restored with "
                        + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB heap");
                return;
            }
            check(server.start(), "Start HTTP server");
            URL url = new URL(server.baseUrl() + "c99");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            check(connection.getResponseCode() == 200, "GET artwork");
            check(connection.getContentLength() == 3 * 1024 * 1024, "Full response length");
            int count = 0;
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    for (int j = 0; j < n; j++) check(buffer[j] == 100, "Artwork content");
                    count += n;
                }
            } finally { connection.disconnect(); }
            check(count == 3 * 1024 * 1024, "Complete HTTP body");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestProperty("Range", "bytes=100-199");
            check(connection.getResponseCode() == 206 && connection.getContentLength() == 100, "Range response");
            try (InputStream in = connection.getInputStream()) {
                for (int i = 0; i < 100; i++) check(in.read() == 100, "Range content");
                check(in.read() == -1, "Range ends correctly");
            } finally { connection.disconnect(); }
            System.out.println("PASS: 300 MB artwork imported and restored with "
                    + Runtime.getRuntime().maxMemory() / 1024 / 1024 + " MB heap; HTTP GET and Range");
        } finally {
            server.stop();
            File artwork = new File(directory, "local-library/artwork");
            File[] files = artwork.listFiles();
            if (files != null) for (File file : files) file.delete();
            artwork.delete(); artwork.getParentFile().delete(); directory.delete();
        }
        System.exit(0);
    }
}
