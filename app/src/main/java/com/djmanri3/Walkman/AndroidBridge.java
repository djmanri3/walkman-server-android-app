package com.djmanri3.Walkman;

import android.util.Log;
import android.webkit.JavascriptInterface;

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
 * de Android (widget, bloqueo, bluetooth, audio focus...).
 */
public class AndroidBridge {

    private static final String TAG = "AndroidBridge";

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

    public AndroidBridge() {
        // Sin estado propio; delega en MediaService.
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
}
