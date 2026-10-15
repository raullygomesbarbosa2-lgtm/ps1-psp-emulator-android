package com.raully.psxpsp;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;

import com.raully.psxpsp.ps1.Ps1CueSheet;
import com.raully.psxpsp.ps1.Ps1DiscImage;
import com.raully.psxpsp.ps1.Ps1ExecutableLoader;
import com.raully.psxpsp.ps1.Ps1Gpu;
import com.raully.psxpsp.ps1.Ps1Machine;
import com.raully.psxpsp.ps1.Ps1Session;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Tela nativa para PS-X EXE experimental e seleção de discos anexados ao CD-ROM, sem boot de jogos. */
public final class MainActivity extends Activity {
    private static final int REQUEST_SELECT_PS1_FILE = 101;
    private static final int REQUEST_SELECT_CUE_BINARY = 102;
    private static final int MAX_CUE_FILE_BYTES = 1024 * 1024;

    private TextView ps1Selection;
    private Uri selectedPs1Uri;
    private boolean selectedPs1Executable;
    private Ps1VideoFrameView ps1VideoView;
    private TextView ps1RunStatus;
    private Button ps1StartButton;
    private Button ps1PauseButton;
    private Button ps1StopButton;
    private Button diagnosticButton;
    private Ps1Session ps1Session;
    private Ps1CueSheet pendingCueSheet;
    private ArrayDeque<String> pendingCueFiles;
    private Map<String, Uri> pendingCueUris;
    private String activeCueFile;
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

        TextView title = text("PLAYSTATION ONE", 28, Color.WHITE, true);
        root.addView(title);
        TextView subtitle = text("Emulador PS1 nativo • projeto em desenvolvimento", 14, Color.LTGRAY, false);
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(-1, -2);
        subParams.bottomMargin = dp(18);
        root.addView(subtitle, subParams);

        ps1Selection = addLibraryCard(root, "PLAYSTATION 1", "Núcleo parcial — ainda não inicia jogos");

