package it.paolo.telegrampdfshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class SearchAlarmReceiver extends BroadcastReceiver {

    static final String ACTION_DAILY_START = "it.paolo.telegrampdfshare.DAILY_SEARCH_START";
    static final String ACTION_WATCHDOG = "it.paolo.telegrampdfshare.SEARCH_WATCHDOG";
    private static final String PREFS = "telegram_pdf_share_prefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("daily_search_enabled", false)) return;

        String action = intent == null ? "" : intent.getAction();
        if (ACTION_DAILY_START.equals(action)) {
            SearchScheduler.scheduleNext(context);
            startSearch(context, prefs, "orario programmato");
            return;
        }
        if (ACTION_WATCHDOG.equals(action)) {
            SearchScheduler.scheduleWatchdog(context);
            if (shouldRecoverNow(prefs)) startSearch(context, prefs, "recupero automatico");
        }
    }

    private boolean shouldRecoverNow(SharedPreferences prefs) {
        int hour = prefs.getInt("search_start_hour", -1);
        int minute = prefs.getInt("search_start_minute", -1);
        if (hour < 0 || minute < 0) return false;
        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinutes = hour * 60 + minute;
        if (nowMinutes < startMinutes) return false;
        int endHour = prefs.getInt("search_end_hour", -1);
        int endMinute = prefs.getInt("search_end_minute", -1);
        if (endHour >= 0 && endMinute >= 0) {
            int endMinutes = endHour * 60 + endMinute;
            if (nowMinutes >= endMinutes) return false;
        }
        int found = 0;
        int configured = 0;
        String today = new SimpleDateFormat("yyyyMMdd", Locale.ITALY).format(new Date());
        for (int slot = 1; slot <= MainActivity.SLOT_COUNT; slot++) {
            if (prefs.getString("search_filter_" + slot, "").trim().isEmpty()) continue;
            configured++;
            boolean foundToday = prefs.getBoolean("search_found_" + slot, false)
                    && today.equals(prefs.getString("search_result_day_key_" + slot, ""));
            boolean handledToday = today.equals(prefs.getString("search_handled_day_key_" + slot, ""));
            if (foundToday || handledToday) found++;
        }
        if (configured == 0 || found >= configured) return false;
        String lastDay = prefs.getString("last_auto_search_day", "");
        if (!today.equals(lastDay)) return true;
        long last = prefs.getLong("last_auto_search_at", 0L);
        int retry = Math.max(1, prefs.getInt("retry_minutes", 15));
        long staleAfter = (retry + 5L) * 60L * 1000L;
        return last <= 0L || System.currentTimeMillis() - last >= staleAfter;
    }

    private void startSearch(Context context, SharedPreferences prefs, String reason) {
        prefs.edit()
                .putBoolean("auto_search_active", true)
                .putLong("next_search_at", 0L)
                .putString("last_auto_start_reason", reason)
                .apply();
        Intent service = new Intent(context, PdfSearchService.class).setAction(PdfSearchService.ACTION_START);
        context.startForegroundService(service);
    }
}
