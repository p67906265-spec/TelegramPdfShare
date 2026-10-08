package it.paolo.telegrampdfshare;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

public class BootReceiver extends BroadcastReceiver {

    private static final String PREFS = "telegram_pdf_share_prefs";

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (prefs.getBoolean("daily_search_enabled", false)) {
            SearchScheduler.scheduleNext(context);
        }
    }
}
