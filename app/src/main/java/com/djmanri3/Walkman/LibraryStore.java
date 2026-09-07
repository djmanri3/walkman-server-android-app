package com.djmanri3.Walkman;

import android.content.Context;
import android.net.Uri;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Caché en memoria (con persistencia en fichero) de la biblioteca que la web
 * WALKMAN notifica vía {@link AndroidBridge}. Es lo que Android Auto navega:
 * álbumes, playlists y las pistas de cada contenedor.
 *
 * Los datos llegan desde la web como JSON a través de los métodos
 * pushAlbums/pushPlaylists/pushTracks y se guardan en
 * filesDir/walkman_library.json para sobrevivir a reinicios del proceso (el
 * navegador puede solicitar los hijos antes de que la WebView vuelva a empujar
 * los datos).
 */
public class LibraryStore {

    private static final String TAG = "WalkmanLib";
    private static final String FILE_NAME = "walkman_library.json";

    // Límite de elementos por categoría devueltos a Android Auto. Android Auto
    // no pagina el navegador: toda la lista viaja en una única transacción
    // binder (~1 MB). Con bibliotecas grandes (miles de artistas) la lista
    // completa no cabe y el navegador muestra la categoría vacía. El resto de
    // elementos sigue siendo accesible por voz (onSearch cubre el caché entero)
    // y la lista devuelta se ordena por nombre para que el recorte sea estable.
    private static final int MAX_BROWSE_ITEMS = 500;

    private static final Object LOCK = new Object();
    private static JSONObject sData = null;

    private static JSONObject data(Context ctx) {
        synchronized (LOCK) {
            if (sData == null) {
                sData = readFile(ctx.getFilesDir());
                if (sData == null) {
                    sData = new JSONObject();
                }
            }
            return sData;
        }
    }

    // ─── Escritura (desde AndroidBridge, hilo JS) ─────────────────────────

    /** Vacía toda la biblioteca cacheada (al cambiar de servidor). */
    public static void clear(Context ctx) {
        synchronized (LOCK) {
            sData = new JSONObject();
            persist(ctx);
        }
    }

    public static void setAlbums(Context ctx, JSONArray albums) {
        synchronized (LOCK) {
            try {
                data(ctx).put("albums", albums == null ? new JSONArray() : albums);
            } catch (Exception e) {
                android.util.Log.w(TAG, "setAlbums", e);
            }
            persist(ctx);
        }
    }

    public static void setPlaylists(Context ctx, JSONArray playlists) {
        synchronized (LOCK) {
            try {
                data(ctx).put("playlists", playlists == null ? new JSONArray() : playlists);
            } catch (Exception e) {
                android.util.Log.w(TAG, "setPlaylists", e);
            }
            persist(ctx);
        }
    }

    /** Guarda la lista de bibliotecas de música del servidor conectado. */
    public static void setLibraries(Context ctx, JSONArray libraries) {
        synchronized (LOCK) {
            try {
                data(ctx).put("libraries", libraries == null ? new JSONArray() : libraries);
            } catch (Exception e) {
                android.util.Log.w(TAG, "setLibraries", e);
            }
            persist(ctx);
        }
    }

    /**
     * Guarda la cola de reproducción actual (desde la web). Devuelve true solo
     * si el contenido cambió respecto a lo que ya había, para que el llamado
     * pueda evitar notificaciones innecesarias (y con ellas recargas de la
     * lista en Android Auto que bloquean el scroll).
     */
    public static boolean setQueue(Context ctx, JSONArray queue) {
        synchronized (LOCK) {
            try {
                JSONObject d = data(ctx);
                JSONArray prev = d.optJSONArray("queue");
                JSONArray next = queue == null ? new JSONArray() : queue;
                boolean changed = !sameJson(prev, next);
                d.put("queue", next);
                d.put("queue_time", System.currentTimeMillis());
                return changed;
            } catch (Exception e) {
                android.util.Log.w(TAG, "setQueue", e);
                return false;
            } finally {
                persist(ctx);
            }
        }
    }

