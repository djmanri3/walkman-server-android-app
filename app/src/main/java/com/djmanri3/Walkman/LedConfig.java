package com.djmanri3.Walkman;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configuración de la emulación de los LEDs transparentes del Xperia SP.
 * Persiste en SharedPreferences si la barra de LEDs debe mostrarse en la
 * pestaña de reproducción de la web.
 */
public final class LedConfig {

    private static final String PREFS = "walkman_leds";
    private static final String KEY_ENABLED = "enabled";

    private LedConfig() {
    }

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
