package com.djmanri3.Walkman;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

/**
 * Actividad (a pantalla completa) para elegir el color de acento del widget:
 * el de la carátula o un color personalizado elegido con un espectro HSB.
 * La preferencia se guarda en {@link MediaStateStore} y se refrescan los widgets.
 */
public class AccentColorActivity extends Activity {

    private int mManualColor = 0xFF00E5FF;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_accent_color);

        final MediaStateStore store = new MediaStateStore(this);

        final ColorPickerView picker = findViewById(R.id.color_picker);
        final View preview = findViewById(R.id.preview_swatch);

        View auto = findViewById(R.id.opt_auto);
        auto.setOnClickListener(v -> {
            store.setAccentManual(false);
            refreshWidgets();
            finish();
        });

        // Color personalizado inicial: el guardado o uno por defecto.
        if (store.isAccentManual()) {
            mManualColor = store.getAccentOverride();
        }
        picker.setColor(mManualColor);
        preview.setBackgroundColor(mManualColor);

        picker.setOnColorListener(color -> {
            mManualColor = color;
            preview.setBackgroundColor(color);
        });

        Button use = findViewById(R.id.btn_use);
        use.setOnClickListener(v -> {
            store.setAccentManual(true);
            store.setAccentOverride(mManualColor);
            refreshWidgets();
            finish();
        });
    }

    private void refreshWidgets() {
        Intent i = new Intent(WalkmanWidgetProvider.ACTION_UPDATE)
                .setPackage(getPackageName());
        sendBroadcast(i);
        Toast.makeText(this, "Color de acento actualizado", Toast.LENGTH_SHORT).show();
    }
}
