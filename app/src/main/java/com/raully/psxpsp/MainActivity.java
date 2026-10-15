package com.raully.psxpsp;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

import com.raully.psxpsp.ps1.Ps1ExecutableLoader;
import com.raully.psxpsp.ps1.Ps1Gpu;
import com.raully.psxpsp.ps1.Ps1Machine;
import com.raully.psxpsp.ps1.Ps1Session;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Tela inicial nativa com prévia GPU e execução experimental de PS-X EXE, sem suporte a jogos de disco. */
public final class MainActivity extends Activity {
    private TextView ps1Selection;
    private TextView pspSelection;
    private int pendingSystem = 0;
    private Uri selectedPs1Uri;
    private boolean selectedPs1Executable;
    private Ps1VideoFrameView ps1VideoView;
    private TextView ps1RunStatus;
    private Button ps1StartButton;
    private Button ps1PauseButton;
    private Button ps1StopButton;
    private Button diagnosticButton;
    private Ps1Session ps1Session;
    private boolean activityResumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(0);
        getWindow().setStatusBarColor(Color.rgb(16, 20, 16));
        getWindow().setNavigationBarColor(Color.rgb(16, 20, 16));
        setContentView(buildScreen());
    }

    private View buildScreen() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(16, 20, 16));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));
        root.setBackgroundColor(Color.rgb(16, 20, 16));
        scroll.addView(root);

        TextView title = text("PS1 + PSP", 28, Color.WHITE, true);
        root.addView(title);
        TextView subtitle = text("Bibliotecas separadas • projeto em desenvolvimento", 14, Color.LTGRAY, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.bottomMargin = dp(18);
        root.addView(subtitle, subParams);

        ps1Selection = addLibraryCard(root, "PLAYSTATION 1", "Núcleo parcial — ainda não inicia jogos", 1);
        pspSelection = addLibraryCard(root, "PLAYSTATION PORTABLE", "Núcleo próprio — etapa futura, ainda não implementado", 2);

        TextView note = text("Selecionar um arquivo não inicia a sessão automaticamente. Só PS-X EXE pode ser iniciado em modo experimental; jogos BIN/CUE/ISO ainda não rodam.", 13, Color.LTGRAY, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(4);
        root.addView(note, noteParams);
        addVideoPreview(root);
        return scroll;
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

    private void addVideoPreview(LinearLayout root) {
        TextView heading = text("SAÍDA DE VÍDEO PS1", 18, Color.rgb(57, 255, 20), true);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
        headingParams.topMargin = dp(18);
        root.addView(heading, headingParams);

        TextView explanation = text("Prévia técnica gerada pelo núcleo — não é um jogo.", 13, Color.LTGRAY, false);
        LinearLayout.LayoutParams explanationParams = new LinearLayout.LayoutParams(-1, -2);
        explanationParams.topMargin = dp(5);
        root.addView(explanation, explanationParams);

        ps1VideoView = new Ps1VideoFrameView(this);
        LinearLayout.LayoutParams videoParams = new LinearLayout.LayoutParams(-1, dp(220));
        videoParams.topMargin = dp(10);
        videoParams.bottomMargin = dp(4);
        root.addView(ps1VideoView, videoParams);

        ps1RunStatus = text("Selecione um executável PS-X EXE para iniciar uma sessão experimental.", 13, Color.LTGRAY, false);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(8);
        root.addView(ps1RunStatus, statusParams);

        ps1StartButton = new Button(this);
        ps1StartButton.setText("Iniciar sessão PS-X EXE (teste)");
        ps1StartButton.setAllCaps(false);
        ps1StartButton.setEnabled(false);
        ps1StartButton.setOnClickListener(v -> startStandaloneExeSession());
        root.addView(ps1StartButton, new LinearLayout.LayoutParams(-1, -2));

        ps1PauseButton = new Button(this);
        ps1PauseButton.setText("Pausar sessão");
        ps1PauseButton.setAllCaps(false);
        ps1PauseButton.setEnabled(false);
        ps1PauseButton.setOnClickListener(v -> togglePs1Pause());
        root.addView(ps1PauseButton, new LinearLayout.LayoutParams(-1, -2));

        ps1StopButton = new Button(this);
        ps1StopButton.setText("Encerrar sessão");
        ps1StopButton.setAllCaps(false);
        ps1StopButton.setEnabled(false);
        ps1StopButton.setOnClickListener(v -> stopPs1Session());
        root.addView(ps1StopButton, new LinearLayout.LayoutParams(-1, -2));

        diagnosticButton = new Button(this);
        diagnosticButton.setText("Gerar prévia técnica");
        diagnosticButton.setAllCaps(false);
        diagnosticButton.setOnClickListener(v -> renderDiagnosticFrame(ps1VideoView));
        root.addView(diagnosticButton, new LinearLayout.LayoutParams(-1, -2));
    }

    private void renderDiagnosticFrame(Ps1VideoFrameView videoView) {
        Ps1Machine machine = new Ps1Machine();
        int port = Ps1Gpu.DATA_PORT;
        int[] colors = {
                0x000000FF, 0x0000FF00, 0x00FF0000,
                0x0000FFFF, 0x00FFFF00, 0x00FF00FF
        };
        machine.getBus().write32(port, 0x02120C08); // opaque dark background
        machine.getBus().write32(port, 0);
        machine.getBus().write32(port, 256 | (240 << 16));
        for (int i = 0; i < colors.length; i++) {
            machine.getBus().write32(port, 0x60000000 | colors[i]);
            machine.getBus().write32(port, (24 + i * 34) | (38 << 16));
            machine.getBus().write32(port, 30 | (68 << 16));
        }
        machine.getBus().write32(port, 0x20000000 | 0x00FFFFFF); // flat white triangle
        machine.getBus().write32(port, (62) | (132 << 16));
        machine.getBus().write32(port, (194) | (132 << 16));
        machine.getBus().write32(port, (128) | (222 << 16));
        videoView.present(machine.renderDisplayFrame());
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
        if (requestCode == 101) {
            if (ps1Session != null) stopPs1Session();
            selectedPs1Uri = uri;
            inspectSelectedPs1File(uri);
        }
    }

    private void inspectSelectedPs1File(Uri uri) {
        selectedPs1Executable = false;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input != null) {
                byte[] magic = new byte[8];
                int count = 0;
                while (count < magic.length) {
                    int read = input.read(magic, count, magic.length - count);
                    if (read < 0) break;
                    count += read;
                }
                byte[] expected = {'P', 'S', '-', 'X', ' ', 'E', 'X', 'E'};
                selectedPs1Executable = count == expected.length;
                for (int i = 0; selectedPs1Executable && i < expected.length; i++) {
                    selectedPs1Executable = magic[i] == expected[i];
                }
            }
        } catch (IOException | SecurityException ignored) { }
        ps1StartButton.setEnabled(selectedPs1Executable);
        ps1RunStatus.setText(selectedPs1Executable
                ? "Executável PS-X EXE detectado. Início experimental, sem BIOS e sem serviços HLE completos."
                : "Arquivo selecionado. Esta etapa aceita apenas PS-X EXE; imagens BIN/CUE/ISO ainda não iniciam.");
    }

    private void startStandaloneExeSession() {
        final Uri uri = selectedPs1Uri;
        if (uri == null || !selectedPs1Executable || ps1StartButton == null) return;
        ps1StartButton.setEnabled(false);
        ps1RunStatus.setText("Carregando executável PS-X EXE...");
        Thread loader = new Thread(() -> {
            Ps1Session candidate = new Ps1Session();
            try {
                byte[] executable = readSelectedFile(uri);
                Ps1ExecutableLoader.ExecutableInfo info = candidate.loadExecutable(executable);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !uri.equals(selectedPs1Uri)) {
                        candidate.close();
                        return;
                    }
                    if (!activityResumed) {
                        candidate.close();
                        ps1StartButton.setEnabled(selectedPs1Executable);
                        ps1RunStatus.setText("A tela foi pausada durante o carregamento. Toque para tentar novamente.");
                        return;
                    }
                    stopPs1Session();
                    ps1Session = candidate;
                    ps1VideoView.clearFrame();
                    try {
                        candidate.start(frame -> {
                            if (ps1Session == candidate && !isFinishing() && !isDestroyed()) {
                                ps1VideoView.present(frame);
                            }
                        });
                        ps1StartButton.setEnabled(false);
                        ps1PauseButton.setText("Pausar sessão");
                        ps1PauseButton.setEnabled(true);
                        ps1StopButton.setEnabled(true);
                        diagnosticButton.setEnabled(false);
                        ps1RunStatus.setText(String.format("Sessão experimental ativa — PC 0x%08X, %d bytes. Sem HLE completo; pode não executar este programa.", info.entryPoint, info.textSize));
                    } catch (IllegalStateException startFailure) {
                        ps1Session = null;
                        candidate.close();
                        ps1StartButton.setEnabled(true);
                        ps1RunStatus.setText("Não foi possível iniciar este executável experimental.");
                    }
                });
            } catch (IOException | IllegalArgumentException | SecurityException failure) {
                candidate.close();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !uri.equals(selectedPs1Uri)) return;
                    ps1StartButton.setEnabled(selectedPs1Executable);
                    ps1RunStatus.setText("Não foi possível carregar o PS-X EXE. Confira o arquivo e tente novamente.");
                });
            }
        }, "ps1-exe-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private byte[] readSelectedFile(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("file unavailable");
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                if (count > Ps1ExecutableLoader.MAX_FILE_SIZE - total) {
                    throw new IOException("file exceeds supported PS-X EXE size");
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        }
    }

    private void stopPs1Session() {
        if (ps1Session != null) {
            ps1Session.stop();
            ps1Session = null;
        }
        if (ps1PauseButton != null) {
            ps1PauseButton.setEnabled(false);
            ps1PauseButton.setText("Pausar sessão");
        }
        if (ps1StopButton != null) ps1StopButton.setEnabled(false);
        if (diagnosticButton != null) diagnosticButton.setEnabled(true);
        if (ps1StartButton != null) ps1StartButton.setEnabled(selectedPs1Executable);
        if (ps1RunStatus != null) ps1RunStatus.setText("Sessão encerrada.");
    }

    private void togglePs1Pause() {
        if (ps1Session == null) return;
        if (ps1Session.getState() == Ps1Session.State.RUNNING) {
            ps1Session.pause();
            ps1PauseButton.setText("Retomar sessão");
            ps1RunStatus.setText("Sessão pausada.");
        } else if (ps1Session.getState() == Ps1Session.State.PAUSED) {
            ps1Session.resume();
            ps1PauseButton.setText("Pausar sessão");
            ps1RunStatus.setText("Sessão experimental ativa.");
        }
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        if (ps1Session != null && ps1Session.getState() == Ps1Session.State.RUNNING) {
            ps1Session.pause();
            if (ps1PauseButton != null) ps1PauseButton.setText("Retomar sessão");
        }
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        if (ps1Session != null && ps1Session.getState() == Ps1Session.State.PAUSED) {
            ps1Session.resume();
            if (ps1PauseButton != null) ps1PauseButton.setText("Pausar sessão");
        }
    }

    @Override
    protected void onDestroy() {
        if (ps1Session != null) {
            Ps1Session ending = ps1Session;
            ps1Session = null;
            ending.stop();
        }
        super.onDestroy();
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
