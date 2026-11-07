package com.djmanri3.Walkman;

import android.os.Handler;
import android.os.Looper;
import androidx.media.BrowseTestResult;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

/** Run on Android with app_process and the app APK on CLASSPATH. */
public final class AndroidAutoLoadSmokeTest {
    private static Handler handler;
    private static TestService service;
    private static int refreshes;
    private static int received;
    private static File directory;
    private static class TestService extends MediaService {
        @Override public File getFilesDir() { return directory; }
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            failure.printStackTrace(); System.exit(1);
        });
        Looper.prepareMainLooper();
        handler = new Handler(Looper.getMainLooper());
        directory = new File("/data/local/tmp/walkman-aa-test-" + android.os.Process.myPid());
        check(directory.mkdirs(), "Test directory");
        service = new TestService();
        Field instance = MediaService.class.getDeclaredField("sInstance");
        instance.setAccessible(true);
        instance.set(null, service);
        MediaService.setCommandListener((command, arg) -> {
            check(Looper.myLooper() == Looper.getMainLooper(), "Commands must run on main");
            if (command.equals("refresh")) {
                refreshes++;
                handler.postDelayed(() -> {
                    LibraryStore.setPlaylists(service, new JSONArray());
                    MediaService.childrenChanged("walkman_playlists");
                }, 100);
            }
        });
        Field data = LibraryStore.class.getDeclaredField("sData");
        data.setAccessible(true);
        data.set(null, new JSONObject().put("playlists", new JSONArray()));
        check(!LibraryStore.isLoaded(service, "walkman_playlists"), "Old empty cache must request fresh data");
        BrowseTestResult first = emptyResult();
        BrowseTestResult second = emptyResult();
        service.onLoadChildren("walkman_playlists", first);
        service.onLoadChildren("walkman_playlists", second);
        check(received == 0, "Missing data must remain loading");
        handler.postDelayed(() -> { throw new AssertionError("Test timed out"); }, 25000);
        Looper.loop();
    }
    private static BrowseTestResult emptyResult() {
        return new BrowseTestResult(items -> {
            check(items.isEmpty(), "Loaded empty category is valid");
            if (++received == 2) {
                check(refreshes == 1, "Concurrent subscriptions must share one request");
                service.onLoadChildren("walkman_playlists", new BrowseTestResult(cached -> {
                    check(cached.isEmpty() && refreshes == 1, "Cached empty category must not reload");
                    try { testLargeLibrary(); } catch (Exception e) { throw new RuntimeException(e); }
                }));
            }
        });
    }
    private static void testLargeLibrary() throws Exception {
        JSONArray albums = new JSONArray();
        for (int i = 4999; i >= 0; i--) {
            albums.put(new JSONObject().put("id", "a" + i).put("name", String.format("Album %04d", i)));
        }
        LibraryStore.setAlbums(service, albums);
        LibraryStore.setTracks(service, "empty", new JSONArray());
        check(LibraryStore.isLoaded(service, "album:empty"), "Empty tracks are loaded");
        boolean[] mainResponsive = {false};
        service.onLoadChildren("walkman_albums", new BrowseTestResult(items -> {
            check(mainResponsive[0], "Browse must detach and keep main responsive");
            check(items.size() == 500, "Binder-safe browse limit");
            check(items.get(0).getDescription().getTitle().toString().equals("Album 0000"), "Alphabetical order");
            handler.postDelayed(() -> {
                try {
                    Field data = LibraryStore.class.getDeclaredField("sData");
                    data.setAccessible(true);
                    data.set(null, null);
                    check(LibraryStore.isLoaded(service, "walkman_playlists"), "Persist empty category across restart");
                    check(LibraryStore.isLoaded(service, "album:empty"), "Persist empty album across restart");
                    check(LibraryStore.resolveBest(service, "Album 4999") != null, "Persistence must retain uncapped library");
                    testTimeout();
                } catch (Exception e) { throw new RuntimeException(e); }
            }, 800);
        }));
        handler.post(() -> mainResponsive[0] = true);
    }
    private static void testTimeout() {
        long start = android.os.SystemClock.elapsedRealtime();
        AtomicInteger sends = new AtomicInteger();
        service.onLoadChildren("album:never-replies", new BrowseTestResult(items -> {
            check(sends.incrementAndGet() == 1, "Only one completion per detached result");
            check(items.isEmpty(), "Missing server has bounded wait");
            check(android.os.SystemClock.elapsedRealtime() - start >= 11000, "Do not show empty before waiting");
            LibraryStore.setTracks(service, "never-replies", new JSONArray());
            MediaService.childrenChanged("album:never-replies");
            handler.postDelayed(() -> {
                check(sends.get() == 1, "Late data must not complete the old result again");
                for (File file : directory.listFiles()) file.delete();
                directory.delete();
                System.out.println("PASS: shared detached loads, empty cache, 5000 albums, responsive main, persistence, timeout and late data");
                System.exit(0);
            }, 500);
        }));
    }
}
