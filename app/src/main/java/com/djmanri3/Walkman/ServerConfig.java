package com.djmanri3.Walkman;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Configuración del servidor (URL de la web WALKMAN) elegida por el usuario.
 * Permite usar la web oficial por defecto o una URL personalizada que se
 * guarda en SharedPreferences para que no se pierda al reiniciar la app.
 */
public final class ServerConfig {

    public static final String DEFAULT_URL = "https://djmanri3.github.io/walkman-server/";

    private static final String PREFS = "walkman_server_url";
    private static final String KEY_MODE = "use_custom";
    private static final String KEY_CUSTOM = "custom_url";

    private ServerConfig() {
    }

    /** Devuelve la URL que debe cargar la app (oficial o personalizada). */
    public static String getUrl(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getBoolean(KEY_MODE, false)) {
            String url = p.getString(KEY_CUSTOM, "").trim();
            if (!url.isEmpty()) {
                return normalize(url);
            }
        }
        return DEFAULT_URL;
    }

    public static boolean isCustom(Context context) {
        return prefs(context).getBoolean(KEY_MODE, false);
    }

    public static String getCustom(Context context) {
        return prefs(context).getString(KEY_CUSTOM, "");
    }

    /** Guarda la elección del usuario. */
    public static void setCustom(Context context, boolean useCustom, String customUrl) {
        prefs(context).edit()
                .putBoolean(KEY_MODE, useCustom)
                .putString(KEY_CUSTOM, customUrl == null ? "" : customUrl.trim())
                .apply();
    }

    /** Añade https:// si la URL no lleva esquema. */
    static String normalize(String url) {
        String u = url == null ? "" : url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://" + u;
        }
        return u;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
