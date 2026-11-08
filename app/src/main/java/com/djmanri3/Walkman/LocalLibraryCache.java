package com.djmanri3.Walkman;

import android.content.Context;
import android.content.UriPermission;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Durable SAF index. Restoring only reads the index and cached artwork, never the music tree. */
final class LocalLibraryCache {
    private static File directory(Context context) {
        return new File(context.getFilesDir(), "local-library");
    }

    private static boolean canRestore(Context context, Uri tree) {
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.getUri().equals(tree)) return true;
        }
        return false;
    }

    static synchronized void save(Context context, Uri tree, List<JSONObject> tracks,
                                  List<String> ids, MiniHttpServer server) throws Exception {
        if (!canRestore(context, tree)) throw new SecurityException("Folder access is not persistent");
        File dir = directory(context), artwork = new File(dir, "artwork");
        if (!artwork.isDirectory() && !artwork.mkdirs()) throw new IOException("Cannot create library cache");
        JSONObject files = new JSONObject();
        JSONArray covers = new JSONArray(), items = new JSONArray();
        Set<String> keep = new HashSet<>();
        for (String id : ids) {
            Uri uri = server.fileFor(id);
            if (uri != null) files.put(id, uri.toString());
            byte[] bytes = server.bytesFor(id);
            if (bytes != null) {
                if (!id.matches("[A-Za-z0-9_]+")) throw new IOException("Invalid artwork id");
                try (FileOutputStream out = new FileOutputStream(new File(artwork, id))) {
                    out.write(bytes);
                }
                covers.put(id);
                keep.add(id);
            }
        }
        for (JSONObject track : tracks) items.put(track);
        JSONObject index = new JSONObject().put("version", 1).put("tree", tree.toString())
                .put("baseUrl", server.baseUrl()).put("tracks", items).put("files", files).put("covers", covers);
        AtomicFile file = new AtomicFile(new File(dir, "index.json"));
        FileOutputStream out = null;
        try {
            out = file.startWrite();
            out.write(index.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (Exception error) {
            if (out != null) file.failWrite(out);
            throw error;
        }
        // Delete artwork from the previous scan only after committing the new index.
        File[] old = artwork.listFiles();
        if (old != null) for (File cover : old) if (!keep.contains(cover.getName())) cover.delete();
    }

    static synchronized JSONArray restore(Context context, MiniHttpServer server) throws Exception {
        File dir = directory(context);
        AtomicFile file = new AtomicFile(new File(dir, "index.json"));
        if (!file.getBaseFile().exists() && !new File(dir, "index.json.bak").exists()) return null;
        JSONObject index = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
        if (index.getInt("version") != 1) throw new IOException("Unsupported local library cache");
        if (!canRestore(context, Uri.parse(index.getString("tree")))) {
            throw new SecurityException("The music folder permission was revoked");
        }
        JSONObject files = index.getJSONObject("files");
        JSONArray covers = index.getJSONArray("covers"), tracks = index.getJSONArray("tracks");
        String previous = index.getString("baseUrl");
        if (!previous.startsWith("http://127.0.0.1:") || !previous.endsWith("/")) {
            throw new IOException("Invalid cached audio server");
        }
        for (java.util.Iterator<String> keys = files.keys(); keys.hasNext();) {
            String id = keys.next();
            server.addFile(id, Uri.parse(files.getString(id)));
        }
        for (int i = 0; i < covers.length(); i++) {
            String id = covers.getString(i);
            if (!id.matches("[A-Za-z0-9_]+")) throw new IOException("Invalid cached artwork id");
            File cover = new File(new File(dir, "artwork"), id);
            if (cover.isFile()) {
                try (FileInputStream in = new FileInputStream(cover);
                     ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    server.addBytes(id, bytes.toByteArray());
                }
            }
        }
        for (int i = 0; i < tracks.length(); i++) {
            JSONObject track = tracks.getJSONObject(i);
            for (String key : new String[]{"Path", "objUrl", "coverUrl"}) {
                String value = track.optString(key, "");
                if (value.startsWith(previous)) track.put(key, server.baseUrl() + value.substring(previous.length()));
            }
        }
        return tracks;
    }
}