    /** Compara dos arrays JSON por contenido (sin importar el orden). */
    private static boolean sameJson(JSONArray a, JSONArray b) {
        if (a == b) return true;
        try {
            if (a == null || b == null) return false;
            if (a.length() != b.length()) return false;
            for (int i = 0; i < a.length(); i++) {
                String sa = a.optJSONObject(i) == null ? a.optString(i) : a.get(i).toString();
                String sb = b.optJSONObject(i) == null ? b.optString(i) : b.get(i).toString();
                if (!sa.equals(sb)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void setTracks(Context ctx, String containerId, JSONArray tracks) {
        synchronized (LOCK) {
            try {
                JSONObject d = data(ctx);
                JSONObject tr = d.optJSONObject("tracks");
                if (tr == null) {
                    tr = new JSONObject();
                    d.put("tracks", tr);
                }
                tr.put(containerId == null ? "" : containerId,
                        tracks == null ? new JSONArray() : tracks);
            } catch (Exception e) {
                android.util.Log.w(TAG, "setTracks", e);
            }
            persist(ctx);
        }
    }

    // ─── Lectura / construcción de MediaItem ──────────────────────────────

    /**
     * Devuelve los objetos "name" de un array JSON ordenados por nombre
     * (insensible a mayúsculas) y limitados a MAX_BROWSE_ITEMS para no
     * exceder la transacción binder de Android Auto. Los items con id o nombre
     * vacíos se descartan.
     */
    private static List<JSONObject> sortedCapped(JSONArray arr) {
        List<JSONObject> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            if (id.isEmpty() || name.isEmpty()) continue;
            out.add(o);
        }
        out.sort((a, b) -> normalize(a.optString("name", ""))
                .compareTo(normalize(b.optString("name", ""))));
        if (out.size() > MAX_BROWSE_ITEMS) {
            return new ArrayList<>(out.subList(0, MAX_BROWSE_ITEMS));
        }
        return out;
    }

    public static boolean hasAlbums(Context ctx) {
        return albums(ctx).size() > 0;
    }

    public static boolean hasPlaylists(Context ctx) {
        return playlists(ctx).size() > 0;
    }

    public static boolean hasTracks(Context ctx, String containerId) {
        JSONObject tr = data(ctx).optJSONObject("tracks");
        if (tr == null) return false;
        JSONArray arr = tr.optJSONArray(containerId == null ? "" : containerId);
        return arr != null && arr.length() > 0;
    }

    /** Álbumes como MediaItem navegables y reproducibles. */
    public static List<MediaBrowserCompat.MediaItem> albums(Context ctx) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        for (JSONObject o : sortedCapped(data(ctx).optJSONArray("albums"))) {
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            String artist = o.optString("artist", "");
            String img = o.optString("imageUrl", "");
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                    .setMediaId("album:" + id)
                    .setTitle(name.isEmpty() ? "Álbum" : name);
            if (!artist.isEmpty()) b.setSubtitle(artist);
            if (!img.isEmpty()) {
                try {
                    b.setIconUri(Uri.parse(img));
                } catch (Exception ignored) {
                }
            }
            out.add(new MediaBrowserCompat.MediaItem(b.build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                            | MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }

    /** Playslists como MediaItem navegables y reproducibles. */
    public static List<MediaBrowserCompat.MediaItem> playlists(Context ctx) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        for (JSONObject o : sortedCapped(data(ctx).optJSONArray("playlists"))) {
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            String img = o.optString("imageUrl", "");
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                    .setMediaId("playlist:" + id)
                    .setTitle(name.isEmpty() ? "Lista" : name);
            if (!img.isEmpty()) {
                try {
                    b.setIconUri(Uri.parse(img));
                } catch (Exception ignored) {
                }
            }
            out.add(new MediaBrowserCompat.MediaItem(b.build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                            | MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }

    // ─── Bibliotecas (clientes Emby/Jellyfin/Plex) ───────────────────────

    /**
     * Bibliotecas de música del servidor conectado como MediaItem reproducibles
     * (al pulsarla el usuario cambia a esa biblioteca). La biblioteca actual
     * se marca con el icono de descripción y se lista primero.
     */
    public static List<MediaBrowserCompat.MediaItem> libraries(Context ctx) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        JSONArray arr = data(ctx).optJSONArray("libraries");
        if (arr == null) return out;
        List<JSONObject> current = new ArrayList<>();
        List<JSONObject> rest = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            if (id.isEmpty() || name.isEmpty()) continue;
            if (o.optBoolean("current", false)) {
                current.add(o);
            } else {
                rest.add(o);
            }
        }
        List<JSONObject> all = new ArrayList<>(current);
        all.addAll(rest);
        for (JSONObject o : all) {
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            boolean isCurrent = o.optBoolean("current", false);
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                    .setMediaId("library:" + id)
                    .setTitle(name.isEmpty() ? "Biblioteca" : name);
            if (isCurrent) b.setSubtitle("Biblioteca actual");
            out.add(new MediaBrowserCompat.MediaItem(b.build(),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }

    /** ¿Hay bibliotecas cacheadas del servidor conectado? */
    public static boolean hasLibraries(Context ctx) {
        JSONArray arr = data(ctx).optJSONArray("libraries");
        return arr != null && arr.length() > 0;
    }

    /**
     * Cola de reproducción actual como MediaItem reproducibles. El mediaId es
     * queue:<index> para reproducir desde esa posición (playTrack(index)).
     */
    public static List<MediaBrowserCompat.MediaItem> queue(Context ctx) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        JSONArray arr = data(ctx).optJSONArray("queue");
        if (arr == null) return out;
        int n = Math.min(arr.length(), MAX_BROWSE_ITEMS);
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "");
            if (name.isEmpty() && !o.has("index")) continue;
            int idx = o.optInt("index", i);
            boolean current = o.optBoolean("current", false);
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                    .setMediaId("queue:" + idx)
                    .setTitle(name.isEmpty() ? "Pista " + (i + 1) : name);
            String artist = o.optString("artist", "");
            if (!artist.isEmpty()) b.setSubtitle(artist);
            if (current) b.setSubtitle((artist.isEmpty() ? "" : artist + " · ") + "En reproducción");
            String img = o.optString("imageUrl", "");
            if (!img.isEmpty()) {
                try {
                    b.setIconUri(Uri.parse(img));
                } catch (Exception ignored) {
                }
            }
            out.add(new MediaBrowserCompat.MediaItem(b.build(),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }

    /** ¿Hay cola cacheadas? */
    public static boolean hasQueue(Context ctx) {
        JSONArray arr = data(ctx).optJSONArray("queue");
        return arr != null && arr.length() > 0;
    }

    /** Pistas de un contenedor (álbum o lista) como MediaItem reproducibles. */
    public static List<MediaBrowserCompat.MediaItem> tracks(Context ctx, String containerId, String kind) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        JSONObject tr = data(ctx).optJSONObject("tracks");
        if (tr == null) return out;
        JSONArray arr = tr.optJSONArray(containerId == null ? "" : containerId);
        if (arr == null) return out;
        int n = Math.min(arr.length(), MAX_BROWSE_ITEMS);
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            if (id.isEmpty() || name.isEmpty()) continue;
            String artist = o.optString("artist", "");
            String img = o.optString("imageUrl", "");
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                    .setMediaId("track:" + kind + ":" + containerId + ":" + id)
                    .setTitle(name);
            if (!artist.isEmpty()) b.setSubtitle(artist);
            if (!img.isEmpty()) {
                try {
                    b.setIconUri(Uri.parse(img));
                } catch (Exception ignored) {
                }
            }
            out.add(new MediaBrowserCompat.MediaItem(b.build(),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }

    // ─── Artistas (Android Auto) ─────────────────────────────────────────

    public static void setArtists(Context ctx, JSONArray artists) {
        synchronized (LOCK) {
            try {
                data(ctx).put("artists", artists == null ? new JSONArray() : artists);
            } catch (Exception e) {
                android.util.Log.w(TAG, "setArtists", e);
            }
            persist(ctx);
        }
    }

    public static void setArtistSongs(Context ctx, String artistId, JSONArray songs) {
        synchronized (LOCK) {
            try {
                JSONObject d = data(ctx);
                JSONObject ss = d.optJSONObject("artist_songs");
                if (ss == null) {
                    ss = new JSONObject();
                    d.put("artist_songs", ss);
                }
                ss.put(artistId == null ? "" : artistId,
                        songs == null ? new JSONArray() : songs);
            } catch (Exception e) {
                android.util.Log.w(TAG, "setArtistSongs", e);
            }
            persist(ctx);
        }
    }

    /** Artistas como MediaItem navegables (abren su repertorio). */
    public static List<MediaBrowserCompat.MediaItem> artists(Context ctx) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        for (JSONObject o : sortedCapped(data(ctx).optJSONArray("artists"))) {
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            String img = o.optString("imageUrl", "");
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                    .setMediaId("artist:" + id)
                    .setTitle(name.isEmpty() ? "Artista" : name);
            if (!img.isEmpty()) {
                try {
                    b.setIconUri(Uri.parse(img));
                } catch (Exception ignored) {
                }
            }
            out.add(new MediaBrowserCompat.MediaItem(b.build(),
                    MediaBrowserCompat.MediaItem.FLAG_BROWSABLE | MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }

    public static boolean hasArtists(Context ctx) {
        return artists(ctx).size() > 0;
    }

    /** Nombre de un artista a partir de su id crudo. */
    public static String artistName(Context ctx, String artistId) {
        if (artistId == null) return "";
        JSONArray arr = data(ctx).optJSONArray("artists");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && artistId.equals(o.optString("id", ""))) {
                    return o.optString("name", "");
                }
            }
        }
        if (artistId.startsWith("local_artist_")) return artistId.substring(13);
        return "";
    }

    /** ¿Hay canciones cacheadas para un artista? */
    public static boolean hasArtistSongs(Context ctx, String artistId) {
        JSONObject ss = data(ctx).optJSONObject("artist_songs");
        if (ss == null) return false;
        JSONArray arr = ss.optJSONArray(artistId == null ? "" : artistId);
        return arr != null && arr.length() > 0;
    }

    /**
     * Canciones de un artista (MediaItem reproducibles). El mediaId lleva el
     * kind "artist" y el id del artista como contenedor:
     * track:artist:<artistId>:<trackId>
     */
    public static List<MediaBrowserCompat.MediaItem> artistSongs(Context ctx, String artistId) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        JSONObject ss = data(ctx).optJSONObject("artist_songs");
        if (ss == null) return out;
        JSONArray arr = ss.optJSONArray(artistId == null ? "" : artistId);
        if (arr == null) return out;
        int n = Math.min(arr.length(), MAX_BROWSE_ITEMS);
        for (int i = 0; i < n; i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            String name = o.optString("name", "");
            if (id.isEmpty() || name.isEmpty()) continue;
            String artist = o.optString("artist", "");
            String img = o.optString("imageUrl", "");
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                    .setMediaId("track:artist:" + artistId + ":" + id)
                    .setTitle(name);
            if (!artist.isEmpty()) b.setSubtitle(artist);
            if (!img.isEmpty()) {
                try {
                    b.setIconUri(Uri.parse(img));
                } catch (Exception ignored) {
                }
            }
            out.add(new MediaBrowserCompat.MediaItem(b.build(),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
        }
        return out;
    }

    // ─── Búsqueda (Google Asistente / Gemini) ─────────────────────────────

    /** ¿Un id pertenece a la lista de playlists? (para deducir el tipo de una pista). */
    private static boolean isPlaylistId(Context ctx, String id) {
        JSONArray arr = data(ctx).optJSONArray("playlists");
        if (arr == null) return false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && id != null && id.equals(o.optString("id", ""))) return true;
        }
        return false;
    }

    /** Guarda los resultados de la búsqueda por servidor (desde la web). */
    public static void setSearchResults(Context ctx, String query, JSONArray results) {
        synchronized (LOCK) {
            try {
                String key = normalize(query == null ? "" : query);
                JSONObject d = data(ctx);
                JSONObject sr = d.optJSONObject("search_results");
                if (sr == null) {
                    sr = new JSONObject();
                    d.put("search_results", sr);
                }
                sr.put(key, results == null ? new JSONArray() : results);
            } catch (Exception e) {
                android.util.Log.w(TAG, "setSearchResults", e);
            }
            persist(ctx);
        }
    }

    /** Resultados cacheados de la búsqueda por servidor como MediaItem. */
    public static List<MediaBrowserCompat.MediaItem> searchResults(Context ctx, String query) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        JSONObject sr = data(ctx).optJSONObject("search_results");
        if (sr == null) return out;
        JSONArray arr = sr.optJSONArray(normalize(query == null ? "" : query));
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String kind = o.optString("kind", "");
            String id = o.optString("id", "");
            if (kind.isEmpty() || id.isEmpty()) continue;
            String name = o.optString("name", "");
            String sub = o.optString("sub", "");
            String container = o.optString("container", "");
            String img = o.optString("imageUrl", "");
            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder().setTitle(name);
            if (!sub.isEmpty()) b.setSubtitle(sub);
            if (!img.isEmpty()) {
                try {
                    b.setIconUri(Uri.parse(img));
                } catch (Exception ignored) {
                }
            }
            int flags = MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
            switch (kind) {
                case "album":
                    b.setMediaId("album:" + id);
                    flags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE | MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
                    break;
                case "playlist":
                    b.setMediaId("playlist:" + id);
                    flags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE | MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
                    break;
                case "artist":
                    b.setMediaId("artist:" + id);
                    flags = MediaBrowserCompat.MediaItem.FLAG_BROWSABLE | MediaBrowserCompat.MediaItem.FLAG_PLAYABLE;
                    break;
                case "track":
                default:
                    if (container.isEmpty()) continue;
                    b.setMediaId("track:album:" + container + ":" + id);
                    break;
            }
            out.add(new MediaBrowserCompat.MediaItem(b.build(), flags));
        }
        return out;
    }

    /**
     * Búsqueda local sobre lo que hay en caché (álbumes, artistas, playlists
     * y las pistas de los contenedores ya abiertos). Devuelve MediaItems
     * navegables/reproducibles para mostrar al Asistente.
     */
    public static List<MediaBrowserCompat.MediaItem> search(Context ctx, String query) {
        List<MediaBrowserCompat.MediaItem> out = new ArrayList<>();
        if (query == null || query.trim().isEmpty()) return out;
        String q = normalize(query);

        // Artistas
        JSONArray art = data(ctx).optJSONArray("artists");
        if (art != null) {
            for (int i = 0; i < art.length(); i++) {
                JSONObject o = art.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name", "");
                if (!name.isEmpty() && normalize(name).contains(q)) {
                    String id = o.optString("id", "");
                    if (!id.isEmpty()) {
                        MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                                .setMediaId("artist:" + id)
                                .setTitle(name);
                        String img = o.optString("imageUrl", "");
                        if (!img.isEmpty()) {
                            try {
                                b.setIconUri(Uri.parse(img));
                            } catch (Exception ignored) {
                            }
                        }
                        out.add(new MediaBrowserCompat.MediaItem(b.build(),
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                                        | MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
                    }
                }
            }
        }

        // Álbumes
        for (MediaBrowserCompat.MediaItem it : albums(ctx)) {
            MediaDescriptionCompat d = it.getDescription();
            String title = d.getTitle() == null ? "" : d.getTitle().toString();
            String sub = d.getSubtitle() == null ? "" : d.getSubtitle().toString();
            if (normalize(title).contains(q) || normalize(sub).contains(q)) {
                out.add(it);
            }
        }

        // Playlists
        for (MediaBrowserCompat.MediaItem it : playlists(ctx)) {
            MediaDescriptionCompat d = it.getDescription();
            String title = d.getTitle() == null ? "" : d.getTitle().toString();
            if (normalize(title).contains(q)) {
                out.add(it);
            }
        }

        // Canciones (recorriendo los contenedores ya cacheados)
        JSONObject tr = data(ctx).optJSONObject("tracks");
        if (tr != null) {
            JSONArray names = tr.names();
            if (names != null) {
                for (int i = 0; i < names.length() && out.size() < 60; i++) {
                    String containerId = names.optString(i);
                    JSONArray trackArr = tr.optJSONArray(containerId);
                    if (trackArr == null) continue;
                    String kind = isPlaylistId(ctx, containerId) ? "playlist" : "album";
                    for (int j = 0; j < trackArr.length(); j++) {
                        JSONObject o = trackArr.optJSONObject(j);
                        if (o == null) continue;
                        String tid = o.optString("id", "");
                        String tname = o.optString("name", "");
                        String tartist = o.optString("artist", "");
                        if (tid.isEmpty() || tname.isEmpty()) continue;
                        if (normalize(tname).contains(q) || normalize(tartist).contains(q)) {
                            MediaDescriptionCompat.Builder b = new MediaDescriptionCompat.Builder()
                                    .setMediaId("track:" + kind + ":" + containerId + ":" + tid)
                                    .setTitle(tname);
                            if (!tartist.isEmpty()) b.setSubtitle(tartist);
                            String img = o.optString("imageUrl", "");
                            if (!img.isEmpty()) {
                                try {
                                    b.setIconUri(Uri.parse(img));
                                } catch (Exception ignored) {
                                }
                            }
                            out.add(new MediaBrowserCompat.MediaItem(b.build(),
                                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE));
                            if (out.size() >= 60) break;
                        }
                    }
                }
            }
        }
        if (out.size() > 150) {
            return new ArrayList<>(out.subList(0, 150));
        }
        return out;
    }

    /** Encuentra la mejor coincidencia para onPlayFromSearch (síncrono). */
    public static PlayTarget resolveBest(Context ctx, String query) {
        if (query == null || query.trim().isEmpty()) return null;
        String q = normalize(query);

        // Primera pasada: coincidencia EXACTA de nombre; después: contiene.
        // Cada pasada prioriza artista > álbum > playlist.
        for (int pass = 0; pass < 2; pass++) {
            final boolean exact = (pass == 0);

            JSONArray art = data(ctx).optJSONArray("artists");
            if (art != null) {
                for (int i = 0; i < art.length(); i++) {
                    JSONObject o = art.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", "");
                    String n = normalize(name);
                    if (!name.isEmpty() && (exact ? n.equals(q) : n.contains(q))) {
                        String id = o.optString("id", "");
                        if (!id.isEmpty()) return new PlayTarget("artist", id, "", name);
                    }
                }
            }

            JSONArray alb = data(ctx).optJSONArray("albums");
            if (alb != null) {
                for (int i = 0; i < alb.length(); i++) {
                    JSONObject o = alb.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", "");
                    String n = normalize(name);
                    if (!name.isEmpty() && (exact ? n.equals(q) : n.contains(q))) {
                        String id = o.optString("id", "");
                        if (!id.isEmpty()) return new PlayTarget("album", id, "", name);
                    }
                }
            }

            JSONArray pl = data(ctx).optJSONArray("playlists");
            if (pl != null) {
                for (int i = 0; i < pl.length(); i++) {
                    JSONObject o = pl.optJSONObject(i);
                    if (o == null) continue;
                    String name = o.optString("name", "");
                    String n = normalize(name);
                    if (!name.isEmpty() && (exact ? n.equals(q) : n.contains(q))) {
                        String id = o.optString("id", "");
                        if (!id.isEmpty()) return new PlayTarget("playlist", id, "", name);
                    }
                }
            }
        }
        return null;
    }

    /** Normaliza para búsquedas sin mayúsculas ni acentos. */
    private static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        String lower = s.toLowerCase();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            switch (c) {
                case 'á': c = 'a'; break;
                case 'é': c = 'e'; break;
                case 'í': c = 'i'; break;
                case 'ó': c = 'o'; break;
                case 'ú': c = 'u'; break;
                case 'ü': c = 'u'; break;
                case 'ñ': c = 'n'; break;
                case 'à': c = 'a'; break;
                case 'è': c = 'e'; break;
                case 'ì': c = 'i'; break;
                case 'ò': c = 'o'; break;
                case 'ù': c = 'u'; break;
                default: break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Objetivo de reproducción elegido por voz. */
    public static class PlayTarget {
        public final String kind;
        public final String id;
        public final String trackId;
        public final String name;

        PlayTarget(String kind, String id, String trackId, String name) {
            this.kind = kind;
            this.id = id;
            this.trackId = trackId;
            this.name = name;
        }
    }

    // ─── Persistencia ─────────────────────────────────────────────────────

    private static void persist(Context ctx) {
        try {
            File dir = ctx.getFilesDir();
            File out = new File(dir, FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(sData.toString().getBytes("UTF-8"));
                fos.flush();
            }
        } catch (IOException e) {
            android.util.Log.w(TAG, "No se pudo persistir la biblioteca", e);
        }
    }

    private static JSONObject readFile(File dir) {
        File in = new File(dir, FILE_NAME);
        if (!in.exists()) return null;
        try (FileInputStream fis = new FileInputStream(in)) {
            byte[] buf = new byte[(int) in.length()];
            int read = 0;
            while (read < buf.length) {
                int n = fis.read(buf, read, buf.length - read);
                if (n == -1) break;
                read += n;
            }
            return new JSONObject(new String(buf, 0, read, "UTF-8"));
        } catch (Exception e) {
            android.util.Log.w(TAG, "No se pudo leer la biblioteca en caché", e);
            return null;
        }
    }
}