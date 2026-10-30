package it.paolo.telegrampdfshare;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Environment;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {

    private static final int REQUEST_OPEN_PDF = 1001;
    private static final String PREFS = "telegram_pdf_share_prefs";
    /** Numero totale di slot: 7 PDF da inoltrare + 1 slot "Scarica". */
    static final int SLOT_COUNT = 8;
    /** Lo slot che salva il PDF in Download invece di inoltrarlo a WhatsApp. */
    static final int DOWNLOAD_SLOT = 8;

    static String slotLabel(int slot) {
        return slot == DOWNLOAD_SLOT ? "Scarica" : "PDF " + slot;
    }
    private static final String PDF_PROVIDER_AUTHORITY = "it.paolo.telegrampdfshare.pdfprovider";

    private static final String KEY_API_ID = "telegram_api_id";
    private static final String KEY_API_HASH = "telegram_api_hash";
    private static final String KEY_PHONE = "telegram_phone";
    private static final String KEY_FILTER = "filter";
    private static final String KEY_CHANNEL_ID = "channel_id";
    private static final String KEY_CHANNEL_TITLE = "channel_title";
    private static final String KEY_PDF_URI = "pdf_uri";
    private static final String KEY_PDF_NAME = "pdf_name";
    private static final String KEY_SHARED_MESSAGE_IDS = "shared_message_ids";

    private EditText editApiId;
    private EditText editApiHash;
    private EditText editPhone;
    private EditText editCode;
    private EditText editPassword;
    private EditText editFilter;
    private TextView textTelegramStatus;
    private TextView textSelectedChannel;
    private TextView textPdfSearchInfo;
    private TextView textSelectedPdf;
    private TextView textStatus;
    private LinearLayout pdfListContainer;
    private Button buttonConnectTelegram;
    private Button buttonConfirmCode;
    private Button buttonConfirmPassword;
    private Button buttonSelectChannel;
    private Button buttonRefreshPdfs;
    private Button buttonShare;
    private TextView textAutoSearchSummary;
    private TextView textScheduleDiagnostics;
    private Button buttonOpenSearches;
    private Button buttonStartDashboardSearch;
    private Button buttonShareAvailable;
    private LinearLayout dashboardSlotsContainer;
    private final Button[] dashboardTiles = new Button[SLOT_COUNT];
    private final boolean[] tileReady = new boolean[SLOT_COUNT];
    private boolean dashboardReceiverRegistered = false;

    private SharedPreferences prefs;
    private Uri selectedPdfUri;
    private String selectedPdfName;

    static volatile Client telegramClient;
    static final Map<Long, TdApi.Chat> chats = new ConcurrentHashMap<>();
    private final Map<Integer, PdfItem> pendingDownloads = new ConcurrentHashMap<>();
    private final List<PdfItem> pdfItems = Collections.synchronizedList(new ArrayList<>());
    static volatile boolean telegramReady = false;
    static volatile boolean telegramStarting = false;
    private volatile boolean pdfSearchRunning = false;
    private volatile String currentFilter = "";
    private long selectedChannelId = 0L;
    private String selectedChannelTitle = "";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final BroadcastReceiver dashboardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            renderDashboard();
        }
    };
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        StorageCleanup.runV14Migration(this);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        editApiId = findViewById(R.id.editApiId);
        editApiHash = findViewById(R.id.editApiHash);
        editPhone = findViewById(R.id.editPhone);
        editCode = findViewById(R.id.editCode);
        editPassword = findViewById(R.id.editPassword);
        editFilter = findViewById(R.id.editFilter);
        textTelegramStatus = findViewById(R.id.textTelegramStatus);
        textSelectedChannel = findViewById(R.id.textSelectedChannel);
        textPdfSearchInfo = findViewById(R.id.textPdfSearchInfo);
        textSelectedPdf = findViewById(R.id.textSelectedPdf);
        textStatus = findViewById(R.id.textStatus);
        pdfListContainer = findViewById(R.id.pdfListContainer);

        buttonConnectTelegram = findViewById(R.id.buttonConnectTelegram);
        buttonConfirmCode = findViewById(R.id.buttonConfirmCode);
        buttonConfirmPassword = findViewById(R.id.buttonConfirmPassword);
        buttonSelectChannel = findViewById(R.id.buttonSelectChannel);
        buttonRefreshPdfs = findViewById(R.id.buttonRefreshPdfs);
        buttonShare = findViewById(R.id.buttonShare);
        textAutoSearchSummary = findViewById(R.id.textAutoSearchSummary);
        textScheduleDiagnostics = findViewById(R.id.textScheduleDiagnostics);
        buttonOpenSearches = findViewById(R.id.buttonOpenSearches);
        buttonStartDashboardSearch = findViewById(R.id.buttonStartDashboardSearch);
        buttonShareAvailable = findViewById(R.id.buttonShareAvailable);
        dashboardSlotsContainer = findViewById(R.id.dashboardSlotsContainer);

        Button buttonOpenApiPage = findViewById(R.id.buttonOpenApiPage);
        Button buttonSave = findViewById(R.id.buttonSave);
        Button buttonPickPdf = findViewById(R.id.buttonPickPdf);
        Button buttonClearHistory = findViewById(R.id.buttonClearHistory);

        restoreState();
        buildDashboardPanels();
        renderDashboard();

        buttonOpenApiPage.setOnClickListener(v -> openTelegramApiPage());
        buttonConnectTelegram.setOnClickListener(v -> startTelegramClient(false));
        buttonConfirmCode.setOnClickListener(v -> confirmTelegramCode());
        buttonConfirmPassword.setOnClickListener(v -> confirmTelegramPassword());
        buttonSelectChannel.setOnClickListener(v -> selectTelegramChannel());
        buttonRefreshPdfs.setOnClickListener(v -> searchPdfs());
        buttonSave.setOnClickListener(v -> saveSettings());
        buttonPickPdf.setOnClickListener(v -> pickPdf());
        buttonShare.setOnClickListener(v -> shareManualPdf());
        buttonClearHistory.setOnClickListener(v -> clearSharedHistory());
        buttonOpenSearches.setOnClickListener(v -> startActivity(new Intent(this, SearchesActivity.class)));
        buttonStartDashboardSearch.setOnClickListener(v -> startDashboardSearch());
        buttonShareAvailable.setOnClickListener(v -> shareAvailablePdfs());

        updateAutoSearchSummary();

        if (hasStoredTelegramConfiguration()) {
            mainHandler.postDelayed(() -> startTelegramClient(true), 350);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAutoSearchSummary();
        renderDashboard();
        if (!dashboardReceiverRegistered) {
            IntentFilter filter = new IntentFilter(PdfSearchService.ACTION_UPDATED);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(dashboardReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(dashboardReceiver, filter);
            }
            dashboardReceiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (dashboardReceiverRegistered) {
            try {
                unregisterReceiver(dashboardReceiver);
            } catch (Exception ignored) {
            }
            dashboardReceiverRegistered = false;
        }
    }


    private void buildDashboardPanels() {
        if (dashboardSlotsContainer == null || dashboardSlotsContainer.getChildCount() > 0) {
            return;
        }
        LinearLayout row = null;
        for (int index = 0; index < SLOT_COUNT; index++) {
            final int slot = index + 1;
            boolean left = index % 2 == 0;
            if (left) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, dp(8), 0, 0);
                row.setLayoutParams(rowParams);
                dashboardSlotsContainer.addView(row);
            }

            Button tile = new Button(this);
            tile.setAllCaps(false);
            tile.setTextSize(14f);
            tile.setTypeface(null, Typeface.BOLD);
            tile.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            tile.setPadding(dp(14), 0, dp(14), 0);
            tile.setMinHeight(0);
            tile.setMinimumHeight(0);
            tile.setStateListAnimator(null);
            LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(0, dp(52), 1f);
            tileParams.setMargins(left ? 0 : dp(4), 0, left ? dp(4) : 0, 0);
            tile.setLayoutParams(tileParams);
            tile.setOnClickListener(v -> onTileClicked(slot));
            tile.setOnLongClickListener(v -> {
                SlotColors.showPicker(this, prefs, slot, "Colore di " + slotLabel(slot), this::renderDashboard);
                return true;
            });
            row.addView(tile);
            dashboardTiles[index] = tile;
            styleTile(tile, slotLabel(slot), "", 0, R.color.navy_card, R.color.divider, R.color.text_secondary, 1f);
        }
    }

    private void onTileClicked(int slot) {
        if (!tileReady[slot - 1]) {
            Toast.makeText(this, "Nessun PDF disponibile", Toast.LENGTH_SHORT).show();
            return;
        }
        if (slot == DOWNLOAD_SLOT) {
            saveSlotToDownloads(slot);
        } else {
            shareDashboardSlot(slot, dashboardTiles[slot - 1]);
        }
    }

    private int color(int resId) {
        return getResources().getColor(resId, getTheme());
    }

    private void styleTile(Button tile, String label, String suffix, int suffixColor,
                           int fillRes, int strokeRes, int textRes, float alpha) {
        styleTileRaw(tile, label, suffix, suffixColor, color(fillRes), strokeRes == 0 ? 0 : color(strokeRes),
                color(textRes), alpha);
    }

    private void styleTileRaw(Button tile, String label, String suffix, int suffixColor,
                              int fill, int stroke, int textColor, float alpha) {
        SpannableStringBuilder text = new SpannableStringBuilder(label);
        text.setSpan(new ForegroundColorSpan(textColor), 0, label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (suffix != null && !suffix.isEmpty()) {
            int from = text.length();
            text.append(suffix);
            text.setSpan(new ForegroundColorSpan(suffixColor == 0 ? textColor : suffixColor),
                    from, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tile.setText(text);
        GradientDrawable background = new GradientDrawable();
        background.setColor(fill);
        background.setCornerRadius(dp(12));
        if (stroke != 0) background.setStroke(dp(1), stroke);
        tile.setBackground(background);
        tile.setAlpha(alpha);
    }

    private void renderDashboard() {
        if (prefs == null || dashboardSlotsContainer == null) {
            return;
        }
        ensureDashboardTodayState();
        int found = 0;
        int ready = 0;
        int handled = 0;
        int configured = 0;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(new Date());

        for (int slot = 1; slot <= SLOT_COUNT; slot++) {
            int index = slot - 1;
            Button tile = dashboardTiles[index];
            if (tile == null) continue;

            String filter = prefs.getString("search_filter_" + slot, "").trim();
            if (!filter.isEmpty()) configured++;
            boolean handledToday = !filter.isEmpty() && today.equals(prefs.getString("search_handled_day_key_" + slot, ""));
            boolean isFound = !filter.isEmpty() && prefs.getBoolean("search_found_" + slot, false)
                    && today.equals(prefs.getString("search_result_day_key_" + slot, ""));
            boolean download = slot == DOWNLOAD_SLOT;
            boolean custom = SlotColors.isCustom(prefs, slot);
            int base = SlotColors.get(prefs, slot);
            String label = slotLabel(slot);
            int green = color(R.color.green);

            if (handledToday) {
                handled++;
                tileReady[index] = false;
                styleTileRaw(tile, label, "  ✓", green, color(R.color.navy_card),
                        custom ? base : color(R.color.divider),
                        custom ? base : color(R.color.text_secondary), 1f);
            } else if (isFound) {
                found++;
                String cachedPath = prefs.getString("search_result_cached_path_" + slot, "");
                boolean downloaded = !cachedPath.isEmpty() && new File(cachedPath).exists();
                if (downloaded && !download) ready++;
                tileReady[index] = downloaded;
                if (downloaded) {
                    styleTileRaw(tile, label, download ? "  ↓" : "  ↗", 0, base, 0, color(R.color.navy), 1f);
                } else {
                    styleTileRaw(tile, label, "  …", 0, color(R.color.navy_card), base, color(R.color.white), 1f);
                }
            } else {
                tileReady[index] = false;
                styleTileRaw(tile, label, "", 0, color(R.color.navy_card),
                        custom ? base : color(R.color.divider),
                        custom ? base : color(R.color.text_secondary), filter.isEmpty() ? 0.5f : 1f);
            }
        }

        String status = found + "/" + configured + " disponibili" + (handled > 0 ? " · " + handled + " inoltrati" : "");
        if (configured == 0) status = "Nessun PDF configurato";
        textAutoSearchSummary.setText(status);
        if (buttonShareAvailable != null) {
            buttonShareAvailable.setAlpha(ready > 0 ? 1f : 0.45f);
            buttonShareAvailable.setText(ready > 0 ? "Inoltra (" + ready + ")" : "Inoltra");
        }

        String channel = prefs.getString(KEY_CHANNEL_TITLE, "").trim();
        if (textSelectedChannel != null) {
            textSelectedChannel.setText(channel.isEmpty() ? "Nessun gruppo Telegram" : channel);
        }
        if (textScheduleDiagnostics != null) {
            long last = prefs.getLong("last_auto_search_at", 0L);
            String lastLabel = "mai";
            if (last > 0L) {
                boolean sameDay = new SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(new Date(last)).equals(today);
                lastLabel = new SimpleDateFormat(sameDay ? "HH:mm" : "dd/MM HH:mm", Locale.ITALY).format(new Date(last));
            }
            textScheduleDiagnostics.setText("Ultima ricerca " + lastLabel);
        }
    }

    private void saveSlotToDownloads(int slot) {
        String cachedPath = prefs.getString("search_result_cached_path_" + slot, "");
        String fileName = sanitizeFileName(prefs.getString("search_result_name_" + slot, "documento.pdf"));
        if (cachedPath.isEmpty() || !new File(cachedPath).exists()) {
            Toast.makeText(this, "Il PDF non è ancora pronto", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String where = copyToDownloads(new File(cachedPath), fileName);
            Toast.makeText(this, "Salvato in " + where + ": " + fileName, Toast.LENGTH_LONG).show();
            textStatus.setText("Scaricato: " + fileName);
            markSlotHandledAfterShare(slot, cachedPath);
        } catch (Exception e) {
            Toast.makeText(this, "Salvataggio non riuscito: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String copyToDownloads(File source, String fileName) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (target == null) throw new IOException("cartella Download non disponibile");
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = getContentResolver().openOutputStream(target)) {
                if (out == null) throw new IOException("scrittura non riuscita");
                copyStream(in, out);
            }
            return "Download";
        }
        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IOException("memoria non disponibile");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("cartella non creata");
        File target = new File(dir, fileName);
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copyStream(in, out);
        }
        return "Android/data/" + getPackageName() + "/files/Download";
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[16384];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        out.flush();
    }

    private void startDashboardSearch() {
        selectedChannelId = prefs.getLong(KEY_CHANNEL_ID, 0L);
        selectedChannelTitle = prefs.getString(KEY_CHANNEL_TITLE, "");
        if (selectedChannelId == 0L) {
            Toast.makeText(this, "Imposta prima il gruppo Telegram", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SearchesActivity.class));
            return;
        }
        int configured = 0;
        for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) {
            if (!prefs.getString("search_filter_" + slot, "").trim().isEmpty()) configured++;
        }
        if (configured == 0) {
            Toast.makeText(this, "Configura almeno un filtro PDF", Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SearchesActivity.class));
            return;
        }

        Intent service = new Intent(this, PdfSearchService.class).setAction(PdfSearchService.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        prefs.edit().putBoolean("auto_search_active", true).apply();
        Toast.makeText(this, "Ricerca avviata", Toast.LENGTH_SHORT).show();
        renderDashboard();
    }

    private void shareDashboardSlot(int slot, Button sourceButton) {
        int fileId = prefs.getInt("search_result_file_id_" + slot, 0);
        long messageId = prefs.getLong("search_result_message_id_" + slot, 0L);
        int timestamp = prefs.getInt("search_result_date_" + slot, 0);
        long size = prefs.getLong("search_result_size_" + slot, 0L);
        String fileName = prefs.getString("search_result_name_" + slot, "documento.pdf");
        String caption = prefs.getString("search_result_caption_" + slot, "");
        long channelId = prefs.getLong(KEY_CHANNEL_ID, 0L);
        if (fileId == 0 || messageId == 0L) {
            Toast.makeText(this, "Dati del PDF non disponibili", Toast.LENGTH_SHORT).show();
            return;
        }

        String cachedPath = prefs.getString("search_result_cached_path_" + slot, "");
        if (!cachedPath.isEmpty() && new File(cachedPath).exists()) {
            PdfItem item = new PdfItem(channelId, messageId, timestamp, fileId, size, fileName, caption);
            shareTelegramUri(item, buildCachedPdfUri(messageId, fileName));
            markSlotHandledAfterShare(slot, cachedPath);
            return;
        }

        if (!telegramReady || telegramClient == null) {
            Toast.makeText(this, "Il PDF non è ancora pronto. Attendi il download automatico.", Toast.LENGTH_LONG).show();
            if (hasStoredTelegramConfiguration()) {
                startTelegramClient(true);
            }
            return;
        }
        PdfItem item = new PdfItem(channelId, messageId, timestamp, fileId, size, fileName, caption);
        downloadAndSharePdf(item, sourceButton);
    }

    private Uri buildCachedPdfUri(long messageId, String fileName) {
        return new Uri.Builder()
                .scheme("content")
                .authority(PDF_PROVIDER_AUTHORITY)
                .appendPath(String.valueOf(messageId))
                .appendPath(sanitizeFileName(fileName))
                .build();
    }

    private void shareAvailablePdfs() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(new Date());
        ArrayList<Uri> uris = new ArrayList<>();
        ArrayList<Integer> sharedSlots = new ArrayList<>();
        ArrayList<String> sharedPaths = new ArrayList<>();
        for (int slot = 1; slot < DOWNLOAD_SLOT; slot++) {
            boolean found = prefs.getBoolean("search_found_" + slot, false)
                    && today.equals(prefs.getString("search_result_day_key_" + slot, ""));
            if (!found) continue;
            long messageId = prefs.getLong("search_result_message_id_" + slot, 0L);
            String fileName = prefs.getString("search_result_name_" + slot, "documento.pdf");
            String cachedPath = prefs.getString("search_result_cached_path_" + slot, "");
            if (messageId == 0L || cachedPath.isEmpty() || !new File(cachedPath).exists()) continue;
            uris.add(buildCachedPdfUri(messageId, fileName));
            sharedSlots.add(slot);
            sharedPaths.add(cachedPath);
        }

        if (uris.isEmpty()) {
            Toast.makeText(this, "Nessun PDF è ancora pronto da inoltrare", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent base;
        if (uris.size() == 1) {
            base = new Intent(Intent.ACTION_SEND);
            base.setType("application/pdf");
            base.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            base.setClipData(ClipData.newRawUri("PDF", uris.get(0)));
        } else {
            base = new Intent(Intent.ACTION_SEND_MULTIPLE);
            base.setType("application/pdf");
            base.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData clipData = ClipData.newRawUri("PDF 1", uris.get(0));
            for (int i = 1; i < uris.size(); i++) {
                clipData.addItem(new ClipData.Item(uris.get(i)));
            }
            base.setClipData(clipData);
        }
        base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (tryStartWhatsApp(base, "com.whatsapp") || tryStartWhatsApp(base, "com.whatsapp.w4b")) {
            markSlotsHandledAfterShare(sharedSlots, sharedPaths);
            textStatus.setText(uris.size() + " PDF aperti in WhatsApp");
            return;
        }
        try {
            startActivity(Intent.createChooser(new Intent(base), "Condividi PDF disponibili"));
            markSlotsHandledAfterShare(sharedSlots, sharedPaths);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nessuna app disponibile per condividere", Toast.LENGTH_LONG).show();
        }
    }

    private void markSlotHandledAfterShare(int slot, String cachedPath) {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(new Date());
        SharedPreferences.Editor editor = prefs.edit()
                .putString("search_handled_day_key_" + slot, today)
                .putBoolean("search_found_" + slot, false)
                .remove("search_result_name_" + slot)
                .remove("search_result_message_id_" + slot)
                .remove("search_result_file_id_" + slot)
                .remove("search_result_date_" + slot)
                .remove("search_result_size_" + slot)
                .remove("search_result_caption_" + slot)
                .remove("search_result_day_key_" + slot)
                .remove("search_result_notified_" + slot)
                .remove("search_result_cached_path_" + slot);
        editor.apply();
        scheduleCacheDelete(cachedPath);
        renderDashboard();
    }

    private void markSlotsHandledAfterShare(ArrayList<Integer> slots, ArrayList<String> paths) {
        for (int i = 0; i < slots.size(); i++) {
            String path = i < paths.size() ? paths.get(i) : "";
            markSlotHandledAfterShare(slots.get(i), path);
        }
    }

    private void scheduleCacheDelete(String path) {
        if (path == null || path.isEmpty()) return;
        mainHandler.postDelayed(() -> {
            try {
                File file = new File(path);
                if (file.exists()) file.delete();
                File parent = file.getParentFile();
                if (parent != null && parent.isDirectory()) parent.delete();
            } catch (Exception ignored) {}
        }, 120000L);
    }

    private void ensureDashboardTodayState() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(new Date());
        String stored = prefs.getString("search_day_key", "");
        if (today.equals(stored)) return;
        SharedPreferences.Editor editor = prefs.edit()
                .putString("search_day_key", today)
                .putBoolean("auto_search_active", false)
                .putLong("next_search_at", 0L);
        for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) {
            String cachedPath = prefs.getString("search_result_cached_path_" + slot, "");
            if (!cachedPath.isEmpty()) {
                try { new File(cachedPath).delete(); } catch (Exception ignored) {}
            }
            editor.putBoolean("search_found_" + slot, false)
                    .remove("search_result_name_" + slot)
                    .remove("search_result_message_id_" + slot)
                    .remove("search_result_file_id_" + slot)
                    .remove("search_result_date_" + slot)
                    .remove("search_result_size_" + slot)
                    .remove("search_result_caption_" + slot)
                    .remove("search_result_day_key_" + slot)
                    .remove("search_result_cached_path_" + slot)
                    .remove("search_result_notified_" + slot)
                    .remove("search_handled_day_key_" + slot);
        }
        editor.apply();
        deleteTree(new File(getCacheDir(), "shared_pdf"));
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        try { file.delete(); } catch (Exception ignored) {}
    }

    private void updateAutoSearchSummary() {
        renderDashboard();
    }

    private void restoreState() {
        editApiId.setText(prefs.getString(KEY_API_ID, ""));
        editApiHash.setText(prefs.getString(KEY_API_HASH, ""));
        editPhone.setText(prefs.getString(KEY_PHONE, ""));
        currentFilter = prefs.getString(KEY_FILTER, "").trim();
        editFilter.setText(currentFilter);

        selectedChannelId = prefs.getLong(KEY_CHANNEL_ID, 0L);
        selectedChannelTitle = prefs.getString(KEY_CHANNEL_TITLE, "");
        updateChannelUi();

        String uriString = prefs.getString(KEY_PDF_URI, null);
        selectedPdfName = prefs.getString(KEY_PDF_NAME, null);
        if (uriString != null) {
            selectedPdfUri = Uri.parse(uriString);
        }
        updateManualPdfUi();
        renderPdfList();
    }

    private boolean hasStoredTelegramConfiguration() {
        return !prefs.getString(KEY_API_ID, "").trim().isEmpty()
                && !prefs.getString(KEY_API_HASH, "").trim().isEmpty()
                && !prefs.getString(KEY_PHONE, "").trim().isEmpty();
    }

    private void openTelegramApiPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://my.telegram.org/apps")));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nessun browser disponibile", Toast.LENGTH_SHORT).show();
        }
    }

    private synchronized void startTelegramClient(boolean automatic) {
        if (telegramReady) {
            setTelegramStatus("● Telegram collegato", true);
            loadChannels(false);
            return;
        }
        if (telegramStarting) {
            return;
        }

        String apiIdText = editApiId.getText().toString().trim();
        String apiHash = editApiHash.getText().toString().trim();
        String phone = editPhone.getText().toString().trim().replace(" ", "");

        if (apiIdText.isEmpty() || apiHash.isEmpty() || phone.isEmpty()) {
            if (!automatic) {
                Toast.makeText(this, "Inserisci API ID, API Hash e numero Telegram", Toast.LENGTH_LONG).show();
            }
            return;
        }

        final int apiId;
        try {
            apiId = Integer.parseInt(apiIdText);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "API ID non valido", Toast.LENGTH_SHORT).show();
            return;
        }

        prefs.edit()
                .putString(KEY_API_ID, apiIdText)
                .putString(KEY_API_HASH, apiHash)
                .putString(KEY_PHONE, phone)
                .apply();

        telegramStarting = true;
        telegramReady = false;
        chats.clear();
        buttonSelectChannel.setEnabled(false);
        buttonSelectChannel.setAlpha(0.45f);
        buttonRefreshPdfs.setEnabled(false);
        buttonRefreshPdfs.setAlpha(0.45f);
        setTelegramStatus("Connessione a Telegram…", false);
        buttonConnectTelegram.setEnabled(false);

        try {
            try {
                Client.execute(new TdApi.SetLogVerbosityLevel(1));
            } catch (Exception ignored) {
            }

            telegramClient = Client.create(this::handleTdUpdate,
                    throwable -> showTelegramError("Errore aggiornamento: " + safeMessage(throwable)),
                    throwable -> showTelegramError("Errore TDLib: " + safeMessage(throwable)));

            File tdDir = new File(getFilesDir(), "tdlib");
            File tdFiles = new File(tdDir, "files");
            //noinspection ResultOfMethodCallIgnored
            tdDir.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            tdFiles.mkdirs();

            String language = Locale.getDefault().toLanguageTag();
            if (language == null || language.trim().isEmpty()) {
                language = "it";
            }

            TdApi.SetTdlibParameters parameters = new TdApi.SetTdlibParameters(
                    false,
                    tdDir.getAbsolutePath(),
                    tdFiles.getAbsolutePath(),
                    new byte[0],
                    true,
                    true,
                    true,
                    false,
                    apiId,
                    apiHash,
                    language,
                    Build.MODEL != null ? Build.MODEL : "Android",
                    Build.VERSION.RELEASE != null ? Build.VERSION.RELEASE : "Android",
                    "0.9"
            );

            telegramClient.send(parameters, object -> {
                if (object instanceof TdApi.Error) {
                    showTelegramError("Telegram: " + ((TdApi.Error) object).message);
                }
            });
        } catch (Throwable e) {
            telegramStarting = false;
            buttonConnectTelegram.setEnabled(true);
            showTelegramError("Impossibile avviare Telegram: " + safeMessage(e));
        }
    }

    private void handleTdUpdate(TdApi.Object object) {
        if (object instanceof TdApi.UpdateAuthorizationState) {
            handleAuthorizationState(((TdApi.UpdateAuthorizationState) object).authorizationState);
            return;
        }

        if (object instanceof TdApi.UpdateNewChat) {
            TdApi.Chat chat = ((TdApi.UpdateNewChat) object).chat;
            if (chat != null) {
                chats.put(chat.id, chat);
            }
            return;
        }

        if (object instanceof TdApi.UpdateChatTitle) {
            TdApi.UpdateChatTitle update = (TdApi.UpdateChatTitle) object;
            TdApi.Chat chat = chats.get(update.chatId);
            if (chat != null) {
                chat.title = update.title;
                chats.put(chat.id, chat);
                if (selectedChannelId == chat.id) {
                    selectedChannelTitle = chat.title;
                    prefs.edit().putString(KEY_CHANNEL_TITLE, selectedChannelTitle).apply();
                    runOnUiThread(this::updateChannelUi);
                }
            }
            return;
        }

        if (object instanceof TdApi.UpdateNewMessage) {
            TdApi.Message message = ((TdApi.UpdateNewMessage) object).message;
            if (message != null && message.chatId == selectedChannelId) {
                PdfItem item = pdfItemFromMessage(message);
                if (item != null && matchesCurrentFilter(item)) {
                    synchronized (pdfItems) {
                        boolean exists = false;
                        for (PdfItem old : pdfItems) {
                            if (old.messageId == item.messageId) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            pdfItems.add(item);
                            sortPdfItems();
                        }
                    }
                    runOnUiThread(() -> {
                        renderPdfList();
                        textPdfSearchInfo.setText("Nuovo PDF ricevuto dal canale");
                    });
                }
            }
            return;
        }

        if (object instanceof TdApi.UpdateFile) {
            TdApi.File file = ((TdApi.UpdateFile) object).file;
            if (file != null && file.local != null && file.local.isDownloadingCompleted) {
                PdfItem pending = pendingDownloads.remove(file.id);
                if (pending != null) {
                    shareDownloadedPdf(pending, file);
                }
            }
        }
    }

    private void handleAuthorizationState(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            telegramStarting = true;
            setTelegramStatus("Invio del codice Telegram…", false);
            String phone = prefs.getString(KEY_PHONE, "");
            if (telegramClient != null && !phone.isEmpty()) {
                telegramClient.send(new TdApi.SetAuthenticationPhoneNumber(phone, null), this::handleSimpleTelegramResult);
            }
            return;
        }

        if (state instanceof TdApi.AuthorizationStateWaitCode) {
            telegramStarting = false;
            runOnUiThread(() -> {
                editCode.setVisibility(View.VISIBLE);
                buttonConfirmCode.setVisibility(View.VISIBLE);
                editPassword.setVisibility(View.GONE);
                buttonConfirmPassword.setVisibility(View.GONE);
                buttonConnectTelegram.setEnabled(true);
                setTelegramStatus("Inserisci il codice ricevuto da Telegram", false);
                editCode.requestFocus();
            });
            return;
        }

        if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            telegramStarting = false;
            runOnUiThread(() -> {
                editCode.setVisibility(View.GONE);
                buttonConfirmCode.setVisibility(View.GONE);
                editPassword.setVisibility(View.VISIBLE);
                buttonConfirmPassword.setVisibility(View.VISIBLE);
                buttonConnectTelegram.setEnabled(true);
                setTelegramStatus("Account protetto: inserisci la password Telegram", false);
                editPassword.requestFocus();
            });
            return;
        }

        if (state instanceof TdApi.AuthorizationStateReady) {
            telegramReady = true;
            telegramStarting = false;
            runOnUiThread(() -> {
                editCode.setText("");
                editPassword.setText("");
                editCode.setVisibility(View.GONE);
                buttonConfirmCode.setVisibility(View.GONE);
                editPassword.setVisibility(View.GONE);
                buttonConfirmPassword.setVisibility(View.GONE);
                buttonConnectTelegram.setEnabled(true);
                buttonConnectTelegram.setText("Telegram collegato");
                buttonSelectChannel.setEnabled(true);
                buttonSelectChannel.setAlpha(1.0f);
                updatePdfSearchButtonState();
                setTelegramStatus("● Telegram collegato", true);
            });
            loadChannels(false);
            return;
        }

        if (state instanceof TdApi.AuthorizationStateLoggingOut
                || state instanceof TdApi.AuthorizationStateClosing
                || state instanceof TdApi.AuthorizationStateClosed) {
            telegramReady = false;
            telegramStarting = false;
            runOnUiThread(() -> {
                buttonConnectTelegram.setEnabled(true);
                buttonConnectTelegram.setText("Collega Telegram");
                buttonSelectChannel.setEnabled(false);
                buttonSelectChannel.setAlpha(0.45f);
                updatePdfSearchButtonState();
                setTelegramStatus("Telegram non collegato", false);
            });
            return;
        }

        String stateName = state == null ? "sconosciuto" : state.getClass().getSimpleName();
        if (stateName.contains("WaitEmail") || stateName.contains("WaitRegistration")) {
            telegramStarting = false;
            showTelegramError("Telegram richiede un passaggio aggiuntivo: " + stateName);
        }
    }

    private void handleSimpleTelegramResult(TdApi.Object object) {
        if (object instanceof TdApi.Error) {
            showTelegramError("Telegram: " + ((TdApi.Error) object).message);
        }
    }

    private void confirmTelegramCode() {
        String code = editCode.getText().toString().trim();
        if (code.isEmpty()) {
            Toast.makeText(this, "Inserisci il codice Telegram", Toast.LENGTH_SHORT).show();
            return;
        }
        if (telegramClient == null) {
            Toast.makeText(this, "Ricollega prima Telegram", Toast.LENGTH_SHORT).show();
            return;
        }
        buttonConfirmCode.setEnabled(false);
        setTelegramStatus("Verifica codice…", false);
        telegramClient.send(new TdApi.CheckAuthenticationCode(code), object -> {
            runOnUiThread(() -> buttonConfirmCode.setEnabled(true));
            handleSimpleTelegramResult(object);
        });
    }

    private void confirmTelegramPassword() {
        String password = editPassword.getText().toString();
        if (password.isEmpty()) {
            Toast.makeText(this, "Inserisci la password Telegram", Toast.LENGTH_SHORT).show();
            return;
        }
        if (telegramClient == null) {
            Toast.makeText(this, "Ricollega prima Telegram", Toast.LENGTH_SHORT).show();
            return;
        }
        buttonConfirmPassword.setEnabled(false);
        setTelegramStatus("Verifica password…", false);
        telegramClient.send(new TdApi.CheckAuthenticationPassword(password), object -> {
            runOnUiThread(() -> buttonConfirmPassword.setEnabled(true));
            handleSimpleTelegramResult(object);
        });
    }

    private void loadChannels(boolean showWhenReady) {
        Client client = telegramClient;
        if (!telegramReady || client == null) {
            if (showWhenReady) {
                Toast.makeText(this, "Collega prima Telegram", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        setTelegramStatus("Caricamento canali Telegram…", true);
        client.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 200), object -> {
            if (object instanceof TdApi.Error) {
                TdApi.Error error = (TdApi.Error) object;
                if (error.code != 404) {
                    showTelegramError("Caricamento canali: " + error.message);
                    return;
                }
            }
            runOnUiThread(() -> {
                setTelegramStatus("● Telegram collegato", true);
                if (showWhenReady) {
                    showChannelDialog();
                }
            });
        });
    }

    private void selectTelegramChannel() {
        if (!telegramReady) {
            Toast.makeText(this, "Collega prima Telegram", Toast.LENGTH_SHORT).show();
            return;
        }

        List<TdApi.Chat> available = getAvailableChannels();
        if (!available.isEmpty()) {
            showChannelDialog();
            return;
        }

        loadChannels(false);
        Toast.makeText(this, "Sto caricando i canali…", Toast.LENGTH_SHORT).show();
        mainHandler.postDelayed(this::showChannelDialog, 1000);
    }

    private List<TdApi.Chat> getAvailableChannels() {
        List<TdApi.Chat> result = new ArrayList<>();
        for (TdApi.Chat chat : chats.values()) {
            if (chat == null || chat.type == null) {
                continue;
            }
            if (chat.type instanceof TdApi.ChatTypeSupergroup) {
                TdApi.ChatTypeSupergroup type = (TdApi.ChatTypeSupergroup) chat.type;
                if (type.isChannel) {
                    result.add(chat);
                }
            }
        }
        Collections.sort(result, Comparator.comparing(
                chat -> chat.title == null ? "" : chat.title,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void showChannelDialog() {
        if (isFinishing()) {
            return;
        }
        List<TdApi.Chat> channels = getAvailableChannels();
        if (channels.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Canali Telegram")
                    .setMessage("Non ho ancora ricevuto l'elenco dei canali. Premi Riprova dopo qualche secondo.")
                    .setNegativeButton("Chiudi", null)
                    .setPositiveButton("Riprova", (dialog, which) -> {
                        loadChannels(false);
                        mainHandler.postDelayed(this::showChannelDialog, 900);
                    })
                    .show();
            return;
        }

        String[] names = new String[channels.size()];
        int checked = -1;
        for (int i = 0; i < channels.size(); i++) {
            TdApi.Chat chat = channels.get(i);
            names[i] = chat.title == null || chat.title.trim().isEmpty()
                    ? "Canale " + chat.id
                    : chat.title;
            if (chat.id == selectedChannelId) {
                checked = i;
            }
        }

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Seleziona canale Telegram")
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    TdApi.Chat selected = channels.get(which);
                    selectedChannelId = selected.id;
                    selectedChannelTitle = selected.title == null ? names[which] : selected.title;
                    prefs.edit()
                            .putLong(KEY_CHANNEL_ID, selectedChannelId)
                            .putString(KEY_CHANNEL_TITLE, selectedChannelTitle)
                            .apply();
                    synchronized (pdfItems) {
                        pdfItems.clear();
                    }
                    updateChannelUi();
                    renderPdfList();
                    updatePdfSearchButtonState();
                    textPdfSearchInfo.setText("Premi Cerca PDF per leggere il canale");
                    clearSearchResults();
                    updateAutoSearchSummary();
                    textStatus.setText("Canale Telegram selezionato");
                    d.dismiss();
                })
                .setNegativeButton("Annulla", null)
                .create();
        dialog.show();
    }

    private void clearSearchResults() {
        SharedPreferences.Editor editor = prefs.edit().putBoolean("auto_search_active", false);
        for (int i = 1; i <= MainActivity.SLOT_COUNT; i++) {
            editor.remove("search_found_" + i)
                    .remove("search_result_name_" + i)
                    .remove("search_result_message_id_" + i)
                    .remove("search_result_file_id_" + i)
                    .remove("search_result_date_" + i)
                    .remove("search_result_size_" + i)
                    .remove("search_result_caption_" + i)
                    .remove("search_result_day_key_" + i)
                    .remove("search_result_cached_path_" + i)
                    .remove("search_result_notified_" + i);
        }
        editor.apply();
        stopService(new Intent(this, PdfSearchService.class));
    }

    static Client getSharedTelegramClient() {
        return telegramClient;
    }

    static boolean isSharedTelegramReady() {
        return telegramReady;
    }

    static void setSharedTelegramState(Client client, boolean ready, boolean starting) {
        telegramClient = client;
        telegramReady = ready;
        telegramStarting = starting;
    }

    private void updateChannelUi() {
        if (selectedChannelId == 0L || selectedChannelTitle == null || selectedChannelTitle.trim().isEmpty()) {
            textSelectedChannel.setText("Nessun canale selezionato");
        } else {
            textSelectedChannel.setText(selectedChannelTitle);
        }
        if (buttonRefreshPdfs != null) {
            updatePdfSearchButtonState();
        }
    }

    private void updatePdfSearchButtonState() {
        if (buttonRefreshPdfs == null) {
            return;
        }
        boolean enabled = telegramReady && selectedChannelId != 0L && !pdfSearchRunning;
        buttonRefreshPdfs.setEnabled(enabled);
        buttonRefreshPdfs.setAlpha(enabled ? 1.0f : 0.45f);
    }

    private void setTelegramStatus(String message, boolean connected) {
        runOnUiThread(() -> {
            textTelegramStatus.setText(message);
            textTelegramStatus.setTextColor(getResources().getColor(
                    connected ? R.color.green : R.color.text_secondary,
                    getTheme()));
        });
    }

    private void showTelegramError(String message) {
        telegramStarting = false;
        runOnUiThread(() -> {
            buttonConnectTelegram.setEnabled(true);
            textTelegramStatus.setText(message);
            textTelegramStatus.setTextColor(getResources().getColor(R.color.warning, getTheme()));
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "errore sconosciuto";
        }
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private void saveSettings() {
        currentFilter = editFilter.getText().toString().trim();
        prefs.edit()
                .putString(KEY_FILTER, currentFilter)
                .apply();
        textStatus.setText("Filtro salvato sul telefono");
        Toast.makeText(this, "Filtro salvato", Toast.LENGTH_SHORT).show();
    }

    // -------------------- FASE 3: PDF DAL CANALE TELEGRAM --------------------

    private void searchPdfs() {
        if (!telegramReady || telegramClient == null) {
            Toast.makeText(this, "Collega prima Telegram", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedChannelId == 0L) {
            Toast.makeText(this, "Seleziona prima un canale", Toast.LENGTH_SHORT).show();
            return;
        }
        if (pdfSearchRunning) {
            return;
        }

        saveFilterSilently();
        pdfSearchRunning = true;
        synchronized (pdfItems) {
            pdfItems.clear();
        }
        runOnUiThread(() -> {
            updatePdfSearchButtonState();
            buttonRefreshPdfs.setText("Ricerca in corso…");
            textPdfSearchInfo.setText("Cerco i documenti nel canale…");
            pdfListContainer.removeAllViews();
        });

        searchDocumentPage(0L, 0, 0);
    }

    private void searchDocumentPage(long fromMessageId, int scannedDocuments, int matchedDocuments) {
        Client client = telegramClient;
        if (client == null || !telegramReady) {
            finishPdfSearchWithError("Telegram non è più collegato");
            return;
        }

        TdApi.SearchChatMessages request = new TdApi.SearchChatMessages();
        request.chatId = selectedChannelId;
        request.query = "";
        request.fromMessageId = fromMessageId;
        request.offset = 0;
        request.limit = 100;
        request.filter = new TdApi.SearchMessagesFilterDocument();

        client.send(request, object -> {
            if (object instanceof TdApi.Error) {
                finishPdfSearchWithError("Ricerca PDF: " + ((TdApi.Error) object).message);
                return;
            }
            if (!(object instanceof TdApi.FoundChatMessages)) {
                finishPdfSearchWithError("Risposta Telegram non riconosciuta");
                return;
            }

            TdApi.FoundChatMessages found = (TdApi.FoundChatMessages) object;
            int scannedNow = scannedDocuments;
            int matchedNow = matchedDocuments;

            if (found.messages != null) {
                for (TdApi.Message message : found.messages) {
                    scannedNow++;
                    PdfItem item = pdfItemFromMessage(message);
                    if (item != null && matchesCurrentFilter(item)) {
                        synchronized (pdfItems) {
                            pdfItems.add(item);
                        }
                        matchedNow++;
                    }
                }
            }

            final int displayScanned = scannedNow;
            final int displayMatched = matchedNow;
            runOnUiThread(() -> textPdfSearchInfo.setText(
                    "Documenti controllati: " + displayScanned + "  •  PDF trovati: " + displayMatched));

            boolean noMore = found.messages == null || found.messages.length == 0 || found.nextFromMessageId == 0L;
            boolean safetyLimit = scannedNow >= 500;
            if (noMore || safetyLimit) {
                finishPdfSearch(scannedNow, matchedNow, safetyLimit);
            } else {
                searchDocumentPage(found.nextFromMessageId, scannedNow, matchedNow);
            }
        });
    }

    private PdfItem pdfItemFromMessage(TdApi.Message message) {
        if (message == null || !(message.content instanceof TdApi.MessageDocument)) {
            return null;
        }
        TdApi.MessageDocument content = (TdApi.MessageDocument) message.content;
        if (content.document == null || content.document.document == null) {
            return null;
        }

        String fileName = content.document.fileName == null ? "documento.pdf" : content.document.fileName.trim();
        String mimeType = content.document.mimeType == null ? "" : content.document.mimeType.trim();
        boolean isPdf = "application/pdf".equalsIgnoreCase(mimeType)
                || fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        if (!isPdf) {
            return null;
        }

        String caption = "";
        if (content.caption != null && content.caption.text != null) {
            caption = content.caption.text.trim();
        }

        return new PdfItem(
                message.chatId,
                message.id,
                message.date,
                content.document.document.id,
                content.document.document.size,
                fileName.isEmpty() ? "documento.pdf" : fileName,
                caption
        );
    }

    private boolean matchesCurrentFilter(PdfItem item) {
        String filter = currentFilter.toLowerCase(Locale.ROOT);
        if (filter.isEmpty()) {
            return true;
        }
        String haystack = (item.fileName + " " + item.caption).toLowerCase(Locale.ROOT);
        String[] words = filter.split("\\s+");
        for (String word : words) {
            if (!word.isEmpty() && !haystack.contains(word)) {
                return false;
            }
        }
        return true;
    }

    private void finishPdfSearch(int scanned, int matched, boolean limited) {
        pdfSearchRunning = false;
        sortPdfItems();
        runOnUiThread(() -> {
            buttonRefreshPdfs.setText("Aggiorna PDF");
            updatePdfSearchButtonState();
            renderPdfList();
            if (matched == 0) {
                textPdfSearchInfo.setText("Nessun PDF trovato" + (editFilter.getText().toString().trim().isEmpty()
                        ? ""
                        : " con il filtro impostato"));
            } else {
                String suffix = limited ? " • ricerca limitata agli ultimi 500 documenti" : "";
                textPdfSearchInfo.setText("PDF trovati: " + matched + " • documenti controllati: " + scanned + suffix);
            }
            textStatus.setText("Ricerca Telegram completata");
        });
    }

    private void finishPdfSearchWithError(String message) {
        pdfSearchRunning = false;
        runOnUiThread(() -> {
            buttonRefreshPdfs.setText("Cerca PDF");
            updatePdfSearchButtonState();
            textPdfSearchInfo.setText(message);
            textStatus.setText("Errore durante la ricerca");
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    private void sortPdfItems() {
        synchronized (pdfItems) {
            Collections.sort(pdfItems, (a, b) -> {
                int byDate = Integer.compare(b.date, a.date);
                return byDate != 0 ? byDate : Long.compare(b.messageId, a.messageId);
            });
        }
    }

    private void renderPdfList() {
        if (pdfListContainer == null) {
            return;
        }
        pdfListContainer.removeAllViews();

        List<PdfItem> snapshot;
        synchronized (pdfItems) {
            snapshot = new ArrayList<>(pdfItems);
        }

        if (snapshot.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("I PDF trovati compariranno qui.");
            empty.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            empty.setTextSize(14f);
            empty.setPadding(0, dp(8), 0, dp(2));
            pdfListContainer.addView(empty);
            return;
        }

        Set<String> sharedIds = getSharedIds();
        for (PdfItem item : snapshot) {
            pdfListContainer.addView(createPdfRow(item, sharedIds.contains(sharedKey(item))));
        }
    }

    private View createPdfRow(PdfItem item, boolean alreadyShared) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundResource(R.drawable.bg_pdf_item);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dp(8), 0, 0);
        card.setLayoutParams(cardParams);

        TextView name = new TextView(this);
        name.setText(item.fileName);
        name.setTextColor(getResources().getColor(R.color.white, getTheme()));
        name.setTextSize(16f);
        name.setTypeface(null, Typeface.BOLD);
        card.addView(name);

        TextView meta = new TextView(this);
        String metaText = dateFormat.format(new Date(item.date * 1000L)) + "  •  " + formatSize(item.size);
        if (alreadyShared) {
            metaText += "  •  GIÀ CONDIVISO";
        }
        meta.setText(metaText);
        meta.setTextColor(getResources().getColor(alreadyShared ? R.color.green : R.color.text_secondary, getTheme()));
        meta.setTextSize(12.5f);
        meta.setPadding(0, dp(4), 0, 0);
        card.addView(meta);

        if (!item.caption.isEmpty()) {
            TextView caption = new TextView(this);
            caption.setText(item.caption);
            caption.setMaxLines(3);
            caption.setTextColor(getResources().getColor(R.color.text_secondary, getTheme()));
            caption.setTextSize(13f);
            caption.setPadding(0, dp(7), 0, 0);
            card.addView(caption);
        }

        Button share = new Button(this);
        share.setText(alreadyShared ? "Condividi di nuovo su WhatsApp" : "Condividi su WhatsApp");
        share.setAllCaps(false);
        share.setTextSize(14f);
        share.setTypeface(null, Typeface.BOLD);
        share.setTextColor(getResources().getColor(R.color.navy, getTheme()));
        share.setBackgroundResource(R.drawable.bg_button_primary);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        buttonParams.setMargins(0, dp(10), 0, 0);
        share.setLayoutParams(buttonParams);
        share.setOnClickListener(v -> downloadAndSharePdf(item, share));
        card.addView(share);

        return card;
    }

    private void downloadAndSharePdf(PdfItem item, Button sourceButton) {
        Client client = telegramClient;
        if (!telegramReady || client == null) {
            Toast.makeText(this, "Telegram non è collegato", Toast.LENGTH_SHORT).show();
            return;
        }

        sourceButton.setEnabled(false);
        sourceButton.setText("Scarico il PDF…");
        textStatus.setText("Download da Telegram: " + item.fileName);
        pendingDownloads.put(item.fileId, item);

        TdApi.DownloadFile request = new TdApi.DownloadFile(item.fileId, 32, 0, 0, true);
        client.send(request, object -> {
            runOnUiThread(() -> sourceButton.setEnabled(true));
            if (object instanceof TdApi.Error) {
                pendingDownloads.remove(item.fileId);
                String error = ((TdApi.Error) object).message;
                runOnUiThread(() -> {
                    sourceButton.setText("Riprova con WhatsApp");
                    textStatus.setText("Errore download: " + error);
                    Toast.makeText(this, "Download Telegram: " + error, Toast.LENGTH_LONG).show();
                });
                return;
            }
            if (object instanceof TdApi.File) {
                TdApi.File file = (TdApi.File) object;
                if (file.local != null && file.local.isDownloadingCompleted) {
                    PdfItem pending = pendingDownloads.remove(item.fileId);
                    if (pending != null) {
                        runOnUiThread(() -> sourceButton.setText("Inoltra"));
                        shareDownloadedPdf(pending, file);
                    }
                }
            }
            runOnUiThread(() -> {
                if (pendingDownloads.containsKey(item.fileId)) {
                    sourceButton.setText("Download in corso…");
                }
            });
        });
    }

    private void shareDownloadedPdf(PdfItem item, TdApi.File tdFile) {
        if (tdFile.local == null || tdFile.local.path == null || tdFile.local.path.trim().isEmpty()) {
            runOnUiThread(() -> Toast.makeText(this, "Telegram non ha restituito il file locale", Toast.LENGTH_LONG).show());
            return;
        }

        try {
            File source = new File(tdFile.local.path);
            if (!source.exists() || source.length() == 0) {
                throw new IOException("File scaricato non trovato");
            }

            String safeName = sanitizeFileName(item.fileName);
            File folder = new File(getCacheDir(), "shared_pdf/" + item.messageId);
            if (!folder.exists() && !folder.mkdirs()) {
                throw new IOException("Impossibile preparare la cartella di condivisione");
            }
            File destination = new File(folder, safeName);
            copyFile(source, destination);
            StorageCleanup.releaseTdlibDownload(this, telegramClient, item.fileId, tdFile.local.path);

            Uri shareUri = new Uri.Builder()
                    .scheme("content")
                    .authority(PDF_PROVIDER_AUTHORITY)
                    .appendPath(String.valueOf(item.messageId))
                    .appendPath(safeName)
                    .build();

            runOnUiThread(() -> shareTelegramUri(item, shareUri));
        } catch (Exception e) {
            runOnUiThread(() -> {
                textStatus.setText("Errore preparando il PDF");
                Toast.makeText(this, safeMessage(e), Toast.LENGTH_LONG).show();
            });
        }
    }

    private void shareTelegramUri(PdfItem item, Uri uri) {
        Intent base = createShareIntent(uri, item.fileName);
        boolean opened = tryStartWhatsApp(base, "com.whatsapp");
        String target = "WhatsApp";
        if (!opened) {
            opened = tryStartWhatsApp(base, "com.whatsapp.w4b");
            target = "WhatsApp Business";
        }
        if (!opened) {
            try {
                startActivity(Intent.createChooser(createShareIntent(uri, item.fileName), "Condividi PDF"));
                opened = true;
                target = "menu condivisione";
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Nessuna app disponibile per condividere il PDF", Toast.LENGTH_LONG).show();
            }
        }

        if (opened) {
            markShared(item);
            textStatus.setText("PDF aperto in " + target);
            renderPdfList();
        }
    }

    private Intent createShareIntent(Uri uri, String title) {
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_TITLE, title);
        intent.setClipData(ClipData.newRawUri("PDF", uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private String sanitizeFileName(String input) {
        String value = input == null ? "documento.pdf" : input.trim();
        value = value.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (value.isEmpty()) {
            value = "documento.pdf";
        }
        if (!value.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            value += ".pdf";
        }
        return value;
    }

    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
            }
            out.flush();
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format(Locale.ITALY, "%.1f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format(Locale.ITALY, "%.1f MB", mb);
        }
        return String.format(Locale.ITALY, "%.2f GB", mb / 1024.0);
    }

    private void saveFilterSilently() {
        currentFilter = editFilter.getText().toString().trim();
        prefs.edit().putString(KEY_FILTER, currentFilter).apply();
    }

    private Set<String> getSharedIds() {
        return new HashSet<>(prefs.getStringSet(KEY_SHARED_MESSAGE_IDS, Collections.emptySet()));
    }

    private String sharedKey(PdfItem item) {
        return item.chatId + ":" + item.messageId;
    }

    private void markShared(PdfItem item) {
        Set<String> values = getSharedIds();
        values.add(sharedKey(item));
        prefs.edit().putStringSet(KEY_SHARED_MESSAGE_IDS, values).apply();
    }

    private void clearSharedHistory() {
        new AlertDialog.Builder(this)
                .setTitle("Azzera storico")
                .setMessage("Vuoi togliere l'indicazione “Già condiviso” da tutti i PDF?")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Azzera", (dialog, which) -> {
                    prefs.edit().remove(KEY_SHARED_MESSAGE_IDS).apply();
                    renderPdfList();
                    textStatus.setText("Storico condivisioni azzerato");
                })
                .show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // -------------------- PROVA MANUALE PDF --------------------

    private void pickPdf() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        startActivityForResult(intent, REQUEST_OPEN_PDF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode != REQUEST_OPEN_PDF || resultCode != RESULT_OK || data == null) {
            return;
        }

        Uri uri = data.getData();
        if (uri == null) {
            return;
        }

        selectedPdfUri = uri;
        selectedPdfName = getDisplayName(uri);

        int flags = data.getFlags() &
                (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            getContentResolver().takePersistableUriPermission(uri, flags);
        } catch (SecurityException ignored) {
        }

        prefs.edit()
                .putString(KEY_PDF_URI, uri.toString())
                .putString(KEY_PDF_NAME, selectedPdfName)
                .apply();

        updateManualPdfUi();
        textStatus.setText("PDF manuale pronto per WhatsApp");
    }

    private String getDisplayName(Uri uri) {
        String result = null;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    result = cursor.getString(index);
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return result != null ? result : "documento.pdf";
    }

    private void updateManualPdfUi() {
        if (selectedPdfUri == null) {
            textSelectedPdf.setText("Nessun PDF selezionato");
            buttonShare.setEnabled(false);
            buttonShare.setAlpha(0.45f);
            return;
        }

        String name = selectedPdfName != null ? selectedPdfName : "documento.pdf";
        textSelectedPdf.setText("PDF selezionato: " + name);
        buttonShare.setEnabled(true);
        buttonShare.setAlpha(1.0f);
    }

    private void shareManualPdf() {
        if (selectedPdfUri == null) {
            Toast.makeText(this, "Seleziona prima un PDF", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent shareIntent = createShareIntent(selectedPdfUri,
                selectedPdfName == null ? "documento.pdf" : selectedPdfName);

        if (tryStartWhatsApp(shareIntent, "com.whatsapp")) {
            textStatus.setText("PDF aperto in WhatsApp");
            return;
        }

        if (tryStartWhatsApp(shareIntent, "com.whatsapp.w4b")) {
            textStatus.setText("PDF aperto in WhatsApp Business");
            return;
        }

        try {
            startActivity(Intent.createChooser(createShareIntent(selectedPdfUri, selectedPdfName), "Condividi PDF"));
            textStatus.setText("WhatsApp non trovato: aperto menu condivisione");
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nessuna app disponibile per condividere il PDF", Toast.LENGTH_LONG).show();
        }
    }

    private boolean tryStartWhatsApp(Intent baseIntent, String packageName) {
        Intent intent = new Intent(baseIntent);
        intent.setPackage(packageName);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Il client TDLib resta condiviso con il servizio di ricerca automatica.
    }

    private static final class PdfItem {
        final long chatId;
        final long messageId;
        final int date;
        final int fileId;
        final long size;
        final String fileName;
        final String caption;

        PdfItem(long chatId, long messageId, int date, int fileId, long size, String fileName, String caption) {
            this.chatId = chatId;
            this.messageId = messageId;
            this.date = date;
            this.fileId = fileId;
            this.size = size;
            this.fileName = fileName;
            this.caption = caption;
        }
    }
}
