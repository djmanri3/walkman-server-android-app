package com.djmanri3.Walkman;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.List;

/**
 * Actividad principal que muestra la web WALKMAN en un WebView e inyecta el
 * puente {@link AndroidBridge} para sincronizar el estado con el sistema
 * multimedia de Android. También escucha los comandos del sistema
 * (widget/notificación/bluetooth) y los traduce a llamadas JavaScript.
 */
public class MainActivity extends Activity {

    private static final String WALKMAN_URL = "https://djmanri3.github.io/walkman-server/";
    private static final int REQ_FOLDER = 5001;

    private WebView mWebView;
    private Handler mHandler;
    private MiniHttpServer mHttpServer;

    // Runner de polling para mantener sincronizados posición, estado y pista
    // del widget con el <audio> real del WebView, incluso cuando la web no
    // notifica cada cambio a través del puente.
    private final Runnable mPositionPoll = new Runnable() {
        @Override
        public void run() {
            if (mWebView != null) {
                mWebView.evaluateJavascript(
                        "(function(){ var a=window.__wmAudio; if(!a){return '{}';} " +
                        "var t=document.getElementById('track-title'); " +
                        "var ar=document.getElementById('track-artist'); " +
                        "var curImg=document.getElementById('img-current'); " +
                        "var cArt=''; try{ if(typeof currentTrackIndex==='number' && playlist && playlist[currentTrackIndex]){" +
                        "cArt=(typeof getEmbyImageUrl==='function')?getEmbyImageUrl(playlist[currentTrackIndex]):'';}}catch(e){} " +
                        "if(!cArt && curImg){ cArt=curImg.src||''; } " +
                        "return JSON.stringify({p:a.currentTime||0, d:a.duration||0, " +
                        "playing:!a.paused, " +
                        "title:t?t.textContent:'', artist:ar?ar.textContent:'', cArt:cArt}); })()",
                        new ValueCallback<String>() {
                            @Override
                            public void onReceiveValue(String value) {
                                try {
                                    // evaluateJavascript devuelve el resultado como
                                    // literal JSON; desempaquetamos la string interna.
                                    Object inner = new JSONTokener(value).nextValue();
                                    if (!(inner instanceof String)) return;
                                    JSONObject o = new JSONObject((String) inner);
                                    if (o.length() == 0) return;

                                    long pos = (long) (o.optDouble("p", 0) * 1000);
                                    long dur = (long) (o.optDouble("d", 0) * 1000);
                                    boolean playing = o.optBoolean("playing", false);
                                    String title = o.optString("title", "");
                                    String artist = o.optString("artist", "");
                                    String cArt = o.optString("cArt", "");

                                    MediaService svc = MediaService.instance();
                                    if (svc == null) return;

                                    // La web notifica la carátula ANTES de que la
                                    // animación actualice <img-current>, así que a
                                    // veces entrega la URL de la pista anterior.
                                    // Por eso aquí recalculamos la URL correcta con
                                    // getEmbyImageUrl() y re-sincronizamos si cambió
                                    // la pista O difiere de la carátula ya aplicada.
                                    boolean trackChanged = MediaService.isDifferentTrack(svc, title, artist);
                                    boolean artChanged = !cArt.isEmpty()
                                            && !cArt.equals(MediaService.appliedArtUrl());
                                    if (trackChanged || artChanged) {
                                        final String fArt = cArt;
                                        runOnUiThread(() -> {
                                            if (mWebView != null) {
                                                mWebView.evaluateJavascript(
                                                        "try { if (window.AndroidBridge) window.AndroidBridge.setMediaState(JSON.stringify({" +
                                                        "title: document.getElementById('track-title')?document.getElementById('track-title').textContent:''," +
                                                        "artist: document.getElementById('track-artist')?document.getElementById('track-artist').textContent:''," +
                                                        "album:'', artwork:'" + fArt.replace("'", "\\'") + "'," +
                                                        "playing: !window.__wmAudio.paused, position: window.__wmAudio.currentTime||0, duration: window.__wmAudio.duration||0})); } catch(e){}",
                                                        null);
                                            }
                                        });
                                    } else {
                                        MediaService.updatePlayback(svc, playing, pos, dur);
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                        });
            }
            mHandler.postDelayed(mPositionPoll, 250);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mHandler = new Handler(Looper.getMainLooper());

        // En Android 13+ hay que pedir permiso para mostrar notificaciones.
        requestNotificationPermission();

        mWebView = findViewById(R.id.webview);
        setupWebView();

        // Aseguramos que el MediaService exista para que instance() no sea
        // null cuando la web notifique su estado a través de AndroidBridge.
        startMediaService();

        // Servidor HTTP local para servir los archivos de música elegidos en la
        // web (el <audio> de HTML5 sólo reproduce URLs http/https).
        mHttpServer = new MiniHttpServer(getApplicationContext());
        mHttpServer.start();
        AndroidBridge.setLocalFolderListener(this::launchLocalFolderPicker);

        // Escuchador de comandos del sistema -> JavaScript.
        MediaService.setCommandListener(new MediaService.CommandListener() {
            @Override
            public void onCommand(String command, String argument) {
                runOnUiThread(() -> executeCommand(command, argument));
            }
        });
    }

    /** Inicia el servicio multimedia (crea la sesión y deja listo el puente). */
    private void startMediaService() {
        try {
            Intent intent = new Intent(this, MediaService.class);
            startService(intent);
        } catch (Exception e) {
            // Ignorado: la reproducción web seguirá funcionando sin el widget.
        }
    }

    /** Pide permiso de notificaciones en Android 13+ (necesario para el widget). */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = mWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setAllowFileAccess(true);
        s.setDatabaseEnabled(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        // Usa un User-Agent para que el servidor sirva la versión móvil/web.
        // (En realidad WALKMAN no depende de ello, pero lo dejamos personalizado
        // para que la app sea identificable y reciba el mismo contenido.)
        String ua = s.getUserAgentString();
        s.setUserAgentString(ua + " WalkmanApp/1.0");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                view.loadUrl(request.getUrl().toString());
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectBridgeHelpers();
                mHandler.postDelayed(mPositionPoll, 500);
            }
        });

        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                // Permitir permisos solicitados por la web sin marcos de diálogo.
                runOnUiThread(() -> request.grant(request.getResources()));
            }
        });

        mWebView.setBackgroundColor(0xFF000000);

        // Inyecta el puente AndroidBridge que la web ya espera.
        mWebView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");

        mWebView.loadUrl(WALKMAN_URL);
    }

    /**
     * Expone la referencia global al elemento &lt;audio&gt; para el polling de
     * posición. Se llama al terminar de cargar la página.
     */
    private void injectBridgeHelpers() {
        if (mWebView == null) return;
        mWebView.evaluateJavascript(
                "try { window.__wmAudio = document.getElementById('audio-player'); " +
                "window.__wmAudio.addEventListener('loadedmetadata', function(){ " +
                "  if (window.AndroidBridge && window.__wmAudio) {" +
                "    try { AndroidBridge.setMediaState(JSON.stringify({" +
                "      title: document.title||'', artist:'', album:'', artwork:''," +
                "      playing: !__wmAudio.paused, position: __wmAudio.currentTime||0, duration: __wmAudio.duration||0}));" +
                "    } catch(e){} } }); } catch(e){}", null);
    }

    /** Traduce una orden del sistema a una llamada JavaScript. */
    private void executeCommand(String command, String arg) {
        if (mWebView == null) return;
        switch (command) {
            case "play":
            case "pause":
                mWebView.evaluateJavascript("try { togglePlay(); } catch(e){}", null);
                break;
            case "next":
                mWebView.evaluateJavascript("try { nextTrack(); } catch(e){}", null);
                break;
            case "prev":
                mWebView.evaluateJavascript("try { prevTrack(); } catch(e){}", null);
                break;
            case "seek":
                if (arg != null) {
                    final String a = "'" + arg.replace("'", "\\'") + "'";
                    mWebView.evaluateJavascript("try { " +
                            "var d = document.getElementById('audio-player'); if (d && d.duration) {" +
                            "  var t = Math.min(Math.max(Number(" + a + ")/1000, 0), d.duration); d.currentTime = t; " +
                            "  if (window.AndroidBridge) { try { AndroidBridge.setMediaState(JSON.stringify({" +
                            "    title: document.getElementById('track-title')?document.getElementById('track-title').textContent:''," +
                            "    artist: document.getElementById('track-artist')?document.getElementById('track-artist').textContent:''," +
                            "    album:'', artwork: (document.getElementById('img-current')&&document.getElementById('img-current').src)||''," +
                            "    playing: !d.paused, position: t, duration: d.duration })); } catch(e){} }" +
                            "} } catch(e){}", null);
                }
                break;
            case "stop":
                mWebView.evaluateJavascript("try { var d=document.getElementById('audio-player'); if(d){d.pause(); d.currentTime=0;} } catch(e){}", null);
                break;
            default:
                break;
        }
    }

    /** Lanza el selector de carpeta (SAF) solicitado por la web. */
    private void launchLocalFolderPicker() {
        runOnUiThread(() -> {
            try {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                startActivityForResult(i, REQ_FOLDER);
            } catch (Exception e) {
                android.util.Log.e("LocalFolder", "No se pudo abrir el selector", e);
            }
        });
    }

    /** Recopila los archivos de la carpeta elegida y los inyecta a la web. */
    private void handleFolderResult(Uri treeUri) {
        if (mWebView == null || mHttpServer == null) {
            return;
        }
        final WebView wv = mWebView;
        final MiniHttpServer server = mHttpServer;
        // La recopilación con DocumentFile es lenta con muchos archivos;
        // se hace en un hilo de fondo para no congelar la interfaz (ANR).
        new Thread(() -> {
            final List<String> ids = new ArrayList<>();
            final List<Uri> uris = new ArrayList<>();
            try {
                List<JSONObject> tracks = LocalMusicPicker.collectTree(
                        getApplicationContext(), treeUri, ids, uris, server, server.getPort());

                if (tracks.isEmpty()) {
                    runOnUiThread(() -> {
                        try {
                            wv.evaluateJavascript(
                                    "try { if (window.onAndroidLocalError) onAndroidLocalError('No se encontraron archivos de audio.'); } catch(e){}",
                                    null);
                        } catch (Exception ignored) {
                        }
                    });
                    return;
                }

                JSONArray arr = new JSONArray();
                for (JSONObject t : tracks) {
                    arr.put(t);
                }
                final String json = arr.toString();
                runOnUiThread(() -> {
                    try {
                        wv.evaluateJavascript(
                                "try { if (window.onAndroidLocalTracks) onAndroidLocalTracks(" + json + "); } catch(e){}",
                                null);
                    } catch (Exception ignored) {
                    }
                });
            } catch (Throwable t) {
                android.util.Log.e("LocalFolder", "Error recopilando carpeta", t);
            }
        }, "local-picker").start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                // Persistimos el acceso al árbol para poder leerlo más adelante
                // (el servidor HTTP lo sirve desde el proceso de esta app).
                try {
                    getContentResolver().takePersistableUriPermission(treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                handleFolderResult(treeUri);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) {
            mWebView.onPause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
        }
    }

    @Override
    protected void onDestroy() {
        MediaService.setCommandListener(null);
        AndroidBridge.setLocalFolderListener(null);
        mHandler.removeCallbacks(mPositionPoll);
        if (mHttpServer != null) {
            mHttpServer.stop();
            mHttpServer = null;
        }
        if (mWebView != null) {
            mWebView.destroy();
            mWebView = null;
        }
        super.onDestroy();
    }
}
