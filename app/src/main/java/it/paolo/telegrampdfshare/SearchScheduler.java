package it.paolo.telegrampdfshare;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.util.Calendar;

final class SearchScheduler {

    private static final String PREFS = "telegram_pdf_share_prefs";
    private static final int REQUEST_CODE_DAILY = 6041;
    private static final int REQUEST_CODE_WATCHDOG = 6042;
    private static final long WATCHDOG_INTERVAL_MS = 15L * 60L * 1000L;

    private SearchScheduler() {}

    static long scheduleNext(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean("daily_search_enabled", false)) {
            cancel(context);
            return 0L;
        }
        scheduleWatchdog(context);

        int hour = prefs.getInt("search_start_hour", -1);
        int minute = prefs.getInt("search_start_minute", -1);
        if (hour < 0 || minute < 0) {
            cancelDaily(context);
            return 0L;
        }

        Calendar now = Calendar.getInstance();
        Calendar next = Calendar.getInstance();
        next.set(Calendar.HOUR_OF_DAY, hour);
        next.set(Calendar.MINUTE, minute);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        if (!next.after(now)) next.add(Calendar.DAY_OF_YEAR, 1);

        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return 0L;
        long triggerAt = next.getTimeInMillis();
        PendingIntent pi = dailyAlarmIntent(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            prefs.edit().putLong("scheduled_start_at", 0L).apply();
            return 0L;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi);
        }
        prefs.edit().putLong("scheduled_start_at", triggerAt).apply();
        return triggerAt;
    }

    static void scheduleWatchdog(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return;
        PendingIntent pi = watchdogIntent(context);
        alarmManager.cancel(pi);
        if (!prefs.getBoolean("daily_search_enabled", false)) {
            prefs.edit().putLong("watchdog_next_at", 0L).apply();
            return;
        }

        int startHour = prefs.getInt("search_start_hour", -1);
        int startMinute = prefs.getInt("search_start_minute", -1);
        int endHour = prefs.getInt("search_end_hour", -1);
        int endMinute = prefs.getInt("search_end_minute", -1);
        if (startHour < 0 || startMinute < 0 || endHour < 0 || endMinute < 0) {
            prefs.edit().putLong("watchdog_next_at", 0L).apply();
            return;
        }

        Calendar now = Calendar.getInstance();
        int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        int startMinutes = startHour * 60 + startMinute;
        int endMinutes = endHour * 60 + endMinute;
        Calendar next = Calendar.getInstance();

        if (nowMinutes < startMinutes) {
            next.set(Calendar.HOUR_OF_DAY, startHour);
            next.set(Calendar.MINUTE, startMinute);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            next.add(Calendar.MINUTE, 15);
        } else if (nowMinutes >= endMinutes) {
            next.add(Calendar.DAY_OF_YEAR, 1);
            next.set(Calendar.HOUR_OF_DAY, startHour);
            next.set(Calendar.MINUTE, startMinute);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            next.add(Calendar.MINUTE, 15);
        } else {
            next.setTimeInMillis(System.currentTimeMillis() + WATCHDOG_INTERVAL_MS);
            int nextMinutes = next.get(Calendar.HOUR_OF_DAY) * 60 + next.get(Calendar.MINUTE);
            if (nextMinutes >= endMinutes) {
                next.add(Calendar.DAY_OF_YEAR, 1);
                next.set(Calendar.HOUR_OF_DAY, startHour);
                next.set(Calendar.MINUTE, startMinute);
                next.set(Calendar.SECOND, 0);
                next.set(Calendar.MILLISECOND, 0);
                next.add(Calendar.MINUTE, 15);
            }
        }

        long first = next.getTimeInMillis();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, first, pi);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, first, pi);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, first, pi);
        }
        prefs.edit().putLong("watchdog_next_at", first).apply();
    }

    static boolean canScheduleExact(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) return false;
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms();
    }

    static void cancel(Context context) {
        cancelDaily(context);
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(watchdogIntent(context));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("scheduled_start_at", 0L)
                .putLong("watchdog_next_at", 0L)
                .apply();
    }

    private static void cancelDaily(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) alarmManager.cancel(dailyAlarmIntent(context));
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("scheduled_start_at", 0L).apply();
    }

    private static PendingIntent dailyAlarmIntent(Context context) {
        Intent intent = new Intent(context, SearchAlarmReceiver.class).setAction(SearchAlarmReceiver.ACTION_DAILY_START);
        return PendingIntent.getBroadcast(context, REQUEST_CODE_DAILY, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent watchdogIntent(Context context) {
        Intent intent = new Intent(context, SearchAlarmReceiver.class).setAction(SearchAlarmReceiver.ACTION_WATCHDOG);
        return PendingIntent.getBroadcast(context, REQUEST_CODE_WATCHDOG, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
