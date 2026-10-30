package it.paolo.telegrampdfshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * Colore personalizzabile per ogni tasto PDF.
 * Salvato in "slot_color_N" (0 = colore predefinito, azzurro).
 */
final class SlotColors {

    private static final int[] PALETTE = new int[] {
            0xFF28D7F5, // azzurro (predefinito)
            0xFF48D597, // verde
            0xFFFFD84D, // giallo
            0xFFFFB86B, // arancio
            0xFFFF7A7A, // rosso
            0xFFFF8FC8, // rosa
            0xFFB69BFF, // viola
            0xFFF6FAFD  // bianco
    };

    static final int DEFAULT_COLOR = PALETTE[0];

    private SlotColors() {
    }

    static int get(SharedPreferences prefs, int slot) {
        int stored = prefs.getInt("slot_color_" + slot, 0);
        return stored == 0 ? DEFAULT_COLOR : stored;
    }

    static boolean isCustom(SharedPreferences prefs, int slot) {
        return prefs.getInt("slot_color_" + slot, 0) != 0;
    }

    static void set(SharedPreferences prefs, int slot, int color) {
        SharedPreferences.Editor editor = prefs.edit();
        if (color == 0 || color == DEFAULT_COLOR) {
            editor.remove("slot_color_" + slot);
        } else {
            editor.putInt("slot_color_" + slot, color);
        }
        editor.apply();
    }

    static GradientDrawable dot(int color, int sizePx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        drawable.setStroke(Math.max(1, sizePx / 14), 0x66FFFFFF);
        drawable.setSize(sizePx, sizePx);
        return drawable;
    }

    /** Mostra la tavolozza e chiama onChanged dopo la scelta. */
    static void showPicker(Activity activity, SharedPreferences prefs, int slot, String title, Runnable onChanged) {
        float density = activity.getResources().getDisplayMetrics().density;
        int size = Math.round(44 * density);
        int margin = Math.round(6 * density);
        int current = get(prefs, slot);

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * density);
        root.setPadding(pad, pad, pad, 0);

        final AlertDialog[] holder = new AlertDialog[1];
        LinearLayout row = null;
        for (int i = 0; i < PALETTE.length; i++) {
            if (i % 4 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_HORIZONTAL);
                root.addView(row);
            }
            final int color = PALETTE[i];
            View swatch = new View(activity);
            GradientDrawable drawable = dot(color, size);
            if (color == current) {
                drawable.setStroke(Math.round(3 * density), Color.WHITE);
            }
            swatch.setBackground(drawable);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, margin, margin, margin);
            swatch.setLayoutParams(params);
            swatch.setOnClickListener(v -> {
                set(prefs, slot, color);
                if (holder[0] != null) holder[0].dismiss();
                if (onChanged != null) onChanged.run();
            });
            row.addView(swatch);
        }

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(root)
                .setNegativeButton("Annulla", null)
                .setNeutralButton("Predefinito", (d, which) -> {
                    set(prefs, slot, 0);
                    if (onChanged != null) onChanged.run();
                })
                .create();
        holder[0] = dialog;
        dialog.show();
    }
}
