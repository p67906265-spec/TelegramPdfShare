package it.paolo.telegrampdfshare;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SearchesActivity extends Activity {

    private static final String PREFS = "telegram_pdf_share_prefs";
    private static final String PDF_PROVIDER_AUTHORITY = "it.paolo.telegrampdfshare.pdfprovider";
    private static final int REQUEST_NOTIFICATIONS = 2201;

    private final EditText[] filterEdits = new EditText[MainActivity.SLOT_COUNT];
    private final TextView[] statusTexts = new TextView[MainActivity.SLOT_COUNT];
    private final TextView[] resultTexts = new TextView[MainActivity.SLOT_COUNT];
    private final Button[] shareButtons = new Button[MainActivity.SLOT_COUNT];

    private SharedPreferences prefs;
    private TextView textGlobalSearchStatus;
    private TextView textNextSearch;
    private TextView textSearchDate;
    private TextView textStartTime;
    private TextView textEndTime;
    private EditText editGlobalExclude;
    private EditText editRetryMinutes;
    private TextView textSearchChannel;
    private CheckBox checkControlMessages;
    private Button buttonAutoSearch;
    private Button buttonShareAll;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY);
    private final SimpleDateFormat dayLabelFormat = new SimpleDateFormat("dd/MM/yyyy", Locale.ITALY);
    private final SimpleDateFormat dayKeyFormat = new SimpleDateFormat("yyyyMMdd", Locale.ITALY);

    private final BroadcastReceiver searchReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            refreshAll();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            refreshHeaderOnly();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_searches);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        ensureTodayState();

        textGlobalSearchStatus = findViewById(R.id.textGlobalSearchStatus);
        textNextSearch = findViewById(R.id.textNextSearch);
        textSearchDate = findViewById(R.id.textSearchDate);
        textStartTime = findViewById(R.id.buttonPickStartTime);
        textEndTime = findViewById(R.id.buttonPickEndTime);
        editGlobalExclude = findViewById(R.id.editGlobalExclude);
        editRetryMinutes = findViewById(R.id.editRetryMinutes);
        textSearchChannel = findViewById(R.id.textSearchChannel);
        checkControlMessages = findViewById(R.id.checkControlMessages);
        buttonAutoSearch = findViewById(R.id.buttonAutoSearch);
        buttonShareAll = findViewById(R.id.buttonShareAll);
        Button buttonSearchNow = findViewById(R.id.buttonSearchNow);
        Button buttonNewSearch = findViewById(R.id.buttonNewSearch);
        Button buttonPickStartTime = findViewById(R.id.buttonPickStartTime);
        Button buttonPickEndTime = findViewById(R.id.buttonPickEndTime);
        Button buttonBackMain = findViewById(R.id.buttonBackMain);
        Button buttonSaveSettings = findViewById(R.id.buttonSaveSettings);
        Button buttonSelectTelegramGroup = findViewById(R.id.buttonSelectTelegramGroup);
        LinearLayout container = findViewById(R.id.searchPanelsContainer);

        String channelTitle = prefs.getString("channel_title", "").trim();
        textSearchChannel.setText(channelTitle.isEmpty() ? "Nessun canale selezionato" : channelTitle);
        checkControlMessages.setChecked(prefs.getBoolean("control_messages_enabled", false));
        textSearchDate.setText(todayLabel());

        LinearLayout panelRow = null;
        for (int i = 0; i < MainActivity.SLOT_COUNT; i++) {
            if (i % 2 == 0) {
                panelRow = new LinearLayout(this);
                panelRow.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(0, dp(8), 0, 0);
                panelRow.setLayoutParams(rowParams);
                container.addView(panelRow);
            }
            panelRow.addView(createSearchPanel(i));
        }
        restoreConfig();
        refreshAll();

        buttonAutoSearch.setOnClickListener(v -> toggleAutoSearch());
        buttonSearchNow.setOnClickListener(v -> searchNow());
        buttonNewSearch.setOnClickListener(v -> confirmNewSearch());
        buttonPickStartTime.setOnClickListener(v -> showStartTimePicker());
        buttonPickEndTime.setOnClickListener(v -> showEndTimePicker());
        buttonShareAll.setOnClickListener(v -> downloadAndShareAll());
        buttonSelectTelegramGroup.setOnClickListener(v -> selectTelegramGroup());
        buttonSaveSettings.setOnClickListener(v -> {
            saveConfig(true);
            refreshAll();
            Toast.makeText(this, "Impostazioni salvate", Toast.LENGTH_SHORT).show();
        });
        buttonBackMain.setOnClickListener(v -> finish());
    }

    @Override
    protected void onResume() {
        super.onResume();
        ensureTodayState();
        completePendingScheduleEnableIfPossible();
        IntentFilter filter = new IntentFilter(PdfSearchService.ACTION_UPDATED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(searchReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(searchReceiver, filter);
        }
        handler.post(ticker);
        if (textSearchDate != null) {
            textSearchDate.setText(todayLabel());
        }
        if (textSearchChannel != null) {
            String channelTitle = prefs.getString("channel_title", "").trim();
            textSearchChannel.setText(channelTitle.isEmpty() ? "Nessun gruppo selezionato" : channelTitle);
        }
        if (checkControlMessages != null) checkControlMessages.setChecked(prefs.getBoolean("control_messages_enabled", false));
        refreshAll();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveConfig(true);
        handler.removeCallbacks(ticker);
        try {
            unregisterReceiver(searchReceiver);
        } catch (Exception ignored) {
        }
    }

    private View createSearchPanel(int index) {
        int slot = index + 1;
        boolean left = index % 2 == 0;
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        cardParams.setMargins(left ? 0 : dp(4), 0, left ? dp(4) : 0, 0);
        card.setLayoutParams(cardParams);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(slot == MainActivity.DOWNLOAD_SLOT ? "Scarica" : "Ricerca " + slot);
        title.setTextColor(getColor(R.color.cyan));
        title.setTextSize(12f);
        title.setTypeface(null, Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final View colorDot = new View(this);
        colorDot.setBackground(SlotColors.dot(SlotColors.get(prefs, slot), dp(18)));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(18), dp(18));
        dotParams.setMargins(dp(6), 0, dp(2), 0);
        colorDot.setLayoutParams(dotParams);
        colorDot.setOnClickListener(v -> SlotColors.showPicker(this, prefs, slot,
                "Colore di " + MainActivity.slotLabel(slot),
                () -> colorDot.setBackground(SlotColors.dot(SlotColors.get(prefs, slot), dp(18)))));
        header.addView(colorDot);
        card.addView(header);

        EditText filter = new EditText(this);
        filter.setHint(slot == MainActivity.DOWNLOAD_SLOT ? "Parole del PDF da scaricare" : "Parole del PDF");
        filter.setSingleLine(true);
        filter.setTextColor(getColor(R.color.white));
        filter.setHintTextColor(getColor(R.color.text_secondary));
        filter.setTextSize(13f);
        filter.setMinHeight(0);
        filter.setBackgroundResource(R.drawable.bg_input);
        filter.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(38));
        filterParams.setMargins(0, dp(4), 0, 0);
        filter.setLayoutParams(filterParams);
        card.addView(filter);
        filterEdits[index] = filter;

        TextView state = new TextView(this);
        state.setTextColor(getColor(R.color.warning));
        state.setVisibility(View.GONE);
        card.addView(state);
        statusTexts[index] = state;

        TextView result = new TextView(this);
        result.setTextColor(getColor(R.color.text_secondary));
        result.setVisibility(View.GONE);
        card.addView(result);
        resultTexts[index] = result;

        Button share = new Button(this);
        share.setEnabled(false);
        share.setVisibility(View.GONE);
        final int slotIndex = index;
        share.setOnClickListener(v -> downloadAndShare(slotIndex, share));
        card.addView(share);
        shareButtons[index] = share;

        return card;
    }

    private void restoreConfig() {
        for (int i = 0; i < MainActivity.SLOT_COUNT; i++) {
            int slot = i + 1;
            filterEdits[i].setText(prefs.getString("search_filter_" + slot, ""));
        }
        editGlobalExclude.setText(prefs.getString("global_exclude_filter", ""));
        editRetryMinutes.setText(String.valueOf(prefs.getInt("retry_minutes", 15)));
        refreshStartTimeLabel();
    }

    private void saveConfig(boolean clearChangedResults) {
        ensureTodayState();
        SharedPreferences.Editor editor = prefs.edit();
        boolean clearAll = false;

        String exclude = editGlobalExclude == null ? "" : editGlobalExclude.getText().toString().trim();
        String oldExclude = prefs.getString("global_exclude_filter", "").trim();
        editor.putString("global_exclude_filter", exclude);
        if (clearChangedResults && !oldExclude.equals(exclude)) {
            clearAll = true;
        }

        int retryMinutes = 15;
        try {
            retryMinutes = Integer.parseInt(editRetryMinutes.getText().toString().trim());
        } catch (Exception ignored) {
        }
        retryMinutes = Math.max(1, Math.min(180, retryMinutes));
        editor.putInt("retry_minutes", retryMinutes);
        editRetryMinutes.setText(String.valueOf(retryMinutes));
        editor.remove("whatsapp_group_name");
        if (checkControlMessages != null) editor.putBoolean("control_messages_enabled", checkControlMessages.isChecked());

        for (int i = 0; i < MainActivity.SLOT_COUNT; i++) {
            int slot = i + 1;
            String filter = filterEdits[i].getText().toString().trim();
            String oldFilter = prefs.getString("search_filter_" + slot, "").trim();
            editor.putString("search_filter_" + slot, filter);
            if (clearChangedResults && !oldFilter.equals(filter)) {
                clearResult(editor, slot);
            }
        }
        if (clearAll) {
            for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) {
                clearResult(editor, slot);
            }
        }
        editor.putString("search_day_key", todayKey());
        editor.apply();
    }

    private boolean hasAnyFilterConfigured() {
        for (EditText edit : filterEdits) {
            if (!edit.getText().toString().trim().isEmpty()) return true;
        }
        return false;
    }


    private void selectTelegramGroup() {
        Client client = MainActivity.getSharedTelegramClient();
        if (!MainActivity.isSharedTelegramReady() || client == null) {
            Toast.makeText(this, "Telegram si sta collegando. Attendi qualche secondo e riprova.", Toast.LENGTH_LONG).show();
            return;
        }
        List<TdApi.Chat> channels = getAvailableTelegramChannels();
        if (!channels.isEmpty()) {
            showTelegramGroupDialog(channels);
            return;
        }
        Toast.makeText(this, "Carico i gruppi/canali Telegram…", Toast.LENGTH_SHORT).show();
        client.send(new TdApi.LoadChats(new TdApi.ChatListMain(), 200), object ->
                runOnUiThread(() -> handler.postDelayed(() -> {
                    List<TdApi.Chat> loaded = getAvailableTelegramChannels();
                    if (loaded.isEmpty()) {
                        Toast.makeText(this, "Nessun gruppo/canale Telegram disponibile", Toast.LENGTH_LONG).show();
                    } else {
                        showTelegramGroupDialog(loaded);
                    }
                }, 700L)));
    }

    private List<TdApi.Chat> getAvailableTelegramChannels() {
        List<TdApi.Chat> result = new ArrayList<>();
        for (TdApi.Chat chat : MainActivity.chats.values()) {
            if (chat == null || chat.type == null) continue;
            if (chat.type instanceof TdApi.ChatTypeSupergroup) result.add(chat);
        }
        Collections.sort(result, Comparator.comparing(
                chat -> chat.title == null ? "" : chat.title,
                String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private void showTelegramGroupDialog(List<TdApi.Chat> channels) {
        String[] names = new String[channels.size()];
        long selectedId = prefs.getLong("channel_id", 0L);
        int checked = -1;
        for (int i = 0; i < channels.size(); i++) {
            TdApi.Chat chat = channels.get(i);
            names[i] = chat.title == null || chat.title.trim().isEmpty() ? "Chat " + chat.id : chat.title;
            if (chat.id == selectedId) checked = i;
        }
        new AlertDialog.Builder(this)
                .setTitle("Gruppo Telegram PDF")
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    TdApi.Chat selected = channels.get(which);
                    String title = selected.title == null || selected.title.trim().isEmpty() ? names[which] : selected.title;
                    stopService(new Intent(this, PdfSearchService.class));
                    SharedPreferences.Editor editor = prefs.edit()
                            .putLong("channel_id", selected.id)
                            .putString("channel_title", title)
                            .putBoolean("auto_search_active", false)
                            .putLong("next_search_at", 0L)
                            .putString("search_day_key", todayKey());
                    for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) clearResult(editor, slot);
                    editor.apply();
                    textSearchChannel.setText(title);
                    if (prefs.getBoolean("daily_search_enabled", false)) SearchScheduler.scheduleNext(this);
                    Toast.makeText(this, "Gruppo Telegram aggiornato", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .setNegativeButton("Annulla", null)
                .show();
    }

    private void toggleAutoSearch() {
        boolean dailyEnabled = prefs.getBoolean("daily_search_enabled", false);
        if (dailyEnabled) {
            prefs.edit()
                    .putBoolean("daily_search_enabled", false)
                    .putBoolean("pending_enable_schedule", false)
                    .putBoolean("auto_search_active", false)
                    .putLong("next_search_at", 0L)
                    .apply();
            SearchScheduler.cancel(this);
            stopService(new Intent(this, PdfSearchService.class));
            refreshAll();
            Toast.makeText(this, "Avvio programmato disattivato", Toast.LENGTH_SHORT).show();
            return;
        }

        saveConfig(true);
        if (!hasAnyFilterConfigured()) {
            Toast.makeText(this, "Configura almeno un filtro PDF", Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.getLong("channel_id", 0L) == 0L) {
            Toast.makeText(this, "Seleziona prima un canale Telegram", Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.getInt("search_start_hour", -1) < 0 || prefs.getInt("search_start_minute", -1) < 0) {
            Toast.makeText(this, "Imposta prima l'ora di inizio", Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.getInt("search_end_hour", -1) < 0 || prefs.getInt("search_end_minute", -1) < 0) {
            Toast.makeText(this, "Imposta prima l'ora di fine", Toast.LENGTH_LONG).show();
            return;
        }
        if (!isValidSearchWindow()) {
            Toast.makeText(this, "L'ora di fine deve essere successiva all'ora di inizio", Toast.LENGTH_LONG).show();
            return;
        }

        if (!SearchScheduler.canScheduleExact(this)) {
            prefs.edit().putBoolean("pending_enable_schedule", true).apply();
            try {
                Intent permissionIntent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                        Uri.parse("package:" + getPackageName()));
                startActivity(permissionIntent);
                Toast.makeText(this, "Consenti ‘Sveglie e promemoria’ per avviare la ricerca all'ora scelta", Toast.LENGTH_LONG).show();
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "Abilita ‘Sveglie e promemoria’ nelle impostazioni Android", Toast.LENGTH_LONG).show();
            }
            return;
        }
        enableDailySchedule();
    }

    private void enableDailySchedule() {
        requestNotificationsIfNeeded();
        prefs.edit()
                .putBoolean("pending_enable_schedule", false)
                .putBoolean("daily_search_enabled", true)
                .putBoolean("auto_search_active", false)
                .apply();
        long next = SearchScheduler.scheduleNext(this);
        refreshAll();
        if (next > 0L) {
            Toast.makeText(this, "Ricerca giornaliera programmata", Toast.LENGTH_SHORT).show();
        }
    }

    private void completePendingScheduleEnableIfPossible() {
        if (!prefs.getBoolean("pending_enable_schedule", false)) return;
        if (!SearchScheduler.canScheduleExact(this)) return;
        if (!hasAnyFilterConfigured() || prefs.getLong("channel_id", 0L) == 0L) {
            prefs.edit().putBoolean("pending_enable_schedule", false).apply();
            return;
        }
        if (prefs.getInt("search_start_hour", -1) < 0 || prefs.getInt("search_start_minute", -1) < 0
                || prefs.getInt("search_end_hour", -1) < 0 || prefs.getInt("search_end_minute", -1) < 0
                || !isValidSearchWindow()) {
            prefs.edit().putBoolean("pending_enable_schedule", false).apply();
            return;
        }
        enableDailySchedule();
    }

    private void showStartTimePicker() {
        Calendar now = Calendar.getInstance();
        int savedHour = prefs.getInt("search_start_hour", -1);
        int savedMinute = prefs.getInt("search_start_minute", -1);
        int initialHour = savedHour >= 0 ? savedHour : now.get(Calendar.HOUR_OF_DAY);
        int initialMinute = savedMinute >= 0 ? savedMinute : now.get(Calendar.MINUTE);

        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            prefs.edit()
                    .putInt("search_start_hour", hourOfDay)
                    .putInt("search_start_minute", minute)
                    .apply();
            refreshStartTimeLabel();
            if (prefs.getBoolean("daily_search_enabled", false)) {
                SearchScheduler.scheduleNext(this);
            }
            refreshHeaderOnly();
        }, initialHour, initialMinute, true).show();
    }

    private void refreshStartTimeLabel() {
        if (textStartTime == null) return;
        int hour = prefs.getInt("search_start_hour", -1);
        int minute = prefs.getInt("search_start_minute", -1);
        if (hour < 0 || minute < 0) {
            textStartTime.setText("--:--");
        } else {
            textStartTime.setText(String.format(Locale.ITALY, "%02d:%02d", hour, minute));
        }
    }

    private void showEndTimePicker() {
        Calendar now = Calendar.getInstance();
        int savedHour = prefs.getInt("search_end_hour", -1);
        int savedMinute = prefs.getInt("search_end_minute", -1);
        int initialHour = savedHour >= 0 ? savedHour : Math.min(23, now.get(Calendar.HOUR_OF_DAY) + 6);
        int initialMinute = savedMinute >= 0 ? savedMinute : 0;

        new TimePickerDialog(this, (view, hourOfDay, minute) -> {
            prefs.edit()
                    .putInt("search_end_hour", hourOfDay)
                    .putInt("search_end_minute", minute)
                    .apply();
            refreshEndTimeLabel();
            if (prefs.getBoolean("daily_search_enabled", false)) {
                SearchScheduler.scheduleNext(this);
            }
            refreshHeaderOnly();
        }, initialHour, initialMinute, true).show();
    }

    private void refreshEndTimeLabel() {
        if (textEndTime == null) return;
        int hour = prefs.getInt("search_end_hour", -1);
        int minute = prefs.getInt("search_end_minute", -1);
        if (hour < 0 || minute < 0) {
            textEndTime.setText("--:--");
        } else {
            textEndTime.setText(String.format(Locale.ITALY, "%02d:%02d", hour, minute));
        }
    }

    private boolean isValidSearchWindow() {
        int sh = prefs.getInt("search_start_hour", -1);
        int sm = prefs.getInt("search_start_minute", -1);
        int eh = prefs.getInt("search_end_hour", -1);
        int em = prefs.getInt("search_end_minute", -1);
        if (sh < 0 || sm < 0 || eh < 0 || em < 0) return false;
        return eh * 60 + em > sh * 60 + sm;
    }

    private void searchNow() {
        saveConfig(true);
        if (!hasAnyFilterConfigured()) {
            Toast.makeText(this, "Configura almeno un filtro PDF", Toast.LENGTH_LONG).show();
            return;
        }
        if (prefs.getLong("channel_id", 0L) == 0L) {
            Toast.makeText(this, "Seleziona prima un canale Telegram", Toast.LENGTH_LONG).show();
            return;
        }
        requestNotificationsIfNeeded();
        Intent service = new Intent(this, PdfSearchService.class).setAction(PdfSearchService.ACTION_SEARCH_NOW);
        service.putExtra(PdfSearchService.EXTRA_ONE_SHOT, !prefs.getBoolean("auto_search_active", false));
        startForegroundService(service);
    }

    private void confirmNewSearch() {
        new AlertDialog.Builder(this)
                .setTitle("Nuova ricerca di oggi")
                .setMessage("Azzero i PDF trovati per " + todayLabel() + " e ricomincio da zero?")
                .setNegativeButton("Annulla", null)
                .setPositiveButton("Azzera", (dialog, which) -> {
                    stopService(new Intent(this, PdfSearchService.class));
                    SharedPreferences.Editor editor = prefs.edit()
                            .putBoolean("auto_search_active", false)
                            .putLong("next_search_at", 0L)
                            .putString("search_day_key", todayKey());
                    for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) {
                        clearResult(editor, slot);
                    }
                    editor.apply();
                    if (prefs.getBoolean("daily_search_enabled", false)) {
                        SearchScheduler.scheduleNext(this);
                    }
                    refreshAll();
                })
                .show();
    }

    private void clearResult(SharedPreferences.Editor editor, int slot) {
        editor.putBoolean("search_found_" + slot, false)
                .remove("search_result_name_" + slot)
                .remove("search_result_message_id_" + slot)
                .remove("search_result_file_id_" + slot)
                .remove("search_result_date_" + slot)
                .remove("search_result_size_" + slot)
                .remove("search_result_caption_" + slot)
                .remove("search_result_day_key_" + slot)
                .remove("search_result_cached_path_" + slot)
                .remove("search_result_notified_" + slot);
    }

    private void ensureTodayState() {
        if (prefs == null) return;
        String today = todayKey();
        String stored = prefs.getString("search_day_key", "");
        if (today.equals(stored)) return;

        SharedPreferences.Editor editor = prefs.edit()
                .putString("search_day_key", today)
                .putBoolean("auto_search_active", false)
                .putLong("next_search_at", 0L);
        for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) {
            deleteCachedResult(slot);
            clearResult(editor, slot);
            editor.remove("search_handled_day_key_" + slot);
        }
        editor.apply();
        purgeSharedPdfCache();
    }

    private void refreshAll() {
        ensureTodayState();
        String today = todayKey();
        int foundCount = 0;
        int configuredCount = 0;

        for (int i = 0; i < MainActivity.SLOT_COUNT; i++) {
            int slot = i + 1;
            String filter = prefs.getString("search_filter_" + slot, "").trim();
            boolean sameDay = today.equals(prefs.getString("search_result_day_key_" + slot, ""));
            boolean found = prefs.getBoolean("search_found_" + slot, false) && sameDay;

            if (!filter.isEmpty()) configuredCount++;
            if (found) foundCount++;

            if (filter.isEmpty()) {
                statusTexts[i].setText("DA CONFIGURARE");
                statusTexts[i].setTextColor(getColor(R.color.text_secondary));
                resultTexts[i].setText("Inserisci le parole per identificare questo PDF");
                shareButtons[i].setEnabled(false);
                shareButtons[i].setAlpha(0.45f);
            } else if (found) {
                statusTexts[i].setText("✓ TROVATO OGGI");
                statusTexts[i].setTextColor(getColor(R.color.green));
                String name = prefs.getString("search_result_name_" + slot, "documento.pdf");
                int date = prefs.getInt("search_result_date_" + slot, 0);
                long size = prefs.getLong("search_result_size_" + slot, 0L);
                String meta = date > 0 ? dateFormat.format(new Date(date * 1000L)) : "";
                if (size > 0) {
                    meta += (meta.isEmpty() ? "" : " • ") + formatSize(size);
                }
                resultTexts[i].setText(name + (meta.isEmpty() ? "" : "\n" + meta));
                shareButtons[i].setEnabled(true);
                shareButtons[i].setAlpha(1f);
            } else {
                statusTexts[i].setText("IN ATTESA • " + todayLabel());
                statusTexts[i].setTextColor(getColor(R.color.warning));
                resultTexts[i].setText("PDF di oggi non ancora trovato");
                shareButtons[i].setEnabled(false);
                shareButtons[i].setAlpha(0.45f);
            }
        }

        boolean canShareAll = configuredCount > 0 && foundCount == configuredCount;
        buttonShareAll.setEnabled(canShareAll);
        buttonShareAll.setAlpha(canShareAll ? 1f : 0.45f);
        buttonShareAll.setText(canShareAll
                ? "Invia i " + configuredCount + " PDF a WhatsApp"
                : "WhatsApp disponibile quando sono " + configuredCount + "/" + configuredCount);
        refreshHeaderOnly();
    }

    private void refreshHeaderOnly() {
        ensureTodayState();
        String today = todayKey();
        int configured = 0;
        int found = 0;
        for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) {
            if (!prefs.getString("search_filter_" + slot, "").trim().isEmpty()) {
                configured++;
                boolean sameDay = today.equals(prefs.getString("search_result_day_key_" + slot, ""));
                if (prefs.getBoolean("search_found_" + slot, false) && sameDay) {
                    found++;
                }
            }
        }
        textGlobalSearchStatus.setText(found + "/" + configured + " PDF di oggi trovati");
        if (textSearchDate != null) {
            textSearchDate.setText(todayLabel());
        }

        boolean active = prefs.getBoolean("auto_search_active", false);
        boolean dailyEnabled = prefs.getBoolean("daily_search_enabled", false);
        buttonAutoSearch.setText(dailyEnabled ? "Disattiva avvio programmato" : "Attiva avvio programmato");
        refreshStartTimeLabel();
        refreshEndTimeLabel();

        if (active) {
            long next = prefs.getLong("next_search_at", 0L);
            if (next <= System.currentTimeMillis()) {
                textNextSearch.setText("Ricerca dei PDF di oggi in corso…");
            } else {
                long seconds = Math.max(0L, (next - System.currentTimeMillis()) / 1000L);
                long minutes = seconds / 60L;
                long remain = seconds % 60L;
                textNextSearch.setText(String.format(Locale.ITALY,
                        "Prossimo tentativo tra %d:%02d • cerco solo i PDF mancanti", minutes, remain));
            }
            return;
        }

        if (dailyEnabled) {
            long scheduled = prefs.getLong("scheduled_start_at", 0L);
            if (scheduled <= System.currentTimeMillis()) {
                scheduled = SearchScheduler.scheduleNext(this);
            }
            if (scheduled > 0L) {
                String prefix = configured > 0 && found >= configured
                        ? configured + "/" + configured + " trovati • prossimo ciclo: "
                        : "Prossimo avvio: ";
                textNextSearch.setText(prefix + dateFormat.format(new Date(scheduled)));
            } else {
                textNextSearch.setText("Imposta l'ora di inizio");
            }
        } else {
            textNextSearch.setText(configured > 0 && found >= configured
                    ? "Tutti i PDF configurati di oggi sono stati trovati"
                    : "Avvio programmato disattivato");
        }
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void downloadAndShare(int index, Button sourceButton) {
        int slot = index + 1;
        int fileId = prefs.getInt("search_result_file_id_" + slot, 0);
        long messageId = prefs.getLong("search_result_message_id_" + slot, 0L);
        String fileName = prefs.getString("search_result_name_" + slot, "documento.pdf");
        if (fileId == 0 || messageId == 0L) {
            Toast.makeText(this, "Dati del PDF non disponibili", Toast.LENGTH_SHORT).show();
            return;
        }

        Client client = MainActivity.getSharedTelegramClient();
        if (!MainActivity.isSharedTelegramReady() || client == null) {
            Toast.makeText(this, "Telegram si sta ricollegando. Torna alla pagina principale e attendi qualche secondo.", Toast.LENGTH_LONG).show();
            return;
        }

        sourceButton.setEnabled(false);
        sourceButton.setText("Scarico…");
        TdApi.DownloadFile request = new TdApi.DownloadFile(fileId, 32, 0, 0, true);
        client.send(request, object -> runOnUiThread(() -> {
            sourceButton.setEnabled(true);
            sourceButton.setText("Invia questo PDF a WhatsApp");
            if (object instanceof TdApi.Error) {
                Toast.makeText(this, "Download: " + ((TdApi.Error) object).message, Toast.LENGTH_LONG).show();
                return;
            }
            if (!(object instanceof TdApi.File)) {
                Toast.makeText(this, "Risposta Telegram non riconosciuta", Toast.LENGTH_LONG).show();
                return;
            }
            TdApi.File file = (TdApi.File) object;
            if (file.local == null || !file.local.isDownloadingCompleted || file.local.path == null || file.local.path.isEmpty()) {
                Toast.makeText(this, "Download non ancora completato, riprova", Toast.LENGTH_LONG).show();
                return;
            }
            try {
                Uri uri = prepareSharedUri(messageId, fileName, file.local.path);
                StorageCleanup.releaseTdlibDownload(this, client, fileId, file.local.path);
                shareUri(uri, fileName);
            } catch (Exception e) {
                Toast.makeText(this, safeError(e, "Errore preparando il PDF"), Toast.LENGTH_LONG).show();
            }
        }));
    }

    private void downloadAndShareAll() {
        ensureTodayState();
        if (!allConfiguredReady()) {
            Toast.makeText(this, "Prima devono essere trovati tutti i PDF configurati di oggi", Toast.LENGTH_LONG).show();
            return;
        }

        Client client = MainActivity.getSharedTelegramClient();
        if (!MainActivity.isSharedTelegramReady() || client == null) {
            Toast.makeText(this, "Telegram si sta ricollegando. Torna alla pagina principale e attendi qualche secondo.", Toast.LENGTH_LONG).show();
            return;
        }

        buttonShareAll.setEnabled(false);
        ArrayList<Integer> slots = configuredSlots();
        buttonShareAll.setText("Preparo 1/" + slots.size() + "…");
        downloadAllRecursive(0, slots, client, new ArrayList<>());
    }

    private void downloadAllRecursive(int index, ArrayList<Integer> slots, Client client, ArrayList<Uri> uris) {
        if (index >= slots.size()) {
            buttonShareAll.setEnabled(true);
            buttonShareAll.setText("Invia i " + slots.size() + " PDF a WhatsApp");
            shareMultipleUris(uris);
            return;
        }

        int slot = slots.get(index);
        int fileId = prefs.getInt("search_result_file_id_" + slot, 0);
        long messageId = prefs.getLong("search_result_message_id_" + slot, 0L);
        String fileName = prefs.getString("search_result_name_" + slot, "documento_" + slot + ".pdf");
        if (fileId == 0 || messageId == 0L) {
            abortShareAll("Dati mancanti per il PDF " + slot);
            return;
        }

        buttonShareAll.setText("Preparo " + (index + 1) + "/" + slots.size() + "…");
        TdApi.DownloadFile request = new TdApi.DownloadFile(fileId, 32, 0, 0, true);
        client.send(request, object -> runOnUiThread(() -> {
            if (object instanceof TdApi.Error) {
                abortShareAll("Download PDF " + slot + ": " + ((TdApi.Error) object).message);
                return;
            }
            if (!(object instanceof TdApi.File)) {
                abortShareAll("Risposta Telegram non riconosciuta per il PDF " + slot);
                return;
            }
            TdApi.File file = (TdApi.File) object;
            if (file.local == null || !file.local.isDownloadingCompleted || file.local.path == null || file.local.path.isEmpty()) {
                abortShareAll("Download del PDF " + slot + " non completato");
                return;
            }
            try {
                uris.add(prepareSharedUri(messageId, fileName, file.local.path));
                StorageCleanup.releaseTdlibDownload(this, client, fileId, file.local.path);
                downloadAllRecursive(index + 1, slots, client, uris);
            } catch (Exception e) {
                abortShareAll(safeError(e, "Errore preparando il PDF " + slot));
            }
        }));
    }

    private void abortShareAll(String message) {
        buttonShareAll.setEnabled(true);
        buttonShareAll.setText("Invia PDF configurati a WhatsApp");
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private ArrayList<Integer> configuredSlots() {
        ArrayList<Integer> slots = new ArrayList<>();
        for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) {
            if (!prefs.getString("search_filter_" + slot, "").trim().isEmpty()) slots.add(slot);
        }
        return slots;
    }

    private boolean allConfiguredReady() {
        String today = todayKey();
        ArrayList<Integer> slots = configuredSlots();
        if (slots.isEmpty()) return false;
        for (int slot : slots) {
            if (!prefs.getBoolean("search_found_" + slot, false)) return false;
            if (!today.equals(prefs.getString("search_result_day_key_" + slot, ""))) return false;
            if (prefs.getInt("search_result_file_id_" + slot, 0) == 0) return false;
        }
        return true;
    }

    private Uri prepareSharedUri(long messageId, String fileName, String localPath) throws IOException {
        File source = new File(localPath);
        if (!source.exists() || source.length() == 0L) {
            throw new IOException("File Telegram non trovato");
        }
        String safeName = sanitizeFileName(fileName);
        File folder = new File(getCacheDir(), "shared_pdf/" + messageId);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Impossibile preparare il PDF");
        }
        File destination = new File(folder, safeName);
        copyFile(source, destination);
        return new Uri.Builder()
                .scheme("content")
                .authority(PDF_PROVIDER_AUTHORITY)
                .appendPath(String.valueOf(messageId))
                .appendPath(safeName)
                .build();
    }

    private void shareUri(Uri uri, String title) {
        Intent base = new Intent(Intent.ACTION_SEND);
        base.setType("application/pdf");
        base.putExtra(Intent.EXTRA_STREAM, uri);
        base.putExtra(Intent.EXTRA_TITLE, title);
        base.setClipData(ClipData.newRawUri("PDF", uri));
        base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (tryPackage(base, "com.whatsapp") || tryPackage(base, "com.whatsapp.w4b")) {
            return;
        }
        try {
            startActivity(Intent.createChooser(new Intent(base), "Condividi PDF"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nessuna app disponibile per condividere", Toast.LENGTH_LONG).show();
        }
    }

    private void shareMultipleUris(ArrayList<Uri> uris) {
        int expected = configuredSlots().size();
        if (expected == 0 || uris.size() != expected) {
            Toast.makeText(this, "Non sono riuscito a preparare tutti i PDF configurati", Toast.LENGTH_LONG).show();
            return;
        }
        Intent base = new Intent(Intent.ACTION_SEND_MULTIPLE);
        base.setType("application/pdf");
        base.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        base.putExtra(Intent.EXTRA_TEXT, "PDF del " + todayLabel());
        ClipData clipData = ClipData.newRawUri("PDF 1", uris.get(0));
        for (int i = 1; i < uris.size(); i++) {
            clipData.addItem(new ClipData.Item(uris.get(i)));
        }
        base.setClipData(clipData);
        base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        if (tryPackage(base, "com.whatsapp") || tryPackage(base, "com.whatsapp.w4b")) {
            return;
        }
        try {
            startActivity(Intent.createChooser(new Intent(base), "Condividi PDF configurati"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Nessuna app disponibile per condividere", Toast.LENGTH_LONG).show();
        }
    }

    private boolean tryPackage(Intent base, String packageName) {
        Intent intent = new Intent(base);
        intent.setPackage(packageName);
        try {
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private void copyFile(File source, File destination) throws IOException {
        try (FileInputStream in = new FileInputStream(source);
             FileOutputStream out = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32768];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
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

    private String formatSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024.0) return String.format(Locale.ITALY, "%.1f KB", kb);
        return String.format(Locale.ITALY, "%.1f MB", kb / 1024.0);
    }

    private void deleteCachedResult(int slot) {
        String path = prefs.getString("search_result_cached_path_" + slot, "");
        if (!path.isEmpty()) {
            try {
                File file = new File(path);
                if (file.exists()) file.delete();
                File parent = file.getParentFile();
                if (parent != null && parent.isDirectory()) parent.delete();
            } catch (Exception ignored) {
            }
        }
    }

    private void purgeSharedPdfCache() {
        File root = new File(getCacheDir(), "shared_pdf");
        deleteTree(root);
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        try { file.delete(); } catch (Exception ignored) {}
    }

    private String todayKey() {
        return dayKeyFormat.format(new Date());
    }

    private String todayLabel() {
        return dayLabelFormat.format(new Date());
    }

    private String safeError(Exception e, String fallback) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? fallback : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
