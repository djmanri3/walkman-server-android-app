package com.djmanri3.Walkman;

import android.content.Context;
import android.util.Log;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Puente JavaScript &rarr; Android que la web WALKMAN ya espera.
 *
 * La web ya contiene este código:
 * <pre>
 * function notifyAndroidMedia() {
 *   ...
 *   AndroidBridge.setMediaState(JSON.stringify(payload));
 * }
 * </pre>
 * donde payload = { title, artist, album, artwork, playing, position, duration }.
 *
 * Este objeto se inyecta con WebView.addJavascriptInterface(..., "AndroidBridge")
 * y delega en el MediaService para actualizar la MediaSession y la notificación
 * del sistema, consiguiendo así la integración total con el motor multimedia
 * de Android (widget, bloqueo, bluetooth, Android Auto...).
 *
 * Además recibe la biblioteca (álbumes, playlists y pistas) que la web extrae
 * con el script inyectado {@link MainActivity#LIBRARY_SCRIPT}, y la guarda en
 * {@link LibraryStore} para que Android Auto pueda navegarla.
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";

    private final Context mContext;

    /** Callback registrado por MainActivity para lanzar la selección SAF. */
    public interface LocalFolderListener {
        void onPickLocalFolder();
    }

    private static volatile LocalFolderListener sLocalFolderListener;

    public static void setLocalFolderListener(LocalFolderListener l) {
        sLocalFolderListener = l;
    }

    /** Callback para el panel de "Servidor Walkman" embebido en la web. */
    public interface ServerUrlListener {
        String onGetConfig();

        void onSetConfig(String mode, String url);
    }

    private static volatile ServerUrlListener sServerUrlListener;

    public static void setServerUrlListener(ServerUrlListener l) {
        sServerUrlListener = l;
    }

    public AndroidBridge(Context context) {
        mContext = context != null ? context.getApplicationContext() : null;
    }

    /** Llamado desde JavaScript con el JSON del estado de reproducción. */
    @JavascriptInterface
    public void setMediaState(String json) {
        Log.d(TAG, "setMediaState: " + json);
        if (json == null) return;

        try {
            JSONObject o = new JSONObject(json);
            String title = o.optString("title", "");
            String artist = o.optString("artist", "");
            String album = o.optString("album", "");
            String artwork = o.optString("artwork", "");
            boolean playing = o.optBoolean("playing", false);
            long position = (long) (o.optDouble("position", 0) * 1000);
            long duration = (long) (o.optDouble("duration", 0) * 1000);

            MediaService.updateMedia(
                    MediaService.instance(),
                    title, artist, album, artwork, playing, position, duration);
        } catch (JSONException e) {
            Log.w(TAG, "JSON inválido en setMediaState", e);
        }
    }

    /** Llamado desde JavaScript al pulsar "Elegir carpeta de música". */
    @JavascriptInterface
    public void pickLocalFolder() {
        Log.d(TAG, "pickLocalFolder");
        LocalFolderListener l = sLocalFolderListener;
        if (l != null) {
            l.onPickLocalFolder();
        }
    }

    /** Devuelve el JSON {useCustom, url} con la configuración de servidor. */
    @JavascriptInterface
    public String getServerConfig() {
        ServerUrlListener l = sServerUrlListener;
        return l != null ? l.onGetConfig() : "{}";
    }

    /** Guarda la elección de servidor (mode: 'off' | 'custom') y recarga. */
    @JavascriptInterface
    public void setServerConfig(String mode, String url) {
        Log.d(TAG, "setServerConfig mode=" + mode + " url=" + url);
        ServerUrlListener l = sServerUrlListener;
        if (l != null) {
            l.onSetConfig(mode, url);
        }
    }

    // ─── Biblioteca para Android Auto ─────────────────────────────────────

    /**
     * Recibe el array JSON de álbumes con el formato del script inyectado:
     * [{ id, name, artist, imageUrl }].
     */
    @JavascriptInterface
    public void pushAlbums(String json) {
        try {
            JSONArray arr = parseArray(json);
            if (mContext != null) {
                LibraryStore.setAlbums(mContext, arr);
                MediaService.childrenChanged("walkman_albums");
            }
        } catch (Exception e) {
            Log.w(TAG, "pushAlbums JSON inválido", e);
        }
    }

    /**
     * Recibe el array JSON de playlists con el formato [{ id, name, imageUrl }].
     */
    @JavascriptInterface
    public void pushPlaylists(String json) {
        try {
            JSONArray arr = parseArray(json);
            if (mContext != null) {
                LibraryStore.setPlaylists(mContext, arr);
                MediaService.childrenChanged("walkman_playlists");
            }
        } catch (Exception e) {
            Log.w(TAG, "pushPlaylists JSON inválido", e);
        }
    }

    /**
     * Recibe el array JSON de artistas con el formato [{ id, name, imageUrl }].
     */
    @JavascriptInterface
    public void pushArtists(String json) {
        try {
            JSONArray arr = parseArray(json);
            if (mContext != null) {
                LibraryStore.setArtists(mContext, arr);
                Log.i(TAG, "pushArtists: " + arr.length() + " artistas");
                MediaService.childrenChanged("walkman_artists");
            }
        } catch (Exception e) {
            Log.w(TAG, "pushArtists JSON inválido", e);
        }
    }

    /**
     * Recibe las canciones de un artista con el formato
     * [{ id, name, artist, imageUrl, duration }]. artistId es el id crudo
     * del artista.
     */
    @JavascriptInterface
    public void pushArtistAlbums(String artistId, String json) {
        try {
            JSONArray arr = parseArray(json);
            if (mContext != null) {
                LibraryStore.setArtistSongs(mContext, artistId, arr);
                Log.i(TAG, "pushArtistAlbums(" + artistId + "): " + arr.length() + " canciones");
                MediaService.childrenChanged("artist:" + artistId);
            }
        } catch (Exception e) {
            Log.w(TAG, "pushArtistAlbums JSON inválido", e);
        }
    }

    /**
     * Recibe los resultados de búsqueda por servidor con el formato
     * [{ kind, id, name, sub, imageUrl, container }].
     */
    @JavascriptInterface
    public void pushSearchResults(String query, String json) {
        try {
            JSONArray arr = parseArray(json);
            if (mContext != null) {
                LibraryStore.setSearchResults(mContext, query, arr);
                Log.i(TAG, "pushSearchResults(" + query + "): " + arr.length() + " resultados");
            }
        } catch (Exception e) {
            Log.w(TAG, "pushSearchResults JSON inválido", e);
        }
    }

    /**
     * Recibe las pistas de un contenedor (álbum o lista) con el formato
     * [{ id, name, artist, imageUrl, duration }]. containerId es el id
     * <em>crudo</em> del álbum/lista (sin prefijo).
     */
    @JavascriptInterface
    public void pushTracks(String containerId, String json) {
        try {
            JSONArray arr = parseArray(json);
            if (mContext != null) {
                LibraryStore.setTracks(mContext, containerId, arr);
                MediaService.childrenChanged("album:" + containerId);
                MediaService.childrenChanged("playlist:" + containerId);
            }
        } catch (Exception e) {
            Log.w(TAG, "pushTracks JSON inválido", e);
        }
    }

    /**
     * Recibe las bibliotecas de música del servidor conectado con el formato
     * [{ id, name, current }]. Las muestra Android Auto para poder cambiar de
     * biblioteca (Emby/Jellyfin/Plex) sin tocar el móvil.
     */
    @JavascriptInterface
    public void pushLibraries(String json) {
        try {
            JSONArray arr = parseArray(json);
            if (mContext != null) {
                LibraryStore.setLibraries(mContext, arr);
                Log.i(TAG, "pushLibraries: " + arr.length() + " bibliotecas");
                MediaService.childrenChanged("walkman_servers");
            }
        } catch (Exception e) {
            Log.w(TAG, "pushLibraries JSON inválido", e);
        }
    }

    /**
     * Recibe la cola de reproducción actual (Android Auto) con el formato
     * [{ index, name, artist, imageUrl, current }]. index es la posición en la
     * cola de la web; permite reproducir desde esa pista (playTrack(index)).
     */
    @JavascriptInterface
    public void pushQueue(String json) {
        try {
            JSONArray arr = parseArray(json);
            if (mContext != null) {
                boolean changed = LibraryStore.setQueue(mContext, arr);
                Log.i(TAG, "pushQueue: " + arr.length() + " pistas" + (changed ? " (cambio)" : " (sin cambios)"));
                // Solo notificar si cambió: notificar colas idénticas hace que
                // Android Auto recargue la lista constantemente y el scroll no
                // funcione.
                if (changed) {
                    MediaService.childrenChanged("walkman_queue");
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "pushQueue JSON inválido", e);
        }
    }

    private static JSONArray parseArray(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) {
            return new JSONArray();
        }
        JSONArray arr = new JSONArray(json);
        return arr.length() == 0 ? new JSONArray() : arr;
    }
}