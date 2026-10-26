package com.ts18.cardiag;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Black-box recorder: settings/property changes, broadcasts, filtered logcat, and parking location. */
public class RecorderService extends Service {
    public static volatile boolean running = false;
    static final String[] LOG_KW = {"door", "canbox", "canbus", "carinfo", "mcu", "reverse", "brake", "gear", "ldfy",
            "hiworld", "twutil", "acc on", "acc off", "accon", "accoff", "ignition", "dofun"};

    private BroadcastReceiver receiver;
    private ContentObserver obs;
    private HandlerThread ht;
    private volatile boolean stopFlag = false;
    private Process logcatProc;
    private LocationManager lm;
    private LocationListener ll;
    private volatile Location lastLoc;
    private long lastPersist = 0;
    private final Map<String, String> last = new ConcurrentHashMap<>();
    private final Map<String, Integer> counts = new ConcurrentHashMap<>();

    private boolean granted(String perm) {
        return getPackageManager().checkPermission(perm, getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        stopFlag = false;
        BlackBox.add(this, "RecorderService started");
        startReceivers();
        startSettingsWatch();
        startLogcat();
        startLocation();
    }

    private void goForeground() {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.createNotificationChannel(new NotificationChannel("rec", "CarDiag recorder", NotificationManager.IMPORTANCE_MIN));
                Notification n = new Notification.Builder(this, "rec")
                        .setContentTitle("TS18 CarDiag")
                        .setContentText("الصندوق الأسود شغال")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .build();
                startForeground(3, n);
            } catch (Exception ignored) { }
        }
    }

    private void startReceivers() {
        new Thread(new Runnable() {
            @Override public void run() {
                Set<String> acts = Probe.collectActions(RecorderService.this);
                String[] std = {Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT,
                        Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
                        "android.intent.action.ACTION_SHUTDOWN", "android.intent.action.QUICKBOOT_POWERON",
                        "android.intent.action.QUICKBOOT_POWEROFF", "android.intent.action.DREAMING_STARTED",
                        "android.intent.action.DREAMING_STOPPED"};
                for (String a : std) acts.add(a);
                IntentFilter f = new IntentFilter();
                for (String a : acts) {
                    try { f.addAction(a); } catch (Exception ignored) { }
                }
                if (stopFlag) return;
                receiver = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        String a = i == null ? "" : String.valueOf(i.getAction());
                        StringBuilder sb = new StringBuilder();
                        Bundle b = i == null ? null : i.getExtras();
                        if (b != null) {
                            for (String k : b.keySet()) sb.append(k).append("=").append(String.valueOf(b.get(k))).append(" ");
                        }
                        BlackBox.add(c, "BROADCAST " + a + " " + Sys.trunc(sb.toString(), 160));
                        if (a.equals(Intent.ACTION_SCREEN_OFF) || a.equals("android.intent.action.ACTION_SHUTDOWN")
                                || a.equals("android.intent.action.QUICKBOOT_POWEROFF") || a.equals(Intent.ACTION_POWER_DISCONNECTED)) {
                            saveParking(a);
                        }
                    }
                };
                try {
                    registerReceiver(receiver, f);
                    BlackBox.add(RecorderService.this, "listening to " + acts.size() + " broadcast actions");
                } catch (Exception e) {
                    BlackBox.add(RecorderService.this, "registerReceiver failed: " + e);
                }
            }
        }).start();
    }

    private void logChange(String key, String oldV, String newV) {
        if (Sys.isNoise(key)) return;
        Integer pc = counts.get(key);
        int n = pc == null ? 1 : pc + 1;
        counts.put(key, n);
        if (n > 20) return;
        BlackBox.add(this, (Sys.isHot(key) ? "★ " : "") + "CHANGE " + key + ": " + Sys.trunc(oldV, 80) + " -> " + Sys.trunc(newV, 80));
    }

    private void startSettingsWatch() {
        new Thread(new Runnable() {
            @Override public void run() {
                last.putAll(Sys.snapshot(RecorderService.this, true));
                if (stopFlag) return;
                ht = new HandlerThread("rec-observer");
                ht.start();
                obs = new ContentObserver(new Handler(ht.getLooper())) {
                    @Override public void onChange(boolean selfChange, Uri uri) {
                        if (uri == null) return;
                        List<String> seg = uri.getPathSegments();
                        if (seg.size() < 2) return;
                        String t = seg.get(0);
                        String prefix = t.equals("system") ? "System:" : (t.equals("secure") ? "Secure:" : "Global:");
                        String key = prefix + seg.get(1);
                        String val = Sys.querySetting(getContentResolver(), uri);
                        String old = last.get(key);
                        last.put(key, val == null ? "" : val);
                        if (old == null || !old.equals(val)) logChange(key, old, val);
                    }
                };
                getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, obs);
                getContentResolver().registerContentObserver(Settings.Secure.CONTENT_URI, true, obs);
                getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI, true, obs);
                while (!stopFlag) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                    for (Map.Entry<String, String> e : Sys.getprops().entrySet()) {
                        String key = "prop:" + e.getKey();
                        String old = last.get(key);
                        if (old == null || !old.equals(e.getValue())) {
                            last.put(key, e.getValue());
                            logChange(key, old, e.getValue());
                        }
                    }
                }
            }
        }).start();
    }

    private void startLogcat() {
        if (!granted("android.permission.READ_LOGS")) {
            BlackBox.add(this, "logcat: READ_LOGS not granted (use adb pm grant)");
            return;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    logcatProc = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "time", "-T", "1"});
                    BufferedReader r = new BufferedReader(new InputStreamReader(logcatProc.getInputStream()));
                    String l;
                    long sec = 0;
                    int cnt = 0;
                    while (!stopFlag && (l = r.readLine()) != null) {
                        if (!Sys.hasAny(l, LOG_KW)) continue;
                        long now = System.currentTimeMillis() / 1000;
                        if (now != sec) { sec = now; cnt = 0; }
                        if (++cnt > 30) continue;
                        BlackBox.add(RecorderService.this, "LOG " + Sys.trunc(l, 220));
                    }
                    r.close();
                } catch (Exception e) {
                    BlackBox.add(RecorderService.this, "logcat stopped: " + e);
                }
            }
        }).start();
    }

    private void startLocation() {
        if (!granted("android.permission.ACCESS_FINE_LOCATION")) {
            BlackBox.add(this, "location: permission not granted");
            return;
        }
        try {
            lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            ll = new LocationListener() {
                @Override public void onLocationChanged(Location l) {
                    lastLoc = l;
                    long now = System.currentTimeMillis();
                    if (now - lastPersist > 20000) {
                        lastPersist = now;
                        getSharedPreferences("park", MODE_PRIVATE).edit()
                                .putString("fix_lat", String.valueOf(l.getLatitude()))
                                .putString("fix_lon", String.valueOf(l.getLongitude()))
                                .putFloat("fix_acc", l.getAccuracy())
                                .putLong("fix_time", now)
                                .apply();
                    }
                }
                @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
            };
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000, 5, ll, Looper.getMainLooper());
            BlackBox.add(this, "location updates started");
        } catch (Exception e) {
            BlackBox.add(this, "location failed: " + e);
        }
    }

    private void saveParking(String reason) {
        try {
            SharedPreferences p = getSharedPreferences("park", MODE_PRIVATE);
            Location l = lastLoc;
            String lat;
            String lon;
            float acc;
            if (l != null) {
                if (l.hasSpeed() && l.getSpeed() > 2f) {
                    BlackBox.add(this, "PARK skipped (moving, " + reason + ")");
                    return;
                }
                lat = String.valueOf(l.getLatitude());
                lon = String.valueOf(l.getLongitude());
                acc = l.getAccuracy();
            } else if (p.contains("fix_lat")) {
                lat = p.getString("fix_lat", "0");
                lon = p.getString("fix_lon", "0");
                acc = p.getFloat("fix_acc", 0f);
            } else {
                BlackBox.add(this, "PARK: no location known (" + reason + ")");
                return;
            }
            p.edit().putString("park_lat", lat).putString("park_lon", lon).putFloat("park_acc", acc)
                    .putLong("park_time", System.currentTimeMillis()).putString("park_reason", reason).apply();
            BlackBox.add(this, "PARK saved " + lat + "," + lon + " (" + reason + ")");
        } catch (Exception e) {
            BlackBox.add(this, "PARK failed: " + e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        stopFlag = true;
        BlackBox.add(this, "RecorderService stopped");
        try { if (receiver != null) unregisterReceiver(receiver); } catch (Exception ignored) { }
        try { if (obs != null) getContentResolver().unregisterContentObserver(obs); } catch (Exception ignored) { }
        if (ht != null) ht.quit();
        try { if (logcatProc != null) logcatProc.destroy(); } catch (Exception ignored) { }
        try { if (lm != null && ll != null) lm.removeUpdates(ll); } catch (Exception ignored) { }
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
