package com.raully.psxpsp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

/** Tela inicial nativa; importa e identifica arquivos, mas ainda não roda jogos. */
public final class MainActivity extends Activity {
    private TextView ps1Selection;
    private TextView pspSelection;
    private int pendingSystem = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setStatusBarColor(Color.rgb(16, 20, 16));
        getWindow().setNavigationBarColor(Color.rgb(16, 20, 16));
        setContentView(buildScreen());
    }

    private View buildScreen() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(20));
        root.setBackgroundColor(Color.rgb(16, 20, 16));

        TextView title = text("PS1 + PSP", 28, Color.WHITE, true);
        root.addView(title);
        TextView subtitle = text("Bibliotecas separadas • projeto em desenvolvimento", 14, Color.LTGRAY, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.bottomMargin = dp(18);
        root.addView(subtitle, subParams);

        ps1Selection = addLibraryCard(root, "PLAYSTATION 1", "Núcleo HLE sem BIOS externa — ainda não implementado", 1);
        pspSelection = addLibraryCard(root, "PLAYSTATION PORTABLE", "Núcleo próprio — etapa futura, ainda não implementado", 2);

        TextView note = text("Esta versão é somente a base da interface e do seletor de arquivos. Ela ainda não emula nem inicia jogos.", 13, Color.LTGRAY, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(4);
        root.addView(note, noteParams);
        return root;
    }

    private TextView addLibraryCard(LinearLayout root, String name, String description, int system) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(28, 34, 28));
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), Color.rgb(57, 255, 20));
        card.setBackground(bg);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(14);
        root.addView(card, cardParams);

        TextView heading = text(name, 18, Color.rgb(57, 255, 20), true);
        card.addView(heading);
        TextView detail = text(description, 13, Color.LTGRAY, false);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(-1, -2);
        detailParams.topMargin = dp(6);
        card.addView(detail, detailParams);
        TextView selected = text("Nenhum arquivo selecionado", 13, Color.WHITE, false);
        LinearLayout.LayoutParams selectedParams = new LinearLayout.LayoutParams(-1, -2);
        selectedParams.topMargin = dp(12);
        card.addView(selected, selectedParams);

        Button choose = new Button(this);
        choose.setText("Selecionar arquivo local");
        choose.setAllCaps(false);
        choose.setOnClickListener(v -> openPicker(system));
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(-1, -2);
        buttonParams.topMargin = dp(8);
        card.addView(choose, buttonParams);
        return selected;
    }

    private void openPicker(int system) {
        pendingSystem = system;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, 100 + system);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
        String label = "Arquivo selecionado: " + uri.getLastPathSegment();
        TextView destination = (requestCode == 101) ? ps1Selection : (requestCode == 102 ? pspSelection : null);
        if (destination != null) destination.setText(label);
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        view.setGravity(Gravity.START);
        return view;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
