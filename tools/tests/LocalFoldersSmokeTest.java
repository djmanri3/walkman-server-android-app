package com.djmanri3.Walkman;

import android.net.Uri;
import androidx.documentfile.provider.DocumentFile;
import androidx.documentfile.provider.FolderTestEntry;
import java.lang.reflect.Method;
import java.util.*;

/** Runs on Android without installing the APK or touching the user's library. */
public final class LocalFoldersSmokeTest {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        FolderTestEntry a = new FolderTestEntry("song.flac", false), b = new FolderTestEntry("song.flac", false);
        FolderTestEntry root = new FolderTestEntry("direct.mp3", false), coverA = new FolderTestEntry("cover.jpg", false), coverB = new FolderTestEntry("cover.jpg", false);
        FolderTestEntry[] entries = {
            new FolderTestEntry("Artist A", true, new FolderTestEntry("Album", true, a, coverA)),
            new FolderTestEntry("Artist B", true, new FolderTestEntry("Album", true, b, coverB)), root,
            new FolderTestEntry("Music", true, new FolderTestEntry("Álbum #1", true, new FolderTestEntry("100% + música.flac", false)))
        };
        Map<String, List<DocumentFile>> audio = new HashMap<>(), covers = new HashMap<>();
        Map<Uri, String> paths = new HashMap<>();
        Method collect = LocalMusicPicker.class.getDeclaredMethod("collect", DocumentFile[].class, String.class,
            Map.class, Map.class, List.class, Map.class);
        collect.setAccessible(true);
        collect.invoke(null, entries, "", audio, covers, new ArrayList<>(), paths);
        check(paths.get(a.getUri()).equals("Artist A/Album/song.flac"), "First path");
        check(paths.get(b.getUri()).equals("Artist B/Album/song.flac"), "Duplicate album path");
        check(paths.get(root.getUri()).equals("direct.mp3"), "Root song");
        check(paths.containsValue("Music/Álbum #1/100% + música.flac"), "Unicode and special characters");
        check(covers.get("Artist A/Album").get(0) == coverA, "First folder artwork");
        check(covers.get("Artist B/Album").get(0) == coverB, "Second folder artwork");
        check(audio.size() == 4, "Distinct folders with opaque document IDs");
        Uri tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AMusic");
        Uri doc = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusic%2FArtist%20A%2FAlbum%2Fsong.flac");
        check("Artist A/Album/song.flac".equals(LocalLibraryCache.relativeDocumentPath(tree, doc)), "Old cache migration");
        check(LocalLibraryCache.relativeDocumentPath(tree, Uri.parse("content://com.android.externalstorage.documents/document/primary%3AMusicOther%2Fsong.flac")) == null, "Sibling must not match root");
        Uri storage = Uri.parse("content://com.android.externalstorage.documents/tree/ABCD-1234%3A");
        check("Music/song.flac".equals(LocalLibraryCache.relativeDocumentPath(storage,
            Uri.parse("content://com.android.externalstorage.documents/document/ABCD-1234%3AMusic%2Fsong.flac"))), "Storage root migration");
        System.out.println("PASS native folders: opaque IDs, nesting, duplicate albums, artwork, root songs and cached SAF paths");
    }
}
