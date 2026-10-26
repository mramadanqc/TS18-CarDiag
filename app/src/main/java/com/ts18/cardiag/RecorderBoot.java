package com.ts18.cardiag;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class RecorderBoot extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        boolean on = context.getSharedPreferences("cardiag", Context.MODE_PRIVATE).getBoolean("recorder_enabled", false);
        BlackBox.add(context, "RecorderBoot: " + (intent == null ? "null" : intent.getAction()) + " enabled=" + on);
        if (!on) return;
        try {
            Intent i = new Intent(context, RecorderService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i);
            else context.startService(i);
        } catch (Exception e) {
            BlackBox.add(context, "RecorderBoot: failed " + e);
        }
    }
}
