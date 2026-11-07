package com.djmanri3.Walkman;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.audiofx.AudioEffect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import com.sony.songpal.localplayer.playbackservice.SonyMediaPlayer;
import org.json.JSONObject;

/** Native Sony DSP owner. Decoder, player and focus changes run on one worker. */
public final class SonyAudioEngine {
    public interface Listener { void onState(String json); }
    private static final String EQ_PACKAGE = "local.extracted.equalizer";
    private static final String EQ_ACTIVITY = "com.sony.songpal.localplayer.playbackservice.MainActivity";
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("SonyAudio");
    private final Handler worker;
    private final AudioManager audioManager;
    private volatile String snapshot = "{\"version\":0,\"playing\":false,\"ready\":false,\"position\":0,\"duration\":0}";
    private volatile Listener listener;
    private volatile boolean closed;
    private SonyMediaPlayer player;
    private int version;
    private boolean prepared, wantsPlay, ended, sessionOpen, hasFocus, resumeOnGain, ducked;
    private float volume = 1;
    private int pendingSeek;
    private String error = "";
    private AudioFocusRequest focusRequest;
    private final AudioManager.OnAudioFocusChangeListener focusListener;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (closed) return;
            publish();
            worker.postDelayed(this, 200);
        }
    };

    public SonyAudioEngine(Context context) {
        this.context = context.getApplicationContext();
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        thread.start();
        worker = new Handler(thread.getLooper());
        focusListener = change -> worker.post(() -> focusChanged(change));
        SonyMediaPlayer.initialize(this.context);
        worker.post(tick);
    }
    @JavascriptInterface public boolean isAvailable() {
        for (String abi : Build.SUPPORTED_ABIS) if (abi.equals("arm64-v8a") || abi.equals("armeabi-v7a")) return true;
        return false;
    }
    @JavascriptInterface public boolean isEqualizerInstalled() {
        Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                .setComponent(new ComponentName(EQ_PACKAGE, EQ_ACTIVITY));
        return context.getPackageManager().resolveActivity(intent, 0) != null;
    }
    @JavascriptInterface public String getState() { return snapshot; }
    public void setListener(Listener listener) { this.listener = listener; }

    @JavascriptInterface public void load(String source, int sourceVersion) {
        if (closed) return;
        worker.post(() -> {
            if (closed) return;
            version = sourceVersion;
            wantsPlay = prepared = ended = false;
            pendingSeek = 0;
            error = "";
            try {
                if (player == null) {
                    player = new SonyMediaPlayer();
                    player.setOnPreparedListener(ignored -> onPrepared());
                    player.setOnCompletionListener(ignored -> {
                        wantsPlay = false; ended = true; publish();
                    });
                    player.setOnErrorListener((ignored, what, extra) -> {
                        fail("No se pudo reproducir el audio (" + what + "/" + extra + ")"); return true;
                    });
                } else player.reset();
                if (source == null || source.isEmpty()) { publish(); return; }
                String scheme = Uri.parse(source).getScheme();
                if (!"https".equals(scheme) && !"http".equals(scheme) && !"content".equals(scheme)) {
                    fail("El motor Sony necesita una URL HTTP/HTTPS o un archivo de audio accesible"); return;
                }
                player.setDataSource(source);
                player.prepareAsync();
            } catch (Exception | LinkageError e) { fail("No se pudo preparar el motor Sony"); }
            publish();
        });
    }
    private void onPrepared() {
        prepared = true;
        if (pendingSeek > 0) player.seekTo(pendingSeek);
        if (wantsPlay) startPlayback();
        publish();
    }
    @JavascriptInterface public void play() {
        if (!closed) worker.post(() -> {
            wantsPlay = true; ended = false;
            if (prepared) startPlayback();
            publish();
        });
    }
    private void startPlayback() {
        if (player == null || player.isPlaying()) return;
        if (!requestFocus()) { fail("No se pudo obtener el foco de audio"); return; }
        try {
            player.setVolume(effectiveVolume(), effectiveVolume());
            player.start();
            if (!player.isPlaying()) return;
            if (!sessionOpen) { announce(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION); sessionOpen = true; }
        } catch (RuntimeException | LinkageError e) { fail("No se pudo iniciar el motor Sony"); }
    }
    @JavascriptInterface public void pause() {
        if (!closed) worker.post(() -> { resumeOnGain = false; wantsPlay = false; if (player != null && prepared) player.pause(); publish(); });
    }
    @JavascriptInterface public void seek(double seconds) {
        if (!closed && Double.isFinite(seconds)) worker.post(() -> {
            pendingSeek = (int) Math.min(Integer.MAX_VALUE, Math.max(0, seconds * 1000));
            if (prepared) player.seekTo(pendingSeek);
            ended = false; publish();
        });
    }
    @JavascriptInterface public void setVolume(double value) {
        if (!closed && Double.isFinite(value)) worker.post(() -> {
            volume = (float) Math.max(0, Math.min(1, value));
            if (player != null) player.setVolume(effectiveVolume(), effectiveVolume());
        });
    }
    private float effectiveVolume() { return volume * (ducked ? 0.2f : 1f); }
    private void fail(String message) {
        wantsPlay = false; error = message;
        if (player != null && prepared) player.pause();
        publish();
    }
    private void publish() {
        try {
            boolean playing = player != null && prepared && player.isPlaying();
            JSONObject state = new JSONObject().put("version", version).put("playing", playing)
                    .put("ready", prepared).put("loading", wantsPlay && !prepared).put("ended", ended).put("error", error)
                    .put("position", prepared ? player.getCurrentPosition() / 1000.0 : 0)
                    .put("duration", prepared ? player.getDuration() / 1000.0 : 0)
                    .put("sampleRate", prepared ? player.getNativeSampleRate() : 48000)
                    .put("bytesWritten", prepared ? player.getNativeBytesWritten() : 0)
                    .put("spectrum", prepared ? android.util.Base64.encodeToString(player.getNativeSpectrum(), android.util.Base64.NO_WRAP) : "");
            snapshot = state.toString();
            String json = snapshot;
            main.post(() -> { Listener target = listener; if (!closed && target != null) target.onState(json); });
        } catch (RuntimeException | org.json.JSONException e) {
            android.util.Log.w("SonyAudio", "No se pudo consultar el estado del reproductor", e);
        }
    }
    private boolean requestFocus() {
        if (hasFocus) return true;
        int result;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setOnAudioFocusChangeListener(focusListener, worker).build();
            result = audioManager.requestAudioFocus(focusRequest);
        } else result = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        hasFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return hasFocus;
    }
    private void focusChanged(int change) {
        if (closed) return;
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            hasFocus = true; ducked = false;
            if (resumeOnGain) { resumeOnGain = false; wantsPlay = true; if (prepared) startPlayback(); }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            ducked = true;
        } else {
            hasFocus = false;
            resumeOnGain = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && wantsPlay;
            wantsPlay = false;
            if (player != null && prepared) player.pause();
        }
        if (player != null) player.setVolume(effectiveVolume(), effectiveVolume());
        publish();
    }
    private void announce(String action) {
        if (player == null || player.getAudioSessionId() <= 0) return;
        Intent intent = new Intent(action).setComponent(new ComponentName(EQ_PACKAGE,
                "com.sony.songpal.localplayer.playbackservice.AudioSessionReceiver"))
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName())
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, player.getAudioSessionId())
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
        context.sendBroadcast(intent);
    }
    @JavascriptInterface public void openEqualizer() {
        main.post(() -> {
            Intent intent = new Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                    .setComponent(new ComponentName(EQ_PACKAGE, EQ_ACTIVITY))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.getPackageName())
                    .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC);
            try { context.startActivity(intent); }
            catch (android.content.ActivityNotFoundException | SecurityException e) {
                Toast.makeText(context, "Instala Sony Equalizer compatible para ajustar Clear Bass y VPT", Toast.LENGTH_LONG).show();
            }
        });
    }
    public void release() {
        if (closed) return;
        closed = true; listener = null;
        worker.removeCallbacks(tick);
        worker.post(() -> {
            if (sessionOpen) announce(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
            if (player != null) { player.release(); player = null; }
            if (hasFocus) {
                if (Build.VERSION.SDK_INT >= 26 && focusRequest != null) audioManager.abandonAudioFocusRequest(focusRequest);
                else audioManager.abandonAudioFocus(focusListener);
            }
            thread.quitSafely();
        });
    }
}
