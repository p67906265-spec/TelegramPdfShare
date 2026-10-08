package it.paolo.telegrampdfshare;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class PdfSearchService extends Service {

    static final String ACTION_START = "it.paolo.telegrampdfshare.START_AUTO_SEARCH";
    static final String ACTION_SEARCH_NOW = "it.paolo.telegrampdfshare.SEARCH_NOW";
    static final String ACTION_UPDATED = "it.paolo.telegrampdfshare.SEARCH_UPDATED";
    static final String EXTRA_ONE_SHOT = "one_shot";

    private static final String PREFS = "telegram_pdf_share_prefs";
    private static final String CHANNEL_ID_NOTIFICATION = "pdf_auto_search";
    private static final String CHANNEL_ID_FOUND = "pdf_found_alerts_v06";
    private static final int NOTIFICATION_ID = 4045;
    private static final int FOUND_NOTIFICATION_BASE_ID = 4600;
    private static final int MAX_DOCUMENTS = 500;
    private static final DateTimeFormatter DAY_KEY = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ITALY);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private volatile boolean searching = false;
    private volatile boolean initializingClient = false;
    private volatile boolean oneShotRequested = false;
    private volatile boolean automaticCycle = true;

    private final Runnable scheduledSearch = this::beginSearchCycle;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (action == null) action = ACTION_START;

        oneShotRequested = intent != null && intent.getBooleanExtra(EXTRA_ONE_SHOT, false);
        automaticCycle = !ACTION_SEARCH_NOW.equals(action);
        startForeground(NOTIFICATION_ID, buildNotification("Preparazione ricerca PDF…"));

        if (ACTION_START.equals(action)) {
            prefs.edit().putBoolean("auto_search_active", true).apply();
        }
        if (ACTION_SEARCH_NOW.equals(action)) {
            handler.removeCallbacks(scheduledSearch);
        }

        beginSearchCycle();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        searching = false;
        super.onDestroy();
    }

    private void beginSearchCycle() {
        if (searching) return;

        ensureCurrentDay();
        if (automaticCycle && !isWithinAutomaticWindow()) {
            stopAtEndOfWindow();
            return;
        }
        if (automaticCycle) {
            prefs.edit()
                    .putLong("last_auto_search_at", System.currentTimeMillis())
                    .putString("last_auto_search_day", todayKey())
                    .apply();
        }

        long channelId = prefs.getLong("channel_id", 0L);
        if (channelId == 0L) {
            stopWithMessage("Nessun canale Telegram selezionato", true);
            return;
        }
        int configuredCount = countConfiguredFilters();
        if (configuredCount == 0) {
            stopWithMessage("Configura almeno un filtro PDF", true);
            return;
        }
        if (countCompletedToday() >= configuredCount) {
            completeAllFound();
            return;
        }

        updateNotification("Ricerca PDF • " + countFound() + "/" + configuredCount + " trovati");
        prefs.edit().putLong("next_search_at", 0L).apply();
        broadcastUpdate();

        Client client = MainActivity.getSharedTelegramClient();
        if (client != null && MainActivity.isSharedTelegramReady()) {
            searchWithClient(client, channelId);
            return;
        }
        ensureTelegramClient();
    }

    private void ensureTelegramClient() {
        Client existing = MainActivity.getSharedTelegramClient();
        if (existing != null) {
            if (MainActivity.isSharedTelegramReady()) {
                searchWithClient(existing, prefs.getLong("channel_id", 0L));
            } else {
                handler.postDelayed(this::beginSearchCycle, 1200L);
            }
            return;
        }
        if (initializingClient) return;

        String apiIdText = prefs.getString("telegram_api_id", "").trim();
        String apiHash = prefs.getString("telegram_api_hash", "").trim();
        if (apiIdText.isEmpty() || apiHash.isEmpty()) {
            stopWithMessage("Apri l'app e collega Telegram", true);
            return;
        }

        final int apiId;
        try {
            apiId = Integer.parseInt(apiIdText);
        } catch (NumberFormatException e) {
            stopWithMessage("API ID Telegram non valido", true);
            return;
        }

        initializingClient = true;
        try {
            try {
                Client.execute(new TdApi.SetLogVerbosityLevel(1));
            } catch (Exception ignored) {
            }

            Client client = Client.create(this::handleTdUpdate,
                    throwable -> stopWithMessage("Errore Telegram", false),
                    throwable -> stopWithMessage("Errore TDLib", false));
            MainActivity.setSharedTelegramState(client, false, true);

            File tdDir = new File(getFilesDir(), "tdlib");
            File tdFiles = new File(tdDir, "files");
            //noinspection ResultOfMethodCallIgnored
            tdDir.mkdirs();
            //noinspection ResultOfMethodCallIgnored
            tdFiles.mkdirs();

            String language = Locale.getDefault().toLanguageTag();
            if (language == null || language.trim().isEmpty()) language = "it";

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
                    "0.11"
            );
            client.send(parameters, object -> {
                if (object instanceof TdApi.Error) {
                    stopWithMessage("Telegram: " + ((TdApi.Error) object).message, true);
                }
            });
        } catch (Throwable e) {
            initializingClient = false;
            MainActivity.setSharedTelegramState(null, false, false);
            stopWithMessage("Impossibile avviare Telegram", true);
        }
    }

    private void handleTdUpdate(TdApi.Object object) {
        if (!(object instanceof TdApi.UpdateAuthorizationState)) return;
        TdApi.AuthorizationState state = ((TdApi.UpdateAuthorizationState) object).authorizationState;

        if (state instanceof TdApi.AuthorizationStateReady) {
            initializingClient = false;
            MainActivity.setSharedTelegramState(MainActivity.getSharedTelegramClient(), true, false);
            handler.post(this::beginSearchCycle);
            return;
        }

        if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber
                || state instanceof TdApi.AuthorizationStateWaitCode
                || state instanceof TdApi.AuthorizationStateWaitPassword) {
            initializingClient = false;
            MainActivity.setSharedTelegramState(MainActivity.getSharedTelegramClient(), false, false);
            stopWithMessage("Apri l'app: Telegram richiede di ricollegarsi", true);
            return;
        }

        if (state instanceof TdApi.AuthorizationStateLoggingOut
                || state instanceof TdApi.AuthorizationStateClosing
                || state instanceof TdApi.AuthorizationStateClosed) {
            initializingClient = false;
            MainActivity.setSharedTelegramState(null, false, false);
        }
    }

    private void searchWithClient(Client client, long channelId) {
        if (searching) return;
        searching = true;
        SearchCycle cycle = new SearchCycle();
        cycle.excludeFilter = prefs.getString("global_exclude_filter", "").trim().toLowerCase(Locale.ROOT);
        for (int slot = 1; slot <= 7; slot++) {
            cycle.filters[slot - 1] = prefs.getString("search_filter_" + slot, "").trim().toLowerCase(Locale.ROOT);
            if (cycle.filters[slot - 1].isEmpty()) {
                cycle.found[slot - 1] = true;
                continue;
            }
            boolean foundToday = prefs.getBoolean("search_found_" + slot, false)
                    && todayKey().equals(prefs.getString("search_result_day_key_" + slot, ""));
            boolean handledToday = todayKey().equals(prefs.getString("search_handled_day_key_" + slot, ""));
            cycle.found[slot - 1] = foundToday || handledToday;
            if (foundToday) {
                long id = prefs.getLong("search_result_message_id_" + slot, 0L);
                if (id != 0L) cycle.usedMessageIds.add(id);
            }
        }
        searchPage(client, channelId, 0L, 0, cycle);
    }

    private void searchPage(Client client, long channelId, long fromMessageId, int scanned, SearchCycle cycle) {
        TdApi.SearchChatMessages request = new TdApi.SearchChatMessages();
        request.chatId = channelId;
        request.query = "";
        request.fromMessageId = fromMessageId;
        request.offset = 0;
        request.limit = 100;
        request.filter = new TdApi.SearchMessagesFilterDocument();

        client.send(request, object -> {
            if (object instanceof TdApi.Error) {
                finishCycle(false, "Telegram: " + ((TdApi.Error) object).message);
                return;
            }
            if (!(object instanceof TdApi.FoundChatMessages)) {
                finishCycle(false, "Risposta Telegram non riconosciuta");
                return;
            }

            TdApi.FoundChatMessages foundMessages = (TdApi.FoundChatMessages) object;
            int scannedNow = scanned;
            if (foundMessages.messages != null) {
                for (TdApi.Message message : foundMessages.messages) {
                    scannedNow++;
                    PdfCandidate pdf = candidateFromMessage(message);
                    if (pdf == null || cycle.usedMessageIds.contains(pdf.messageId)) continue;
                    if (matchesExcluded(pdf, cycle.excludeFilter)) continue;

                    for (int i = 0; i < 7; i++) {
                        if (cycle.found[i]) continue;
                        if (matchesFilter(pdf, cycle.filters[i])) {
                            saveFound(i + 1, pdf);
                            cycle.found[i] = true;
                            cycle.usedMessageIds.add(pdf.messageId);
                            broadcastUpdate();
                            break;
                        }
                    }
                    if (allFound(cycle.found)) break;
                }
            }

            if (allFound(cycle.found)) {
                finishCycle(true, null);
                return;
            }

            boolean noMore = foundMessages.messages == null
                    || foundMessages.messages.length == 0
                    || foundMessages.nextFromMessageId == 0L;
            boolean limit = scannedNow >= MAX_DOCUMENTS;
            if (noMore || limit) {
                finishCycle(false, null);
            } else {
                searchPage(client, channelId, foundMessages.nextFromMessageId, scannedNow, cycle);
            }
        });
    }

    private PdfCandidate candidateFromMessage(TdApi.Message message) {
        if (message == null || !(message.content instanceof TdApi.MessageDocument)) return null;
        TdApi.MessageDocument content = (TdApi.MessageDocument) message.content;
        if (content.document == null || content.document.document == null) return null;

        String fileName = content.document.fileName == null ? "documento.pdf" : content.document.fileName.trim();
        String mime = content.document.mimeType == null ? "" : content.document.mimeType.trim();
        boolean isPdf = "application/pdf".equalsIgnoreCase(mime)
                || fileName.toLowerCase(Locale.ROOT).endsWith(".pdf");
        if (!isPdf) return null;

        String caption = "";
        if (content.caption != null && content.caption.text != null) {
            caption = content.caption.text.trim();
        }
        return new PdfCandidate(
                message.id,
                message.date,
                content.document.document.id,
                content.document.document.size,
                fileName.isEmpty() ? "documento.pdf" : fileName,
                caption
        );
    }

    private boolean matchesFilter(PdfCandidate pdf, String filter) {
        if (filter == null || filter.trim().isEmpty()) return false;
        if (!isTodayPdf(pdf)) return false;
        String haystack = (pdf.fileName + " " + pdf.caption).toLowerCase(Locale.ROOT);
        for (String word : filter.trim().split("\\s+")) {
            if (!word.isEmpty() && !haystack.contains(word)) return false;
        }
        return true;
    }

    private boolean matchesExcluded(PdfCandidate pdf, String excludeFilter) {
        if (excludeFilter == null || excludeFilter.trim().isEmpty()) return false;
        String haystack = (pdf.fileName + " " + pdf.caption).toLowerCase(Locale.ROOT);
        String normalized = excludeFilter.replace('\n', ' ').replace(';', ' ').replace(',', ' ');
        String[] terms = normalized.trim().split("\\s+");
        for (String term : terms) {
            String value = term.trim();
            if (!value.isEmpty() && haystack.contains(value)) return true;
        }
        return false;
    }

    private boolean isTodayPdf(PdfCandidate pdf) {
        LocalDate today = LocalDate.now();
        if (pdf.date > 0) {
            LocalDate messageDay = Instant.ofEpochSecond(pdf.date)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
            if (today.equals(messageDay)) return true;
        }

        String haystack = (pdf.fileName + " " + pdf.caption).toLowerCase(Locale.ROOT);
        String[] variants = new String[]{
                today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ITALY)),
                today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ITALY)),
                today.format(DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.ITALY)),
                today.format(DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ITALY)),
                today.format(DateTimeFormatter.ofPattern("ddMMyyyy", Locale.ITALY))
        };
        for (String value : variants) {
            if (haystack.contains(value.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private void saveFound(int slot, PdfCandidate pdf) {
        prefs.edit()
                .putBoolean("search_found_" + slot, true)
                .putString("search_result_name_" + slot, pdf.fileName)
                .putLong("search_result_message_id_" + slot, pdf.messageId)
                .putInt("search_result_file_id_" + slot, pdf.fileId)
                .putInt("search_result_date_" + slot, pdf.date)
                .putLong("search_result_size_" + slot, pdf.size)
                .putString("search_result_caption_" + slot, pdf.caption)
                .putString("search_result_day_key_" + slot, todayKey())
                .putBoolean("search_result_notified_" + slot, false)
                .remove("search_result_cached_path_" + slot)
                .apply();
    }

    private void finishCycle(boolean everythingFound, String error) {
        Client client = MainActivity.getSharedTelegramClient();
        if (client != null && MainActivity.isSharedTelegramReady()) {
            downloadFoundFiles(client, 1, () -> finishCycleAfterDownloads(everythingFound, error));
        } else {
            finishCycleAfterDownloads(everythingFound, error);
        }
    }

    private void finishCycleAfterDownloads(boolean everythingFound, String error) {
        searching = false;
        int found = countFound();
        int downloaded = countDownloaded();
        if (automaticCycle) {
            sendControlMessage(buildCycleControlMessage(found, downloaded, error));
        }
        if (countCompletedToday() >= countConfiguredFilters()) {
            completeAllFound();
            return;
        }

        boolean active = prefs.getBoolean("auto_search_active", false);
        if (oneShotRequested || !active) {
            prefs.edit().putLong("next_search_at", 0L).apply();
            broadcastUpdate();
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        int retryMinutes = getRetryMinutes();
        long retryDelayMs = retryMinutes * 60L * 1000L;
        long next = System.currentTimeMillis() + retryDelayMs;
        if (automaticCycle && isAtOrPastEnd(next)) {
            stopAtEndOfWindow();
            return;
        }
        prefs.edit().putLong("next_search_at", next).apply();
        String text = error == null
                ? "Ricerca PDF • " + found + "/" + countConfiguredFilters() + " trovati • riprovo tra " + retryMinutes + " min"
                : "Ricerca PDF • " + found + "/" + countConfiguredFilters() + " • riprovo tra " + retryMinutes + " min";
        updateNotification(text);
        broadcastUpdate();
        handler.removeCallbacks(scheduledSearch);
        handler.postDelayed(scheduledSearch, retryDelayMs);
    }

    private void downloadFoundFiles(Client client, int slot, Runnable done) {
        if (slot > 7) {
            done.run();
            return;
        }

        boolean foundToday = prefs.getBoolean("search_found_" + slot, false)
                && todayKey().equals(prefs.getString("search_result_day_key_" + slot, ""));
        if (!foundToday) {
            downloadFoundFiles(client, slot + 1, done);
            return;
        }

        String cachedPath = prefs.getString("search_result_cached_path_" + slot, "");
        if (!cachedPath.isEmpty() && new File(cachedPath).exists()) {
            notifyStoredPdfIfNeeded(slot);
            downloadFoundFiles(client, slot + 1, done);
            return;
        }

        int fileId = prefs.getInt("search_result_file_id_" + slot, 0);
        long messageId = prefs.getLong("search_result_message_id_" + slot, 0L);
        String fileName = prefs.getString("search_result_name_" + slot, "documento.pdf");
        if (fileId == 0 || messageId == 0L) {
            downloadFoundFiles(client, slot + 1, done);
            return;
        }

        updateNotification("PDF " + slot + " trovato • download automatico…");
        TdApi.DownloadFile request = new TdApi.DownloadFile(fileId, 32, 0, 0, true);
        final int currentSlot = slot;
        client.send(request, object -> {
            if (object instanceof TdApi.File) {
                TdApi.File file = (TdApi.File) object;
                if (file.local != null && file.local.isDownloadingCompleted
                        && file.local.path != null && !file.local.path.trim().isEmpty()) {
                    try {
                        File cached = cacheDownloadedFile(messageId, fileName, file.local.path);
                        StorageCleanup.releaseTdlibDownload(this, client, fileId, file.local.path);
                        prefs.edit().putString("search_result_cached_path_" + currentSlot, cached.getAbsolutePath()).apply();
                        notifyStoredPdfIfNeeded(currentSlot);
                        broadcastUpdate();
                    } catch (IOException ignored) {
                    }
                }
            }
            downloadFoundFiles(client, currentSlot + 1, done);
        });
    }

    private File cacheDownloadedFile(long messageId, String fileName, String localPath) throws IOException {
        File source = new File(localPath);
        if (!source.exists() || source.length() == 0L) {
            throw new IOException("File Telegram non disponibile");
        }
        String safeName = sanitizeFileName(fileName);
        File folder = new File(getCacheDir(), "shared_pdf/" + messageId);
        if (!folder.exists() && !folder.mkdirs()) {
            throw new IOException("Impossibile creare la cartella PDF");
        }
        File destination = new File(folder, safeName);
        copyFile(source, destination);
        return destination;
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
        if (value.isEmpty()) value = "documento.pdf";
        if (!value.toLowerCase(Locale.ROOT).endsWith(".pdf")) value += ".pdf";
        return value;
    }

    private void notifyStoredPdfIfNeeded(int slot) {
        if (prefs.getBoolean("search_result_notified_" + slot, false)) return;
        String fileName = prefs.getString("search_result_name_" + slot, "documento.pdf");
        notifyPdfFound(slot, fileName);
        if (automaticCycle) {
            sendControlMessage(nowTime() + " - PDF " + slot + " trovato e scaricato - " + fileName);
        }
        prefs.edit().putBoolean("search_result_notified_" + slot, true).apply();
    }

    private String buildCycleControlMessage(int found, int downloaded, String error) {
        StringBuilder text = new StringBuilder();
        text.append(nowTime()).append(" - ricerca automatica eseguita - ")
                .append(found).append("/").append(countConfiguredFilters()).append(" PDF trovati");
        if (downloaded != found) text.append(" - ").append(downloaded).append(" scaricati");
        if (error != null && !error.trim().isEmpty()) text.append(" - errore: ").append(error.trim());
        else if (found < countConfiguredFilters()) text.append(" - ").append(countConfiguredFilters() - found).append(" mancanti");
        else text.append(" - completata");
        return text.toString();
    }

    private String nowTime() {
        return new SimpleDateFormat("HH:mm", Locale.ITALY).format(new Date());
    }

    private void sendControlMessage(String text) {
        if (!prefs.getBoolean("control_messages_enabled", false)) return;
        Client client = MainActivity.getSharedTelegramClient();
        if (client == null || !MainActivity.isSharedTelegramReady()) return;

        long cachedChatId = prefs.getLong("saved_messages_chat_id", 0L);
        if (cachedChatId != 0L) {
            sendTextToChat(client, cachedChatId, text);
            return;
        }

        try {
            client.send(new TdApi.GetMe(), meObject -> {
                if (meObject instanceof TdApi.Error) {
                    prefs.edit().putString("last_control_message_error", ((TdApi.Error) meObject).message).apply();
                    return;
                }
                if (!(meObject instanceof TdApi.User)) return;
                long myUserId = ((TdApi.User) meObject).id;
                client.send(new TdApi.CreatePrivateChat(myUserId, false), chatObject -> {
                    if (chatObject instanceof TdApi.Error) {
                        prefs.edit().putString("last_control_message_error", ((TdApi.Error) chatObject).message).apply();
                        return;
                    }
                    if (!(chatObject instanceof TdApi.Chat)) return;
                    long savedChatId = ((TdApi.Chat) chatObject).id;
                    prefs.edit().putLong("saved_messages_chat_id", savedChatId).apply();
                    sendTextToChat(client, savedChatId, text);
                });
            });
        } catch (Throwable e) {
            prefs.edit().putString("last_control_message_error", e.getClass().getSimpleName()).apply();
        }
    }

    private void sendTextToChat(Client client, long chatId, String text) {
        try {
            TdApi.FormattedText formattedText = new TdApi.FormattedText();
            formattedText.text = text;
            formattedText.entities = new TdApi.TextEntity[0];
            TdApi.InputMessageText input = new TdApi.InputMessageText();
            input.text = formattedText;
            TdApi.SendMessage request = new TdApi.SendMessage();
            request.chatId = chatId;
            request.inputMessageContent = input;
            client.send(request, object -> {
                if (object instanceof TdApi.Error) {
                    prefs.edit().putString("last_control_message_error", ((TdApi.Error) object).message).apply();
                } else {
                    prefs.edit().remove("last_control_message_error").apply();
                }
            });
        } catch (Throwable e) {
            prefs.edit().putString("last_control_message_error", e.getClass().getSimpleName()).apply();
        }
    }

    private void completeAllFound() {
        prefs.edit()
                .putBoolean("auto_search_active", false)
                .putLong("next_search_at", 0L)
                .apply();
        if (prefs.getBoolean("daily_search_enabled", false)) {
            SearchScheduler.scheduleNext(this);
        }
        broadcastUpdate();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }


    private int getRetryMinutes() {
        int minutes = prefs.getInt("retry_minutes", 15);
        if (minutes < 1) minutes = 15;
        return Math.min(minutes, 180);
    }

    private void ensureCurrentDay() {
        String today = todayKey();
        String stored = prefs.getString("search_day_key", "");
        if (today.equals(stored)) return;

        SharedPreferences.Editor editor = prefs.edit()
                .putString("search_day_key", today)
                .putBoolean("auto_search_active", false)
                .putLong("next_search_at", 0L);
        for (int slot = 1; slot <= 7; slot++) {
            deleteCachedResult(slot);
            clearResult(editor, slot);
            editor.remove("search_handled_day_key_" + slot);
        }
        editor.apply();
        purgeSharedPdfCache();
        broadcastUpdate();
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

    private String todayKey() {
        return LocalDate.now().format(DAY_KEY);
    }

    private int countConfiguredFilters() {
        int count = 0;
        for (int slot = 1; slot <= 7; slot++) {
            if (!prefs.getString("search_filter_" + slot, "").trim().isEmpty()) count++;
        }
        return count;
    }

    private int countCompletedToday() {
        int count = 0;
        String today = todayKey();
        for (int slot = 1; slot <= 7; slot++) {
            if (prefs.getString("search_filter_" + slot, "").trim().isEmpty()) continue;
            boolean foundToday = prefs.getBoolean("search_found_" + slot, false)
                    && today.equals(prefs.getString("search_result_day_key_" + slot, ""));
            boolean handledToday = today.equals(prefs.getString("search_handled_day_key_" + slot, ""));
            if (foundToday || handledToday) count++;
        }
        return count;
    }

    private boolean isWithinAutomaticWindow() {
        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startHour = prefs.getInt("search_start_hour", -1);
        int startMinute = prefs.getInt("search_start_minute", -1);
        int endHour = prefs.getInt("search_end_hour", -1);
        int endMinute = prefs.getInt("search_end_minute", -1);
        if (startHour < 0 || startMinute < 0 || endHour < 0 || endMinute < 0) return false;
        int start = startHour * 60 + startMinute;
        int end = endHour * 60 + endMinute;
        return nowMinutes >= start && nowMinutes < end;
    }

    private boolean isAtOrPastEnd(long timeMillis) {
        int endHour = prefs.getInt("search_end_hour", -1);
        int endMinute = prefs.getInt("search_end_minute", -1);
        if (endHour < 0 || endMinute < 0) return false;
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(timeMillis);
        int minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int end = endHour * 60 + endMinute;
        return minutes >= end;
    }

    private void stopAtEndOfWindow() {
        searching = false;
        prefs.edit()
                .putBoolean("auto_search_active", false)
                .putLong("next_search_at", 0L)
                .putString("last_auto_start_reason", "fine fascia oraria")
                .apply();
        if (prefs.getBoolean("daily_search_enabled", false)) SearchScheduler.scheduleNext(this);
        broadcastUpdate();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void deleteCachedResult(int slot) {
        String path = prefs.getString("search_result_cached_path_" + slot, "");
        if (!path.isEmpty()) {
            try {
                File file = new File(path);
                if (file.exists()) file.delete();
                File parent = file.getParentFile();
                if (parent != null && parent.isDirectory()) parent.delete();
            } catch (Exception ignored) {}
        }
    }

    private void purgeSharedPdfCache() {
        deleteTree(new File(getCacheDir(), "shared_pdf"));
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        try { file.delete(); } catch (Exception ignored) {}
    }

    private int countFound() {
        int count = 0;
        for (int slot = 1; slot <= 7; slot++) {
            if (prefs.getString("search_filter_" + slot, "").trim().isEmpty()) continue;
            if (prefs.getBoolean("search_found_" + slot, false)) count++;
        }
        return count;
    }

    private int countDownloaded() {
        int count = 0;
        String today = todayKey();
        for (int slot = 1; slot <= 7; slot++) {
            if (!prefs.getBoolean("search_found_" + slot, false)) continue;
            if (!today.equals(prefs.getString("search_result_day_key_" + slot, ""))) continue;
            String cachedPath = prefs.getString("search_result_cached_path_" + slot, "");
            if (!cachedPath.isEmpty() && new File(cachedPath).exists()) count++;
        }
        return count;
    }

    private boolean allFound(boolean[] values) {
        for (boolean value : values) if (!value) return false;
        return true;
    }

    private void stopWithMessage(String message, boolean disableAuto) {
        searching = false;
        initializingClient = false;
        SharedPreferences.Editor editor = prefs.edit().putLong("next_search_at", 0L);
        if (disableAuto) editor.putBoolean("auto_search_active", false);
        editor.apply();
        updateNotification(message);
        broadcastUpdate();
        handler.postDelayed(() -> {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, 2500L);
    }

    private void broadcastUpdate() {
        Intent intent = new Intent(ACTION_UPDATED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID_NOTIFICATION,
                "Ricerca automatica PDF",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Stato della ricerca automatica dei 7 PDF Telegram");
        manager.createNotificationChannel(channel);

        NotificationChannel foundChannel = new NotificationChannel(
                CHANNEL_ID_FOUND,
                "PDF trovato",
                NotificationManager.IMPORTANCE_HIGH);
        foundChannel.setDescription("Avvisa con un suono quando viene trovato un nuovo PDF");
        foundChannel.enableVibration(true);
        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build();
        foundChannel.setSound(sound, audioAttributes);
        manager.createNotificationChannel(foundChannel);
    }

    private void notifyPdfFound(int slot, String fileName) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) return;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                100 + slot,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL_ID_FOUND)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("PDF " + slot + " trovato e scaricato • tocca per inoltrare")
                .setContentText(fileName)
                .setContentIntent(pending)
                .setAutoCancel(true)
                .build();
        manager.notify(FOUND_NOTIFICATION_BASE_ID + slot, notification);
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID_NOTIFICATION)
                .setSmallIcon(R.drawable.ic_app)
                .setContentTitle("Telegram PDF Share")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private static final class SearchCycle {
        String excludeFilter = "";
        final String[] filters = new String[7];
        final boolean[] found = new boolean[7];
        final Set<Long> usedMessageIds = new HashSet<>();
    }

    private static final class PdfCandidate {
        final long messageId;
        final int date;
        final int fileId;
        final long size;
        final String fileName;
        final String caption;

        PdfCandidate(long messageId, int date, int fileId, long size, String fileName, String caption) {
            this.messageId = messageId;
            this.date = date;
            this.fileId = fileId;
            this.size = size;
            this.fileName = fileName;
            this.caption = caption;
        }
    }
}
