package com.ts18.cardiag;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Advanced tools menu: black box, key monitor, logcat/dumpsys, Topway classes, snapshots, trip screen. */
public class Advanced {
    private final MainActivity a;
    boolean pendingRecorder = false;
    Runnable pendingAction = null;

    Advanced(MainActivity a) { this.a = a; }

    private boolean granted(String perm) {
        return a.getPackageManager().checkPermission(perm, a.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    private SharedPreferences prefs() { return a.getSharedPreferences("cardiag", Context.MODE_PRIVATE); }

    private boolean recorderOn() { return prefs().getBoolean("recorder_enabled", false); }

    private String stamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); }

    private File outDir() {
        File d = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TS18-CarDiag");
        if (!d.exists()) d.mkdirs();
        if (d.canWrite()) return d;
        File alt = a.getExternalFilesDir(null);
        return alt != null ? alt : a.getFilesDir();
    }

    private void writeText(File f, String content) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write(content);
        w.close();
    }

    void ensureStorage(Runnable r) {
        if (Build.VERSION.SDK_INT >= 23 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            pendingAction = r;
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE}, 7);
            return;
        }
        r.run();
    }

    void onPermissionResult(int code) {
        if (code == 8 && pendingRecorder) {
            pendingRecorder = false;
            startRecorder();
        }
        if (code == 7 && pendingAction != null) {
            Runnable r = pendingAction;
            pendingAction = null;
            r.run();
        }
    }

    void menu() {
        String[] items = {
                recorderOn() ? "الصندوق الأسود: إيقاف" : "الصندوق الأسود: تشغيل",
                "عرض الصندوق الأسود (آخر 150 سطر)",
                "حفظ الصندوق الأسود كملف",
                "مسح الصندوق الأسود",
                "تفعيل مراقب الأزرار والنوافذ",
                "قراءة logcat/dumpsys (يحتاج ADB مرة)",
                "فحص مكتبات وكلاسات Topway",
                "حفظ لقطة كاملة للنظام",
                "مقارنة لقطتين",
                "عداد السرعة والرحلة والموقف"};
        new AlertDialog.Builder(a)
                .setTitle("أدوات متقدمة")
                .setItems(items, (d, w) -> {
                    switch (w) {
                        case 0: toggleRecorder(); break;
                        case 1: showBlackBox(); break;
                        case 2: saveBlackBox(); break;
                        case 3: clearBlackBox(); break;
                        case 4: enableKeyMonitor(); break;
                        case 5: logcatRead(); break;
                        case 6: topwayClasses(); break;
                        case 7: snapshotSave(); break;
                        case 8: compare(); break;
                        case 9: a.startActivity(new Intent(a, TripActivity.class)); break;
                        default: break;
                    }
                })
                .show();
    }

    // ---- black box ----
    private void toggleRecorder() {
        if (recorderOn()) {
            prefs().edit().putBoolean("recorder_enabled", false).apply();
            a.stopService(new Intent(a, RecorderService.class));
            a.append("الصندوق الأسود اتوقف.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            pendingRecorder = true;
            a.requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 8);
            return;
        }
        startRecorder();
    }

    private void startRecorder() {
        prefs().edit().putBoolean("recorder_enabled", true).apply();
        try {
            Intent i = new Intent(a, RecorderService.class);
            if (Build.VERSION.SDK_INT >= 26) a.startForegroundService(i); else a.startService(i);
            a.append("الصندوق الأسود اشتغل. بيسجل التغييرات والإشعارات والموقف، وبيرجع يشتغل لوحده بعد الإقلاع.");
            a.append("استخدمه عادي، وبعدين افتح 'عرض الصندوق الأسود'.");
        } catch (Exception e) {
            a.append("فشل التشغيل: " + e);
        }
    }

    private void showBlackBox() {
        a.append("=== الصندوق الأسود (" + (RecorderService.running ? "شغال" : "واقف") + ") — آخر 150 سطر ===\n" + BlackBox.tail(a, 150));
    }

    private void saveBlackBox() {
        ensureStorage(() -> {
            try {
                File f = new File(outDir(), "blackbox_" + stamp() + ".txt");
                writeText(f, BlackBox.tail(a, 100000));
                MediaScannerConnection.scanFile(a, new String[]{f.getAbsolutePath()}, null, null);
                a.append("تم حفظ الصندوق الأسود: " + f.getAbsolutePath());
            } catch (Exception e) {
                a.append("فشل الحفظ: " + e);
            }
        });
    }

    private void clearBlackBox() {
        BlackBox.clear(a);
        a.append("اتمسح الصندوق الأسود.");
    }

    // ---- key / window monitor ----
    private void enableKeyMonitor() {
        String cn = a.getPackageName() + "/" + KeyLogService.class.getName();
        if (granted("android.permission.WRITE_SECURE_SETTINGS")) {
            try {
                String cur = Settings.Secure.getString(a.getContentResolver(), "enabled_accessibility_services");
                String nv = (cur == null || cur.length() == 0) ? cn : (cur.contains(cn) ? cur : cur + ":" + cn);
                Settings.Secure.putString(a.getContentResolver(), "enabled_accessibility_services", nv);
                Settings.Secure.putString(a.getContentResolver(), "accessibility_enabled", "1");
                a.append("تم تفعيل مراقب الأزرار والنوافذ. هيسجل في الصندوق الأسود (KEY و WINDOW).");
                return;
            } catch (Exception e) {
                a.append("فشل التفعيل المباشر: " + e);
            }
        }
        a.append("فعّل خدمة 'TS18 CarDiag' يدوياً من إعدادات الوصول (Accessibility).");
        a.append("لو القايمة دي مش موجودة في الشاشة: من ADB نفّذ مرة واحدة: adb shell pm grant com.ts18.cardiag android.permission.WRITE_SECURE_SETTINGS ثم اضغط الزرار ده تاني.");
        try {
            a.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (Exception e) {
            a.append("الشاشة مفيهاش شاشة إعدادات الوصول.");
        }
    }

    // ---- logcat / dumpsys ----
    private List<String> exec(String[] cmd, int max) {
        List<String> res = new ArrayList<>();
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) {
                res.add(l);
                if (res.size() >= max) break;
            }
            r.close();
            p.destroy();
        } catch (Exception e) {
            res.add("خطأ: " + e);
        }
        return res;
    }

    private void logcatRead() {
        a.append("=== logcat / dumpsys ===");
        new Thread(new Runnable() {
            @Override public void run() {
                if (!granted("android.permission.READ_LOGS")) {
                    a.append("READ_LOGS مش ممنوحة. من كمبيوتر (ADB) نفّذ مرة واحدة:");
                    a.append("adb shell pm grant com.ts18.cardiag android.permission.READ_LOGS");
                    a.append("adb shell pm grant com.ts18.cardiag android.permission.DUMP");
                    a.append("adb shell pm grant com.ts18.cardiag android.permission.WRITE_SECURE_SETTINGS");
                    a.append("وبعدها اقفل التطبيق وافتحه تاني (وشغّل الصندوق الأسود من جديد).");
                } else {
                    List<String> lines = exec(new String[]{"logcat", "-d", "-v", "time", "-t", "6000"}, 6000);
                    int shown = 0;
                    for (String l : lines) {
                        if (Sys.hasAny(l, RecorderService.LOG_KW)) {
                            a.append(l);
                            if (++shown >= 150) { a.append("..."); break; }
                        }
                    }
                    a.append("(فُحص " + lines.size() + " سطر، المطابق: " + shown + ")");
                }
                if (!granted("android.permission.DUMP")) {
                    a.append("DUMP مش ممنوحة — dumpsys مش متاح.");
                } else {
                    List<String> d = exec(new String[]{"dumpsys", "activity", "activities"}, 20000);
                    int n = 0;
                    for (String l : d) {
                        if (l.contains("ResumedActivity") || l.contains("Recents") || l.contains("mFocusedApp")) {
                            a.append(l.trim());
                            if (++n >= 25) break;
                        }
                    }
                    if (n == 0) a.append("dumpsys: ما لقيتش سطور مطابقة.");
                }
            }
        }).start();
    }

    // ---- Topway classes ----
    private void topwayClasses() {
        a.append("=== فحص مكتبات وكلاسات Topway ===");
        new Thread(new Runnable() {
            @Override public void run() {
                Probe.classProbe(a, a.probeOut());
            }
        }).start();
    }

    // ---- snapshots ----
    private void snapshotSave() {
        ensureStorage(() -> new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Map<String, String> m = new TreeMap<>(Sys.snapshot(a, true));
                    File f = new File(outDir(), "snapshot_" + Build.MODEL.replaceAll("[^A-Za-z0-9_-]", "_") + "_" + stamp() + ".txt");
                    StringBuilder sb = new StringBuilder("# TS18 snapshot " + new Date() + "\n");
                    for (Map.Entry<String, String> e : m.entrySet()) {
                        sb.append(e.getKey()).append("=").append(e.getValue().replace("\n", "\\n")).append("\n");
                    }
                    writeText(f, sb.toString());
                    MediaScannerConnection.scanFile(a, new String[]{f.getAbsolutePath()}, null, null);
                    a.append("تم حفظ اللقطة (" + m.size() + " مفتاح): " + f.getAbsolutePath());
                } catch (Exception e) {
                    a.append("فشل حفظ اللقطة: " + e);
                }
            }
        }).start());
    }

    private Map<String, String> parse(File f) throws Exception {
        Map<String, String> m = new HashMap<>();
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        String l;
        while ((l = r.readLine()) != null) {
            if (l.startsWith("#")) continue;
            int i = l.indexOf('=');
            if (i > 0) m.put(l.substring(0, i), l.substring(i + 1));
        }
        r.close();
        return m;
    }

    private void compare() {
        ensureStorage(() -> {
            File[] fs = outDir().listFiles(new java.io.FilenameFilter() {
                @Override public boolean accept(File dir, String name) {
                    return name.startsWith("snapshot_") && name.endsWith(".txt");
                }
            });
            if (fs == null || fs.length < 2) {
                a.append("محتاج لقطتين محفوظتين على الأقل (حفظ لقطة كاملة للنظام).");
                return;
            }
            Arrays.sort(fs, (x, y) -> Long.compare(y.lastModified(), x.lastModified()));
            final File[] list = fs;
            final String[] names = new String[list.length];
            for (int i = 0; i < names.length; i++) names[i] = list[i].getName();
            new AlertDialog.Builder(a)
                    .setTitle("اختار اللقطة القديمة (الأولى)")
                    .setItems(names, (d1, w1) -> {
                        final File first = list[w1];
                        new AlertDialog.Builder(a)
                                .setTitle("اختار اللقطة الجديدة (التانية)")
                                .setItems(names, (d2, w2) -> diff(first, list[w2]))
                                .show();
                    })
                    .show();
        });
    }

    private void diff(final File fa, final File fb) {
        a.append("=== مقارنة: " + fa.getName() + "  ->  " + fb.getName() + " ===");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Map<String, String> ma = parse(fa);
                    Map<String, String> mb = parse(fb);
                    List<String> changed = new ArrayList<>();
                    List<String> added = new ArrayList<>();
                    List<String> removed = new ArrayList<>();
                    int noise = 0;
                    for (Map.Entry<String, String> e : mb.entrySet()) {
                        String k = e.getKey();
                        if (Sys.isNoise(k)) { noise++; continue; }
                        String old = ma.get(k);
                        if (old == null) added.add("+ " + k + " = " + Sys.trunc(e.getValue(), 80));
                        else if (!old.equals(e.getValue())) changed.add("~ " + k + ": " + Sys.trunc(old, 60) + " -> " + Sys.trunc(e.getValue(), 60));
                    }
                    for (String k : ma.keySet()) {
                        if (Sys.isNoise(k)) continue;
                        if (!mb.containsKey(k)) removed.add("- " + k + " = " + Sys.trunc(ma.get(k), 80));
                    }
                    Collections.sort(changed);
                    Collections.sort(added);
                    Collections.sort(removed);
                    a.append("اتغيّر: " + changed.size() + " | اتضاف: " + added.size() + " | اتشال: " + removed.size() + " | (ضجيج متجاهل: " + noise + ")");
                    int n = 0;
                    for (String s : changed) { a.append(s); if (++n >= 120) break; }
                    n = 0;
                    for (String s : added) { a.append(s); if (++n >= 60) break; }
                    n = 0;
                    for (String s : removed) { a.append(s); if (++n >= 60) break; }
                    a.append("انتهت المقارنة.");
                } catch (Exception e) {
                    a.append("فشلت المقارنة: " + e);
                }
            }
        }).start();
    }
}
