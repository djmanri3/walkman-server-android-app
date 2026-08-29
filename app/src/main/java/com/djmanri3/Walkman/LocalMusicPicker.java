package com.djmanri3.Walkman;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Recopila del árbol de carpetas seleccionado (SAF) los archivos de audio y
 * construye la lista de pistas como JSON para inyectar en la WebView. Devuelve
 * además los pares id/uri para que {@link MiniHttpServer} los sirva.
 */
public final class LocalMusicPicker {

    private static final String[] AUDIO_EXT = {"mp3", "flac", "ogg", "oga", "m4a",
            "aac", "wav", "opus", "wma", "webm"};
    private static final String[] COVER_EXT = {"jpg", "jpeg", "png", "webp", "gif"};
    private static final String[] PLAYLIST_EXT = {"m3u", "m3u8"};

    private LocalMusicPicker() {
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    /**
     * Recorre el árbol y recopila los archivos de audio y portadas.
     * Devuelve una estructura intermedia con datos de pistas.
     */
    public static List<JSONObject> collectTree(Context context, Uri treeUri,
                                               List<String> ids, List<Uri> uris,
                                               MiniHttpServer server, int serverPort) {
        DocumentFile tree = DocumentFile.fromTreeUri(context, treeUri);
        if (tree == null || !tree.canRead()) {
            return new ArrayList<>();
        }

        String rootName = tree.getName() != null ? tree.getName() : "Local";

        // Mapa de portada por álbum (ruta de carpeta -> url)
        Map<String, String> coverByAlbum = new HashMap<>();

        // Primera pasada: recopilar todas las URIs de audio, portadas y playlists.
        Map<String, List<DocumentFile>> audioByAlbum = new HashMap<>();
        Map<String, List<DocumentFile>> covers = new HashMap<>();
        List<DocumentFile> playlists = new ArrayList<>();

        collect(dirToList(tree), rootName, audioByAlbum, covers, playlists);

        List<JSONObject> tracks = new ArrayList<>();
        ContentResolver cr = context.getContentResolver();
        // Caché de url de carátula incrustada por uri de audio (para no reextraer).
        Map<Uri, String> embeddedCoverByUri = new HashMap<>();
        // Caché de metadatos embebidos por uri de audio.
        Map<Uri, EmbeddedCover.AudioMeta> metaByUri = new HashMap<>();
        // Cachés para resolver referencias de las playlists.
        Map<Uri, String> idByUri = new HashMap<>();
        Map<Uri, String> urlByUri = new HashMap<>();
        Map<Uri, String> coverByUri = new HashMap<>();
        List<DocumentFile> audioFiles = new ArrayList<>();
        for (Map.Entry<String, List<DocumentFile>> e : audioByAlbum.entrySet()) {
            String album = e.getKey();
            String coverUrl = "";
            List<DocumentFile> cList = covers.get(album);
            if (cList != null && !cList.isEmpty()) {
                DocumentFile c = cList.get(0);
                String cid = "c" + ids.size() + "_" + System.nanoTime();
                ids.add(cid);
                uris.add(c.getUri());
                server.addFile(cid, c.getUri());
                coverUrl = "http://127.0.0.1:" + serverPort + "/" + cid;
            }
            for (DocumentFile f : e.getValue()) {
                String title = f.getName() != null ? f.getName() : "pista";
                if (title.lastIndexOf('.') > 0) {
                    title = title.substring(0, title.lastIndexOf('.'));
                }
                String id = "a" + ids.size() + "_" + System.nanoTime();
                ids.add(id);
                uris.add(f.getUri());
                server.addFile(id, f.getUri());
                String url = "http://127.0.0.1:" + serverPort + "/" + id;

                // Metadatos embebidos (título/artista/álbum/carátula).
                EmbeddedCover.AudioMeta meta = metaByUri.get(f.getUri());
                if (meta == null) {
                    meta = EmbeddedCover.extract(cr, f.getUri());
                    metaByUri.put(f.getUri(), meta);
                }

                String cov = coverUrl;
                // Si la carpeta no tiene portada separada, usamos la incrustada
                // en el propio audio (ID3/FLAC) si existe.
                if (cov.length() == 0) {
                    cov = embeddedCoverByUri.get(f.getUri());
                    if (cov == null) {
                        byte[] img = meta != null ? meta.coverBytes : null;
                        if (img != null && img.length > 0) {
                            String cid = "c" + ids.size() + "_" + System.nanoTime();
                            ids.add(cid);
                            server.addBytes(cid, img);
                            cov = "http://127.0.0.1:" + serverPort + "/" + cid;
                        } else {
                            cov = "";
                        }
                        embeddedCoverByUri.put(f.getUri(), cov);
                    }
                }
                tracks.add(trackJson(id, title, album, url, cov, meta));

                idByUri.put(f.getUri(), id);
                urlByUri.put(f.getUri(), url);
                coverByUri.put(f.getUri(), cov);
                audioFiles.add(f);
            }
        }
        appendPlaylists(cr, tracks, playlists, audioFiles, idByUri, coverByUri,
                ids, server, serverPort);
        return tracks;
    }

    private static void collect(DocumentFile[] entries, String album,
                                Map<String, List<DocumentFile>> audioByAlbum,
                                Map<String, List<DocumentFile>> covers,
                                List<DocumentFile> playlists) {
        if (entries == null) {
            return;
        }
        for (DocumentFile f : entries) {
            if (f == null) {
                continue;
            }
            if (f.isDirectory()) {
                collect(dirToList(f), album, audioByAlbum, covers, playlists);
            } else {
                String name = f.getName() != null ? f.getName() : "";
                String ext = extOf(name);
                String dirKey = lastDirName(f) != null ? lastDirName(f) : album;
                if (isAudioExt(ext)) {
                    audioByAlbum.computeIfAbsent(dirKey, k -> new ArrayList<>()).add(f);
                } else if (isCoverExt(ext) && isCoverName(name)) {
                    covers.computeIfAbsent(dirKey, k -> new ArrayList<>()).add(f);
                } else if (isPlaylistExt(ext)) {
                    playlists.add(f);
                }
            }
        }
    }

    private static String lastDirName(DocumentFile f) {
        String path = f.getUri().getPath();
        if (path == null) {
            return null;
        }
        String[] seg = path.split("/");
        if (seg.length >= 2) {
            // [0]='document', [1]=tree_root, [2..] subcarpetas; devolvemos la penúltima
            // para abajo, mejor el segmento anterior al nombre de archivo.
            return seg.length >= 3 ? seg[seg.length - 2] : null;
        }
        return null;
    }

    private static boolean isAudioExt(String ext) {
        for (String e : AUDIO_EXT) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCoverExt(String ext) {
        for (String e : COVER_EXT) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCoverName(String lower) {
        return lower.contains("cover") || lower.contains("folder")
                || lower.contains("albumart")
                || lower.equals("cover.jpg") || lower.equals("folder.jpg")
                || lower.equals("album.jpg") || lower.equals("front.jpg");
    }

    private static boolean isPlaylistExt(String ext) {
        for (String e : PLAYLIST_EXT) {
            if (e.equals(ext)) {
                return true;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------------ */
    /* Playlists .m3u/.m3u8                                               */
    /* ------------------------------------------------------------------ */

    /**
     * Añade al JSON una playlist "Todas las canciones" y una entrada por cada
     * archivo .m3u/.m3u8 encontrado, resolviendo las rutas relativas que
     * referencian contra los archivos de audio ya recopilados.
     */
    private static void appendPlaylists(ContentResolver cr, List<JSONObject> tracks,
                                        List<DocumentFile> playlists,
                                        List<DocumentFile> audioFiles,
                                        Map<Uri, String> idByUri,
                                        Map<Uri, String> coverByUri,
                                        List<String> ids,
                                        MiniHttpServer server, int serverPort) {
        // Playlist "Todo": todas las canciones recopiladas.
        if (!audioFiles.isEmpty()) {
            JSONArray all = new JSONArray();
            String allCover = "";
            for (DocumentFile f : audioFiles) {
                if (idByUri.containsKey(f.getUri())) {
                    all.put(idByUri.get(f.getUri()));
                    if (allCover.length() == 0 && coverByUri.containsKey(f.getUri())) {
                        allCover = coverByUri.get(f.getUri());
                    }
                }
            }
            if (all.length() > 0) {
                String allId = "pl" + ids.size() + "_" + System.nanoTime();
                ids.add(allId);
                tracks.add(playlistJson(allId, "Todas las canciones",
                        all, allCover, all.length()));
            }
        }

        if (playlists == null || playlists.isEmpty()) {
            return;
        }

        for (DocumentFile pl : playlists) {
            try {
                String rawId = "r" + ids.size() + "_" + System.nanoTime();
                ids.add(rawId);
                server.addFile(rawId, pl.getUri());

                String plName = pl.getName() != null ? pl.getName() : "Playlist";
                if (plName.lastIndexOf('.') > 0) {
                    plName = plName.substring(0, plName.lastIndexOf('.'));
                }
                String content = readText(cr, pl.getUri());
                if (content == null) {
                    continue;
                }

                List<String> folderSegs = folderSegments(pl.getUri());
                JSONArray memberIds = new JSONArray();
                String memberCover = "";
                String extTitle = null;
                List<JSONObject> external = new ArrayList<>();

                String[] lines = content.replace("\r\n", "\n").replace('\r', '\n').split("\n");
                for (String l : lines) {
                    String line = l.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    if (line.startsWith("#PLAYLIST:")) {
                        String n = line.substring(line.indexOf(':') + 1).trim();
                        if (!n.isEmpty() && !n.equalsIgnoreCase(plName)) {
                            plName = n;
                        }
                        continue;
                    }
                    if (line.startsWith("#EXTINF")) {
                        int comma = line.indexOf(',');
                        if (comma >= 0) {
                            extTitle = line.substring(comma + 1).trim();
                        }
                        continue;
                    }
                    if (line.startsWith("#")) {
                        continue;
                    }
                    String ref = line;
                    if (ref.regionMatches(true, 0, "file://", 0, 7)) {
                        ref = ref.substring(7);
                    }
                    if (ref.startsWith("http://") || ref.startsWith("https://")) {
                        String nm = (extTitle != null && !extTitle.isEmpty())
                                ? extTitle : filenameFromUrl(ref);
                        String xid = "x" + ids.size() + "_" + System.nanoTime();
                        ids.add(xid);
                        external.add(trackJson(xid, nm, "", ref, "", null));
                        extTitle = null;
                        continue;
                    }

                    DocumentFile hit = resolveRef(folderSegs, ref, audioFiles);
                    if (hit != null && idByUri.containsKey(hit.getUri())) {
                        String mid = idByUri.get(hit.getUri());
                        if (memberIds.length() == 0 || !contains(memberIds, mid)) {
                            memberIds.put(mid);
                        }
                        if (memberCover.length() == 0 && coverByUri.containsKey(hit.getUri())) {
                            memberCover = coverByUri.get(hit.getUri());
                        }
                    }
                    extTitle = null;
                }

                if (memberIds.length() > 0 || !external.isEmpty()) {
                    String plid = "pl" + ids.size() + "_" + System.nanoTime();
                    ids.add(plid);
                    tracks.add(playlistJson(plid, plName, memberIds,
                            memberCover, memberIds.length() + external.size()));
                }
                for (JSONObject ex : external) {
                    tracks.add(ex);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean contains(JSONArray arr, String id) {
        for (int i = 0; i < arr.length(); i++) {
            if (id.equals(arr.optString(i))) {
                return true;
            }
        }
        return false;
    }

    private static JSONObject playlistJson(String id, String name,
                                           JSONArray memberIds, String coverUrl,
                                           int count) {
        try {
            JSONObject o = new JSONObject();
            o.put("Id", id);
            o.put("Name", name);
            o.put("Type", "Playlist");
            o.put("Album", "");
            o.put("AlbumArtist", "");
            o.put("Artists", new JSONArray());
            o.put("coverUrl", coverUrl == null ? "" : coverUrl);
            o.put("PlaylistIds", memberIds);
            o.put("Count", count);
            o.put("IsLocal", true);
            return o;
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }

    private static String readText(ContentResolver cr, Uri uri) {
        try (java.io.InputStream in = cr.openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > 2 * 1024 * 1024) {
                    return null;
                }
            }
            byte[] data = bos.toByteArray();
            try {
                String s = new String(data, "UTF-8");
                if (s.indexOf('\uFFFD') >= 0) {
                    s = new String(data, "ISO-8859-1");
                }
                return s;
            } catch (Exception e) {
                return new String(data, "ISO-8859-1");
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Segmentos decodificados de la ruta de una URI de documento. */
    private static List<String> decodedSegments(Uri uri) {
        List<String> out = new ArrayList<>();
        if (uri == null) {
            return out;
        }
        String path = uri.getPath();
        if (path == null) {
            return out;
        }
        for (String s : path.split("/")) {
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** Segmentos de la carpeta que contiene al documento (sin el nombre). */
    private static List<String> folderSegments(Uri uri) {
        List<String> segs = decodedSegments(uri);
        if (!segs.isEmpty()) {
            segs.remove(segs.size() - 1);
        }
        return segs;
    }

    /** Comprueba si la cola de {@code full} coincide con {@code tail}. */
    private static boolean tailEquals(List<String> full, List<String> tail) {
        if (tail == null || tail.isEmpty() || full.size() < tail.size()) {
            return false;
        }
        int off = full.size() - tail.size();
        for (int i = 0; i < tail.size(); i++) {
            if (!full.get(off + i).equalsIgnoreCase(tail.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String percentDecode(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                try {
                    int v = Integer.parseInt(s.substring(i + 1, i + 3), 16);
                    sb.append((char) v);
                    i += 2;
                    continue;
                } catch (NumberFormatException ignored) {
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static String filenameFromUrl(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        int slash = u.lastIndexOf('/');
        if (slash >= 0) {
            u = u.substring(slash + 1);
        }
        u = percentDecode(u);
        if (u.isEmpty()) {
            u = "Cancion externa";
        }
        int dot = u.lastIndexOf('.');
        if (dot > 0 && u.length() - dot <= 6) {
            u = u.substring(0, dot);
        }
        return u;
    }

    /** Resuelve una referencia de una playlist contra los archivos recopilados. */
    private static DocumentFile resolveRef(List<String> folderSegs, String ref,
                                           List<DocumentFile> audioFiles) {
        String r = percentDecode(ref.replace('\\', '/'));
        String[] parts = r.split("/");
        List<String> segs = new ArrayList<>(folderSegs);
        for (String p : parts) {
            if (p.isEmpty() || p.equals(".")) {
                continue;
            }
            if (p.equals("..")) {
                if (!segs.isEmpty()) {
                    segs.remove(segs.size() - 1);
                }
                continue;
            }
            segs.add(p);
        }
        if (segs.isEmpty()) {
            return null;
        }
        // Coincidencia por ruta completa (relativa a la carpeta).
        for (DocumentFile f : audioFiles) {
            if (tailEquals(decodedSegments(f.getUri()), segs)) {
                return f;
            }
        }
        // Fallback: coincidencia solo por nombre de archivo.
        String fn = segs.get(segs.size() - 1);
        for (DocumentFile f : audioFiles) {
            String nm = f.getName();
            if (nm != null && nm.equalsIgnoreCase(fn)) {
                return f;
            }
        }
        return null;
    }

    private static DocumentFile[] dirToList(DocumentFile dir) {
        return dir.listFiles();
    }

    private static JSONObject trackJson(String id, String title, String album,
                                        String url, String coverUrl,
                                        EmbeddedCover.AudioMeta meta) {
        try {
            JSONObject o = new JSONObject();
            o.put("Id", id);
            boolean hasMeta = meta != null
                    && (meta.title.length() > 0 || meta.artist.length() > 0
                    || meta.album.length() > 0 || meta.albumArtist.length() > 0);
            String name = hasMeta && meta.title.length() > 0 ? meta.title : title;
            String artist = hasMeta && meta.artist.length() > 0 ? meta.artist : "";
            String albumName = hasMeta && meta.album.length() > 0 ? meta.album : album;
            String albumArtist = hasMeta && meta.albumArtist.length() > 0
                    ? meta.albumArtist : (artist.length() > 0 ? artist : album);
            o.put("Name", name);
            JSONArray artists = new JSONArray();
            if (artist.length() > 0) {
                for (String a : artist.split("/")) {
                    if (a != null && a.trim().length() > 0) {
                        artists.put(a.trim());
                    }
                }
            }
            if (artists.length() == 0) {
                artists.put(albumArtist);
            }
            o.put("Artists", artists);
            o.put("AlbumArtist", albumArtist);
            o.put("Album", albumName);
            o.put("Type", "Audio");
            o.put("Duration", 0);
            if (hasMeta && meta.year.length() > 0) {
                o.put("ProductionYear", meta.year);
            }
            if (hasMeta && meta.track.length() > 0) {
                o.put("IndexNumber", meta.track);
            }
            o.put("Path", url);
            o.put("objUrl", url);
            o.put("coverUrl", coverUrl);
            o.put("IsLocal", true);
            return o;
        } catch (org.json.JSONException e) {
            return new JSONObject();
        }
    }
}
