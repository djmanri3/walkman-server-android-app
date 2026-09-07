package com.djmanri3.Walkman;

import android.app.Activity;
import android.app.SearchManager;
import android.content.Intent;
import android.os.Bundle;

/**
 * Actividad sin interfaz (Theme.NoDisplay) que recibe las órdenes de voz de
 * Google Assistant / Gemini (App Actions actions.intent.PLAY_MEDIA /
 * GET_THING y el intent legacy android.media.action.MEDIA_PLAY_FROM_SEARCH),
 * extrae la consulta y la reenvía a MainActivity para que la web la busque y
 * reproduzca. MainActivity (singleTask) hace el resto: wmPlaySearch resuelve
 * contra la biblioteca cacheada y, si hace falta, contra el servidor.
 */
public class VoiceSearchActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String query = null;
        Intent in = getIntent();
        if (in != null) {
            // Google Asistente (MEDIA_PLAY_FROM_SEARCH) y App Actions usan
            // la extra "query"; soportamos también las variantes más verbosas.
            if (in.hasExtra(SearchManager.QUERY)) {
                query = in.getStringExtra(SearchManager.QUERY);
            } else if (in.hasExtra("android.app.search.QUERY")) {
                query = in.getStringExtra("android.app.search.QUERY");
            } else if (in.hasExtra("query")) {
                query = in.getStringExtra("query");
            } else if (in.hasExtra(Intent.EXTRA_TEXT)) {
                query = in.getStringExtra(Intent.EXTRA_TEXT);
            }
        }
        if (query != null && !query.trim().isEmpty()) {
            Intent go = new Intent(this, MainActivity.class);
            go.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            go.putExtra(MainActivity.EXTRA_VOICE_QUERY, query.trim());
            try {
                startActivity(go);
            } catch (Throwable t) {
                android.util.Log.e("WALKMAN_AA", "startActivity(MainActivity) fallo", t);
            }
        }
        finish();
    }
}