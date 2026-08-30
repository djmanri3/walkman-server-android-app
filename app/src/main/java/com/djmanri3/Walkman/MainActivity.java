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

    private static final int REQ_FOLDER = 5001;

    /**
     * Panel de "Servidor Walkman" inyectado en la sección de Ajustes de la web
     * (sin modificar walkman-server): permite elegir entre la web oficial o una
     * URL personalizada consultando/guardando a través de AndroidBridge.
     */
    private static final String SERVER_EMBED_SCRIPT = """
            (function(){
            if(window.__wmServerEmbedded)return;
            var menu=document.querySelector('.settings-menu');
            if(!menu)return;
            if(document.getElementById('wm-server-group'))return;
            var g=document.createElement('div');
            g.className='settings-group';
            g.id='wm-server-group';
            g.innerHTML=''
              +'<div class="settings-btn" id="wm-server-btn" style="cursor:pointer;">'
              +'<span class="material-icons" style="color:var(--accent-color);">public</span>'
              +'<span>Servidor Walkman-server</span></div>'
              +'<div class="featured-size-wrap" id="wm-server-wrap" style="display:none;">'
              +'<div class="settings-group-label">Origen de la web</div>'
              +'<div class="accent-options" id="wm-server-options">'
              +'<div class="accent-option" data-mode="official">Walkman oficial</div>'
              +'<div class="accent-option" data-mode="custom">URL personalizada</div>'
              +'</div>'
              +'<div id="wm-server-custom" style="display:none; margin-top:8px;">'
              +'<input id="wm-server-url" type="text" placeholder="https://tu-servidor.com/walkman" '
              +'style="width:100%; box-sizing:border-box; padding:8px 10px; background:rgba(255,255,255,0.05); color:#fff; border:1px solid rgba(255,255,255,0.25); border-radius:5px; font-size:12px; outline:none;"/>'
              +'<div id="wm-server-save" style="margin-top:6px; padding:9px; text-align:center; color:#000; font-size:12px; font-weight:600; border-radius:5px; cursor:pointer; background:var(--accent-color);">Guardar y recargar</div>'
              +'</div>'
              +'</div>';
            var link=menu.querySelector('.settings-link');
            if(link){menu.insertBefore(g,link);}else{menu.appendChild(g);}
            function setActive(mode,url){
              var opts=g.querySelectorAll('#wm-server-options .accent-option');
              opts.forEach(function(o){o.classList.toggle('active',o.dataset.mode===mode);});
              var cust=g.querySelector('#wm-server-custom');
              if(cust){cust.style.display=(mode==='custom')?'':'none';}
              var input=g.querySelector('#wm-server-url');
              if(input&&mode==='custom'){input.value=url||'';}
            }
            var cfg={};
            try{cfg=JSON.parse(AndroidBridge.getServerConfig()||'{}');}catch(e){}
            var curMode=(cfg&&cfg.useCustom)?'custom':'official';
            setActive(curMode,(cfg&&cfg.useCustom)?(cfg.url||''):'');
            g.querySelector('#wm-server-btn').onclick=function(){
              var wrap=g.querySelector('#wm-server-wrap');
              wrap.style.display=(wrap.style.display==='none')?'':'none';
            };
            g.querySelectorAll('#wm-server-options .accent-option').forEach(function(o){
              o.onclick=function(){
                var m=o.dataset.mode;
                setActive(m,'');
                if(m==='official'){AndroidBridge.setServerConfig('off','');}
              };
            });
            g.querySelector('#wm-server-save').onclick=function(){
              var input=g.querySelector('#wm-server-url');
              var url=(input&&input.value||'').trim();
              if(!url)return;
              AndroidBridge.setServerConfig('custom',url);
            };
            window.__wmServerEmbedded=true;
            })();
            """;

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

        // La web (mediante el panel de servidor inyectado en Ajustes) consulta y
        // cambia la URL de la web (oficial o personalizada).
        AndroidBridge.setServerUrlListener(new AndroidBridge.ServerUrlListener() {
            @Override
            public String onGetConfig() {
                return serverConfigJson();
            }

            @Override
            public void onSetConfig(String mode, String url) {
                final boolean custom = "custom".equals(mode);
                ServerConfig.setCustom(getApplicationContext(), custom,
                        custom ? url : "");
                runOnUiThread(() -> reloadWithConfiguredUrl());
            }
        });

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

        mWebView.loadUrl(ServerConfig.getUrl(this));
    }

    /**
     * Expone la referencia global al elemento &lt;audio&gt; para el polling de
     * posición. Se llama al terminar de cargar la página.
     */
    private void injectBridgeHelpers() {
        if (mWebView == null) return;
        // Panel "Servidor Walkman" embebido en la sección de Ajustes de la web.
        try {
            mWebView.evaluateJavascript(SERVER_EMBED_SCRIPT, null);
        } catch (Exception ignored) {
        }
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

    /** JSON con la configuración actual de servidor para el panel de la web. */
    private String serverConfigJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("useCustom", ServerConfig.isCustom(this));
            o.put("url", ServerConfig.isCustom(this) ? ServerConfig.getCustom(this) : "");
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    /** Recarga la WebView con la URL guardada en {@link ServerConfig}. */
    private void reloadWithConfiguredUrl() {
        if (mWebView == null) return;
        mWebView.loadUrl(ServerConfig.getUrl(this));
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
        AndroidBridge.setServerUrlListener(null);
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
