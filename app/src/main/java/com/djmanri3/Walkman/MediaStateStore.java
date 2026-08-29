package com.djmanri3.Walkman;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Almacén mínimo del estado de reproducción en SharedPreferences para que el
 * widget (que corre en el proceso del launcher) pueda leer título, artista,
 * carátula, estado y progreso sin depender del MediaService en vivo.
 */
public class MediaStateStore {

    private static final String PREFS = "walkman_media_state";

    private final SharedPreferences mPrefs;

    public MediaStateStore(Context context) {
        mPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void setTitle(String v) { put("title", v == null ? "" : v); }
    public String getTitle() { return mPrefs.getString("title", ""); }

    public void setArtist(String v) { put("artist", v == null ? "" : v); }
    public String getArtist() { return mPrefs.getString("artist", ""); }

    public void setArtworkUrl(String v) { put("artworkUrl", v == null ? "" : v); }
    public String getArtworkUrl() { return mPrefs.getString("artworkUrl", ""); }

    public void setArtworkPath(String v) { put("artworkPath", v == null ? "" : v); }
    public String getArtworkPath() { return mPrefs.getString("artworkPath", ""); }

    public void setDuration(long v) { put("duration", v); }
    public long getDuration() { return mPrefs.getLong("duration", 0L); }

    public void setPosition(long v) { put("position", v); }
    public long getPosition() { return mPrefs.getLong("position", 0L); }

    public void setPlaying(boolean v) { put("playing", v); }
    public boolean isPlaying() { return mPrefs.getBoolean("playing", false); }

    public void setAccentColor(int v) { putInt("accentColor", v); }
    public int getAccentColor() {
        Object v = mPrefs.getAll().get("accentColor");
        if (v instanceof Integer) {
            return (Integer) v;
        }
        if (v instanceof Long) {
            int color = ((Long) v).intValue();
            putInt("accentColor", color);
            return color;
        }
        return 0xFFAA00FF;
    }

    private void put(String key, String value) {
        mPrefs.edit().putString(key, value).apply();
    }

    private void putInt(String key, int value) {
        mPrefs.edit().putInt(key, value).apply();
    }

    private void put(String key, long value) {
        mPrefs.edit().putLong(key, value).apply();
    }

    private void put(String key, boolean value) {
        mPrefs.edit().putBoolean(key, value).apply();
    }
}