        TextView note = text("PS-X EXE funciona apenas em teste experimental. CUE/BIN/ISO podem ser anexados ao CD-ROM, mas o emulador ainda não inicia jogos de disco.", 13, Color.LTGRAY, false);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
        noteParams.topMargin = dp(4);
        root.addView(note, noteParams);
        addVideoPreview(root);
        return scroll;
    }

    private TextView addLibraryCard(LinearLayout root, String name, String description) {
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
        choose.setOnClickListener(v -> openPicker());
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

    private void openPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_SELECT_PS1_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SELECT_CUE_BINARY) {
            handleCueBinaryResult(resultCode, data);
            return;
        }
        if (requestCode != REQUEST_SELECT_PS1_FILE || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        persistReadPermission(uri);
        if (ps1Session != null) stopPs1Session();
        clearPendingCue();
        selectedPs1Uri = uri;
        selectedPs1Executable = false;
        ps1StartButton.setEnabled(false);
        String name = displayName(uri);
        ps1Selection.setText("Arquivo selecionado: " + name);
        String lowerName = name.toLowerCase(java.util.Locale.ROOT);
        if (lowerName.endsWith(".exe")) {
            inspectSelectedPs1File(uri);
        } else if (lowerName.endsWith(".cue")) {
            loadCueSheet(uri);
        } else if (lowerName.endsWith(".iso")) {
            loadStandaloneIso(uri, name);
        } else if (lowerName.endsWith(".bin")) {
            ps1StartButton.setEnabled(false);
            ps1RunStatus.setText("Para abrir um BIN, selecione o arquivo CUE correspondente.");
        } else {
            ps1StartButton.setEnabled(false);
            ps1RunStatus.setText("Escolha um PS-X EXE, um CUE ou um ISO para o teste do núcleo.");
        }
    }

    private void persistReadPermission(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { }
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
                ? "PS-X EXE detectado. Início experimental, sem BIOS externa nem HLE completo."
                : "O arquivo não parece ser um PS-X EXE válido.");
    }

    private void loadCueSheet(Uri cueUri) {
        ps1RunStatus.setText("Lendo a lista de faixas CUE...");
        Thread loader = new Thread(() -> {
            try {
                Ps1CueSheet cue = Ps1CueSheet.parse(readCueText(cueUri));
                LinkedHashSet<String> fileNames = new LinkedHashSet<>();
                for (Ps1CueSheet.Track track : cue.getTracks()) fileNames.add(track.getFileName());
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !cueUri.equals(selectedPs1Uri)) return;
                    pendingCueSheet = cue;
                    pendingCueFiles = new ArrayDeque<>(fileNames);
                    pendingCueUris = new HashMap<>();
                    requestNextCueBinary();
                });
            } catch (IOException | IllegalArgumentException | SecurityException failure) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !cueUri.equals(selectedPs1Uri)) return;
                    ps1RunStatus.setText("Não consegui ler esse CUE. Confira se ele referencia arquivos BIN BINARY.");
                });
            }
        }, "ps1-cue-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void requestNextCueBinary() {
        if (pendingCueFiles == null || pendingCueSheet == null) return;
        if (pendingCueFiles.isEmpty()) {
            finishCueDiscLoad();
            return;
        }
        activeCueFile = pendingCueFiles.peekFirst();
        String expectedName = baseName(activeCueFile);
        ps1RunStatus.setText("Selecione o arquivo de dados indicado no CUE: " + activeCueFile);
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, "Escolha " + expectedName);
        startActivityForResult(intent, REQUEST_SELECT_CUE_BINARY);
    }

    private void handleCueBinaryResult(int resultCode, Intent data) {
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            clearPendingCue();
            ps1RunStatus.setText("Seleção dos arquivos do CUE cancelada. Nenhum jogo foi iniciado.");
            return;
        }
        Uri uri = data.getData();
        persistReadPermission(uri);
        String expected = activeCueFile;
        String selectedName = displayName(uri);
        if (expected == null || !baseName(expected).equalsIgnoreCase(selectedName)) {
            ps1RunStatus.setText("O CUE pede " + (expected == null ? "outro arquivo" : baseName(expected))
                    + ". Selecione esse BIN/ISO para continuar.");
            requestNextCueBinary();
            return;
        }
        pendingCueUris.put(expected, uri);
        pendingCueFiles.removeFirst();
        requestNextCueBinary();
    }

    private void finishCueDiscLoad() {
        Ps1CueSheet cue = pendingCueSheet;
        Map<String, Uri> fileUris = new HashMap<>(pendingCueUris);
        Uri cueUri = selectedPs1Uri;
        clearPendingCue();
        ps1RunStatus.setText("Validando faixas e abrindo os arquivos do disco...");
        Thread loader = new Thread(() -> {
            try {
                Ps1DiscImage image = Ps1DiscImage.open(cue, fileName -> {
                    Uri fileUri = fileUris.get(fileName);
                    if (fileUri == null) throw new IOException("arquivo citado no CUE não foi selecionado");
                    return openRandomAccessDocument(fileUri);
                });
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !cueUri.equals(selectedPs1Uri)) {
                        try { image.close(); } catch (IOException ignored) { }
                        return;
                    }
                    attachDiscImage(image, cueUri, displayName(cueUri));
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !cueUri.equals(selectedPs1Uri)) return;
                    ps1RunStatus.setText("Não consegui abrir os arquivos citados no CUE. Confira os nomes e tamanhos.");
                });
            }
        }, "ps1-disc-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void loadStandaloneIso(Uri uri, String name) {
        ps1RunStatus.setText("Validando ISO e preparando a faixa de dados...");
        Thread loader = new Thread(() -> {
            try {
                Ps1CueSheet cue = Ps1CueSheet.forIso(name);
                Ps1DiscImage image = Ps1DiscImage.open(cue, ignored -> openRandomAccessDocument(uri));
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !uri.equals(selectedPs1Uri)) {
                        try { image.close(); } catch (IOException ignored) { }
                        return;
                    }
                    attachDiscImage(image, uri, name);
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || !uri.equals(selectedPs1Uri)) return;
                    ps1RunStatus.setText("Não consegui abrir esse ISO. Confira se o arquivo está íntegro.");
                });
            }
        }, "ps1-iso-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void attachDiscImage(Ps1DiscImage image, Uri sourceUri, String label) {
        Ps1Session candidate = new Ps1Session();
        try {
            candidate.insertDisc(image);
            selectedPs1Uri = sourceUri;
            stopPs1Session();
            ps1Session = candidate;
            selectedPs1Executable = false;
            ps1StartButton.setEnabled(false);
            ps1PauseButton.setEnabled(false);
            ps1StopButton.setText("Ejetar disco");
            ps1StopButton.setEnabled(true);
            diagnosticButton.setEnabled(true);
            ps1Selection.setText("Disco anexado: " + label);
            ps1RunStatus.setText("Imagem anexada ao CD-ROM. O núcleo ainda não inicia jogos de disco.");
        } catch (IOException | RuntimeException failure) {
            candidate.close();
            ps1RunStatus.setText("Não consegui anexar a imagem ao controlador de CD-ROM.");
        }
    }

    private String readCueText(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("CUE unavailable");
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                if (count > MAX_CUE_FILE_BYTES - total) throw new IOException("CUE is too large");
                output.write(buffer, 0, count);
                total += count;
            }
            String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
            return text.startsWith("\uFEFF") ? text.substring(1) : text;
        }
    }

    private Ps1DiscImage.DataSource openRandomAccessDocument(Uri uri) throws IOException {
        AssetFileDescriptor descriptor = getContentResolver().openAssetFileDescriptor(uri, "r");
        if (descriptor == null) throw new IOException("document unavailable");
        FileInputStream stream = null;
        try {
            stream = new FileInputStream(descriptor.getFileDescriptor());
            FileChannel channel = stream.getChannel();
            long startOffset = descriptor.getStartOffset();
            long size = descriptor.getLength();
            if (size < 0) size = channel.size() - startOffset;
            if (size < 0 || startOffset < 0) throw new IOException("document is not seekable");
            return new AndroidDocumentSource(descriptor, stream, channel, startOffset, size);
        } catch (IOException | RuntimeException failure) {
            if (stream != null) try { stream.close(); } catch (IOException ignored) { }
            try { descriptor.close(); } catch (IOException ignored) { }
            throw failure;
        }
    }

    private void clearPendingCue() {
        pendingCueSheet = null;
        pendingCueFiles = null;
        pendingCueUris = null;
        activeCueFile = null;
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) {
                    String value = cursor.getString(column);
                    if (value != null && !value.isEmpty()) return value;
                }
            }
        } catch (RuntimeException ignored) { }
        String path = uri.getLastPathSegment();
        return path == null ? "arquivo" : path;
    }

    private static String baseName(String path) {
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return separator >= 0 ? path.substring(separator + 1) : path;
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
            ps1Session.close();
            ps1Session = null;
        }
        if (ps1PauseButton != null) {
            ps1PauseButton.setEnabled(false);
            ps1PauseButton.setText("Pausar sessão");
        }
        if (ps1StopButton != null) {
            ps1StopButton.setEnabled(false);
            ps1StopButton.setText("Encerrar sessão");
        }
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
            ending.close();
        }
        super.onDestroy();
    }

    private static final class AndroidDocumentSource implements Ps1DiscImage.DataSource {
        private final AssetFileDescriptor descriptor;
        private final FileInputStream stream;
        private final FileChannel channel;
        private final long startOffset;
        private final long size;

        AndroidDocumentSource(AssetFileDescriptor descriptor, FileInputStream stream,
                              FileChannel channel, long startOffset, long size) {
            this.descriptor = descriptor;
            this.stream = stream;
            this.channel = channel;
            this.startOffset = startOffset;
            this.size = size;
        }

        @Override public long size() { return size; }

        @Override public void readFully(long position, byte[] destination, int offset, int length) throws IOException {
            if (position < 0 || length < 0 || position > size - length || destination == null
                    || offset < 0 || offset > destination.length - length) throw new EOFException("read outside document");
            ByteBuffer buffer = ByteBuffer.wrap(destination, offset, length);
            long filePosition = startOffset + position;
            int idleReads = 0;
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, filePosition);
                if (read < 0) throw new EOFException("document ended during sector read");
                if (read == 0) {
                    if (++idleReads > 32) throw new IOException("document does not support random reads");
                    Thread.yield();
                } else {
                    filePosition += read;
                    idleReads = 0;
                }
            }
        }

        @Override public void close() throws IOException {
            IOException failure = null;
            try { stream.close(); } catch (IOException closeFailure) { failure = closeFailure; }
            try { descriptor.close(); } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
            if (failure != null) throw failure;
        }
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
