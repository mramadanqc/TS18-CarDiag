package com.ts18.cardiag;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.media.MediaScannerConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Environment;
import android.provider.Settings;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

public class MainActivity extends Activity {
    private TextView banner, out;
    private ScrollView outScroll;
    private EditText filterField;
    private Button watchBtn;
    private final StringBuilder report = new StringBuilder();
    private volatile boolean watching = false;
    private ElmCan elm;
    private boolean pendingSave = false;
    private Advanced adv;
    private volatile boolean testRunning = false;
    private BroadcastReceiver bRecv;
    private boolean bOn = false;
    private Button bBtn;
    private final Map<String, String> lastVals = new ConcurrentHashMap<>();
    private final Map<String, Integer> changeCount = new ConcurrentHashMap<>();
    private ContentObserver settingsObserver;
    private HandlerThread obsThread;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private Button btn(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setOnClickListener(l);
        return b;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        final int MP = LinearLayout.LayoutParams.MATCH_PARENT;
        final int WC = LinearLayout.LayoutParams.WRAP_CONTENT;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        ScrollView leftScroll = new ScrollView(this);
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(4), 0, dp(10), 0);
        leftScroll.addView(left);
        root.addView(leftScroll, new LinearLayout.LayoutParams(0, MP, 3f));

        TextView title = new TextView(this);
        title.setText("TS18 CarDiag");
        title.setTextSize(20);
        left.addView(title);

        left.addView(btn("1) معلومات الشاشة والـ firmware", v -> systemReport()));
        watchBtn = btn("2) مراقب التغيّرات: ابدأ", v -> toggleWatch());
        left.addView(watchBtn);
        left.addView(btn("3) اختبار الأبواب (بدون ELM)", v -> doorTest()));
        left.addView(btn("4) تحليل تطبيقات Topway", v -> analyzeTopway()));
        left.addView(btn("5) مين بيكتب SYSTEM_DOOR؟", v -> whoWrites()));
        bBtn = btn("6) مراقب الإشعارات: ابدأ", v -> toggleBroadcast());
        left.addView(bBtn);
        adv = new Advanced(this);
        left.addView(btn("أدوات متقدمة…", v -> adv.menu()));

        TextView fl = new TextView(this);
        fl.setText("فلتر ID لفحص CAN (اختياري):");
        fl.setTextSize(13);
        left.addView(fl);
        filterField = new EditText(this);
        filterField.setSingleLine(true);
        filterField.setHint("مثال: 3A2");
        filterField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        left.addView(filterField);
        left.addView(btn("7) فحص CAN عبر ELM327 (يحتاج جهاز)", v -> chooseElm()));
        left.addView(btn("إيقاف", v -> stopAll()));
        left.addView(btn("نسخ التقرير", v -> copyReport()));
        left.addView(btn("مشاركة التقرير", v -> shareReport()));
        left.addView(btn("حفظ التقرير كملف", v -> saveReport()));
        left.addView(btn("مسح الشاشة", v -> { report.setLength(0); out.setText(""); }));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        root.addView(right, new LinearLayout.LayoutParams(0, MP, 7f));

        banner = new TextView(this);
        banner.setTextSize(24);
        banner.setTypeface(Typeface.DEFAULT_BOLD);
        banner.setPadding(dp(6), dp(4), dp(6), dp(8));
        banner.setText("اختار فحص من القايمة");
        right.addView(banner);

        outScroll = new ScrollView(this);
        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextSize(12);
        out.setTextIsSelectable(true);
        outScroll.addView(out);
        right.addView(outScroll, new LinearLayout.LayoutParams(MP, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        watching = false;
        if (elm != null) { elm.stop(); elm.close(); }
        super.onDestroy();
    }

    void append(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                report.append(s).append("\n");
                if (report.length() > 80000) report.delete(0, 20000);
                out.setText(report.toString());
                outScroll.post(new Runnable() {
                    @Override public void run() { outScroll.fullScroll(View.FOCUS_DOWN); }
                });
            }
        });
    }

    void setBanner(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { banner.setText(s); }
        });
    }

    private void stopAll() {
        watching = false;
        watchBtn.setText("2) مراقب التغيّرات: ابدأ");
        if (elm != null) elm.stop();
        testRunning = false;
        Probe.cancel = true;
        setBanner("اتوقف");
    }

    // ---------- save report as a file ----------
    private void saveReport() {
        if (report.length() == 0) {
            Toast.makeText(this, "مفيش تقرير لسه", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingSave = true;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 7);
            return;
        }
        doSave();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7 && pendingSave) {
            pendingSave = false;
            doSave();
        }
        if (adv != null) adv.onPermissionResult(requestCode);
    }

    private void doSave() {
        String screen = ("TS18-" + Build.MODEL).replaceAll("[^A-Za-z0-9_-]", "_");
        String fileName = screen + "_report_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        String content = "Screen: " + screen + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + "\nTime: " + new Date() + "\n\n" + report.toString();
        File saved = null;
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "TS18-CarDiag");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, fileName);
            writeFile(f, content);
            saved = f;
        } catch (Exception e) {
            try {
                File dir2 = getExternalFilesDir(null);
                if (dir2 != null) {
                    File f2 = new File(dir2, fileName);
                    writeFile(f2, content);
                    saved = f2;
                }
            } catch (Exception e2) {
                append("فشل الحفظ: " + e2);
            }
        }
        if (saved != null) {
            MediaScannerConnection.scanFile(this, new String[]{saved.getAbsolutePath()}, null, null);
            append("تم حفظ التقرير: " + saved.getAbsolutePath());
            Toast.makeText(this, "تم الحفظ: " + saved.getName(), Toast.LENGTH_LONG).show();
        }
    }

    private void writeFile(File f, String content) throws Exception {
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f), "UTF-8");
        w.write(content);
        w.close();
    }

    private void copyReport() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("report", report.toString()));
        Toast.makeText(this, "تم نسخ التقرير", Toast.LENGTH_SHORT).show();
    }

    private void shareReport() {
        try {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_TEXT, report.toString());
            startActivity(Intent.createChooser(i, "مشاركة التقرير"));
        } catch (Exception e) {
            Toast.makeText(this, "الشاشة مفيهاش تطبيق مشاركة، استخدم النسخ", Toast.LENGTH_LONG).show();
        }
    }

    // ---------- 2) system report ----------
    private static Map<String, String> getprops() {
        Map<String, String> m = new TreeMap<>();
        try {
            Process pr = Runtime.getRuntime().exec("getprop");
            BufferedReader r = new BufferedReader(new InputStreamReader(pr.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) {
                int i = l.indexOf("]: [");
                if (l.startsWith("[") && i > 0) {
                    String k = l.substring(1, i);
                    String v = l.substring(i + 4);
                    if (v.endsWith("]")) v = v.substring(0, v.length() - 1);
                    m.put(k, v);
                }
            }
            r.close();
        } catch (Exception ignored) { }
        return m;
    }

    private void readTable(Uri uri, String prefix, Map<String, String> m) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{"name", "value"}, null, null, null);
            if (c != null) {
                while (c.moveToNext()) {
                    String k = c.getString(0);
                    String v = c.getString(1);
                    m.put(prefix + k, v == null ? "" : v);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
    }

    private Map<String, String> snapshot(boolean withProps) {
        Map<String, String> m = new HashMap<>();
        readTable(Settings.System.CONTENT_URI, "System:", m);
        readTable(Settings.Secure.CONTENT_URI, "Secure:", m);
        readTable(Settings.Global.CONTENT_URI, "Global:", m);
        if (withProps) {
            for (Map.Entry<String, String> e : getprops().entrySet()) m.put("prop:" + e.getKey(), e.getValue());
        }
        return m;
    }

    private static String trunc(String s) {
        if (s == null) return "(لا يوجد)";
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static boolean hasAny(String s, String[] words) {
        String l = s.toLowerCase(Locale.US);
        for (String w : words) if (l.contains(w)) return true;
        return false;
    }

    private void systemReport() {
        setBanner("معلومات الشاشة والـ firmware");
        append("=== معلومات الشاشة والـ firmware (ثواني...) ===");
        new Thread(new Runnable() {
            @Override public void run() {
                StringBuilder sb = new StringBuilder();
                Map<String, String> props = getprops();
                String[] keys = {"ro.product.model", "ro.product.brand", "ro.product.manufacturer", "ro.build.display.id",
                        "ro.build.version.release", "ro.build.version.sdk", "ro.board.platform", "ro.hardware"};
                for (String k : keys) if (props.containsKey(k)) sb.append(k).append(" = ").append(props.get(k)).append("\n");

                String[] kwProps = {"canbus", "can_", "_can", ".can", "mcu", "topway", "ts18", "hiworld", "sleep", "ldfy",
                        "syu", "txz", "acc_", "_acc", ".acc", "door", "reverse", "gear"};
                sb.append("--- خصائص نظام ليها علاقة (CAN/MCU/ACC...) ---\n");
                int n = 0;
                for (Map.Entry<String, String> e : props.entrySet()) {
                    if (hasAny(e.getKey(), kwProps)) {
                        sb.append(e.getKey()).append(" = ").append(trunc(e.getValue())).append("\n");
                        if (++n >= 70) break;
                    }
                }
                if (n == 0) sb.append("(لا يوجد)\n");

                sb.append("--- تطبيقات ليها علاقة بالسيارة/الـ CAN ---\n");
                String[] kwPkg = {"canbus", "canbox", "carinfo", "mcu", "topway", "hiworld", "ts18", "txz", "syu", "fyt",
                        "autokit", "carservice", "carsetting", "vehicle"};
                PackageManager pm = getPackageManager();
                List<ApplicationInfo> apps = pm.getInstalledApplications(0);
                int k = 0;
                for (ApplicationInfo ai : apps) {
                    String label = String.valueOf(ai.loadLabel(pm));
                    if (hasAny(ai.packageName, kwPkg) || hasAny(label, new String[]{"canbox", "can box", "car info", "carinfo"})) {
                        sb.append(label).append(" (").append(ai.packageName).append(")")
                                .append((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? " [system]" : "").append("\n");
                        if (++k >= 60) break;
                    }
                }
                if (k == 0) sb.append("(لا يوجد)\n");

                sb.append("--- مفاتيح إعدادات النظام اللي ممكن تحمل بيانات السيارة ---\n");
                String[] kwSet = {"door", "can", "acc", "brake", "reverse", "gear", "park", "vehicle", "car_"};
                Map<String, String> st = snapshot(false);
                TreeMap<String, String> sorted = new TreeMap<>(st);
                int c = 0;
                for (Map.Entry<String, String> e : sorted.entrySet()) {
                    if (hasAny(e.getKey(), kwSet)) {
                        sb.append(e.getKey()).append(" = ").append(trunc(e.getValue())).append("\n");
                        if (++c >= 80) break;
                    }
                }
                if (c == 0) sb.append("(لا يوجد)\n");
                append(sb.toString());
                setBanner("انتهى تقرير المعلومات");
            }
        }).start();
    }

    // ---------- change watcher (instant Settings observer + property polling) ----------
    private static final String[] NOISE = {"phenotype", "backup_agent", "ble_scan", "wifi_scan", "boot_count", "uptime",
            "elapsed", "timestamp", "last_", "_time", "time_", "ntp", "gservices", "finsky", "vending", "bluetooth_address",
            "wifi_", "netstats", "cell_", "net.", "dhcp", "init.svc", "sys.sysctl", "ril.", "gsm.", "debug.", "dalvik",
            "persist.sys.timezone", "vendor.cam", "bootanim", "sys.boot", "sys.lmk", "mobile_data", "adb_", "vendor.sys",
            "media_button_receiver", "usage", "stats", "heartbeat", "clock"};
    private static final String[] HOT = {"door", "can", "acc", "brake", "reverse", "gear", "park", "light", "mcu", "ldfy",
            "speed", "ignit", "sleep", "tw"};

    private static boolean isNoise(String key) {
        return hasAny(key, NOISE);
    }

    private Map<String, String> cleanSnapshot() {
        Map<String, String> m = snapshot(true);
        for (Iterator<Map.Entry<String, String>> it = m.entrySet().iterator(); it.hasNext(); ) {
            if (isNoise(it.next().getKey())) it.remove();
        }
        return m;
    }

    private void reportChange(String key, String oldV, String newV) {
        if (isNoise(key)) return;
        Integer pc = changeCount.get(key);
        int n = pc == null ? 1 : pc + 1;
        changeCount.put(key, n);
        if (n > 8) {
            if (n == 9) append("(تجاهل مفتاح كتير التغيير: " + key + ")");
            return;
        }
        String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        append(t + (hasAny(key, HOT) ? " ★ " : " ") + key + ": " + trunc(oldV) + " -> " + trunc(newV));
    }

    private String querySetting(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{"value"}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String v = c.getString(0);
                return v == null ? "" : v;
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private void startObserver() {
        obsThread = new HandlerThread("settings-observer");
        obsThread.start();
        settingsObserver = new ContentObserver(new Handler(obsThread.getLooper())) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                if (uri == null) return;
                List<String> seg = uri.getPathSegments();
                if (seg.size() < 2) return;
                String table = seg.get(0);
                String prefix = table.equals("system") ? "System:" : (table.equals("secure") ? "Secure:" : "Global:");
                String key = prefix + seg.get(1);
                String val = querySetting(uri);
                String old = lastVals.get(key);
                lastVals.put(key, val == null ? "" : val);
                if (old == null || !old.equals(val)) reportChange(key, old, val);
            }
        };
        getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver);
        getContentResolver().registerContentObserver(Settings.Secure.CONTENT_URI, true, settingsObserver);
        getContentResolver().registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver);
    }

    private void stopObserver() {
        try {
            if (settingsObserver != null) getContentResolver().unregisterContentObserver(settingsObserver);
        } catch (Exception ignored) { }
        settingsObserver = null;
        if (obsThread != null) { obsThread.quit(); obsThread = null; }
    }

    private void toggleWatch() {
        if (watching) {
            watching = false;
            watchBtn.setText("2) مراقب التغيّرات: ابدأ");
            return;
        }
        watching = true;
        watchBtn.setText("2) مراقب التغيّرات: إيقاف");
        setBanner("المراقب شغال — افتح/اقفل باب أو حرّك الفتيس وشوف اللي بيتغير");
        new Thread(new Runnable() {
            @Override public void run() {
                lastVals.clear();
                changeCount.clear();
                lastVals.putAll(snapshot(true));
                append("لقطة أولية: " + lastVals.size() + " مفتاح. الإعدادات بتتراقب لحظياً، والخصائص كل ثانية. ★ = مفتاح مشبوه:");
                startObserver();
                while (watching) {
                    try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
                    Map<String, String> props = getprops();
                    for (Map.Entry<String, String> e : props.entrySet()) {
                        String key = "prop:" + e.getKey();
                        String old = lastVals.get(key);
                        if (old == null || !old.equals(e.getValue())) {
                            lastVals.put(key, e.getValue());
                            reportChange(key, old, e.getValue());
                        }
                    }
                }
                stopObserver();
                append("تم إيقاف المراقب.");
            }
        }).start();
    }

    // ---------- guided door test using Settings/properties only ----------
    private void beepTone() {
        try {
            new ToneGenerator(AudioManager.STREAM_MUSIC, 100).startTone(ToneGenerator.TONE_PROP_BEEP, 250);
        } catch (Exception ignored) { }
    }

    private void doorTest() {
        if (testRunning) { append("الاختبار شغال بالفعل."); return; }
        testRunning = true;
        append("=== اختبار الأبواب من الإعدادات والخصائص (بدون ELM) ===");
        append("شغّل الكونتاكت. اتبع التعليمات على الشاشة، وهيطلع صوت بيب عند كل خطوة.");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String[] steps = {"باب السواق", "باب الراكب الأمامي", "الباب الخلفي الشمال", "الباب الخلفي اليمين", "الشنطة"};
                    setBanner("اقفل كل الأبواب وماتلمسش حاجة");
                    beepTone();
                    Thread.sleep(4000);
                    beepTone();
                    Map<String, String> s0 = cleanSnapshot();
                    Map<String, Integer> hits = new HashMap<>();
                    for (int i = 0; i < steps.length && testRunning; i++) {
                        String st = steps[i];
                        setBanner("افتح " + st + " وسيبه مفتوح");
                        beepTone();
                        Thread.sleep(4500);
                        setBanner("ثبّت — " + st + " مفتوح (بيتسجل)");
                        beepTone();
                        Map<String, String> s1 = cleanSnapshot();
                        setBanner("اقفل " + st);
                        beepTone();
                        Thread.sleep(4500);
                        setBanner("ثبّت — " + st + " مقفول (بيتسجل)");
                        beepTone();
                        Map<String, String> s2 = cleanSnapshot();
                        List<String> cand = new ArrayList<>();
                        for (Map.Entry<String, String> e : s0.entrySet()) {
                            String k = e.getKey();
                            String a = e.getValue();
                            String b1 = s1.get(k);
                            String c2 = s2.get(k);
                            if (b1 != null && c2 != null && a.equals(c2) && !a.equals(b1)) {
                                cand.add(k + ": " + trunc(a) + " -> " + trunc(b1) + " -> " + trunc(c2));
                                Integer h = hits.get(k);
                                hits.put(k, h == null ? 1 : h + 1);
                            }
                        }
                        Collections.sort(cand);
                        if (cand.isEmpty()) {
                            append("[" + st + "] ما لقيتش أي مفتاح اتغيّر واترجع.");
                        } else {
                            append("[" + st + "] مرشحات (" + cand.size() + "):");
                            for (int j = 0; j < Math.min(15, cand.size()); j++) append("   " + cand.get(j));
                        }
                        s0 = s2;
                    }
                    append("--- الخلاصة ---");
                    if (hits.isEmpty()) {
                        append("ما فيش أي مفتاح في الإعدادات أو الخصائص بيتغيّر مع الأبواب. يعني الأبواب مش بتتنشر بالطريقة دي، أو الـ decoder مش بيوصلها للـ firmware.");
                    } else {
                        append("مفاتيح اتغيّرت مع أكتر من باب (الأقوى):");
                        for (Map.Entry<String, Integer> e : hits.entrySet()) {
                            if (e.getValue() >= 2) append("   " + e.getKey() + "  (مع " + e.getValue() + " خطوات)");
                        }
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    testRunning = false;
                    setBanner("انتهى اختبار الأبواب");
                }
            }
        }).start();
    }

    // ---------- Topway package probes ----------
    Probe.Out probeOut() {
        return new Probe.Out() {
            @Override public void line(String s) { append(s); }
            @Override public void banner(String s) { setBanner(s); }
        };
    }

    private void analyzeTopway() {
        append("=== تحليل تطبيقات Topway/Dofun (مكونات + إشعارات معلنة) ===");
        setBanner("بيحلل التطبيقات...");
        new Thread(new Runnable() {
            @Override public void run() {
                Probe.Out o = probeOut();
                List<ApplicationInfo> t = Probe.targets(MainActivity.this);
                if (t.isEmpty()) o.line("ما لقيتش تطبيقات مطابقة.");
                for (ApplicationInfo ai : t) {
                    Probe.components(MainActivity.this, ai, o);
                    if (ai.sourceDir != null) {
                        Set<String> acts = Probe.actionsOf(ai.sourceDir);
                        if (!acts.isEmpty()) {
                            o.line("  إشعارات/نصوص مشبوهة في الـ manifest (" + acts.size() + "):");
                            int n = 0;
                            for (String a : acts) {
                                o.line("    " + a);
                                if (++n >= 40) break;
                            }
                        }
                    }
                }
                o.banner("انتهى التحليل");
            }
        }).start();
    }

    private void whoWrites() {
        append("=== بيدوّر في كود التطبيقات على SYSTEM_DOOR و TWCameraBrake و persist.tw.reverse (ممكن ياخد دقيقة أو اتنين) ===");
        new Thread(new Runnable() {
            @Override public void run() {
                Probe.scanDex(MainActivity.this, new String[]{"SYSTEM_DOOR", "TWCameraBrake", "persist.tw.reverse"}, probeOut(), 180000);
            }
        }).start();
    }

    // ---------- broadcast listener ----------
    private void toggleBroadcast() {
        if (bOn) {
            try { unregisterReceiver(bRecv); } catch (Exception ignored) { }
            bOn = false;
            bBtn.setText("6) مراقب الإشعارات: ابدأ");
            append("تم إيقاف مراقب الإشعارات.");
            return;
        }
        append("=== مراقب الإشعارات: بيجمع أسماء الإشعارات من تطبيقات Topway... ===");
        new Thread(new Runnable() {
            @Override public void run() {
                final Set<String> acts = Probe.collectActions(MainActivity.this);
                String[] std = {Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF, Intent.ACTION_USER_PRESENT,
                        Intent.ACTION_POWER_CONNECTED, Intent.ACTION_POWER_DISCONNECTED,
                        "android.intent.action.ACTION_SHUTDOWN", "android.intent.action.QUICKBOOT_POWERON",
                        "android.intent.action.QUICKBOOT_POWEROFF"};
                for (String a : std) acts.add(a);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        IntentFilter f = new IntentFilter();
                        for (String a : acts) {
                            try { f.addAction(a); } catch (Exception ignored) { }
                        }
                        bRecv = new BroadcastReceiver() {
                            @Override public void onReceive(Context c, Intent i) {
                                StringBuilder sb = new StringBuilder();
                                Bundle b = i.getExtras();
                                if (b != null) {
                                    for (String k : b.keySet()) sb.append(k).append("=").append(String.valueOf(b.get(k))).append(" ");
                                }
                                String t = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
                                append(t + " BROADCAST " + i.getAction() + " " + trunc(sb.toString()));
                            }
                        };
                        registerReceiver(bRecv, f);
                        bOn = true;
                        bBtn.setText("6) مراقب الإشعارات: إيقاف");
                        append("بيسمع " + acts.size() + " إشعار. اعمل أي حاجة (افتح باب، شغّل الفتيس، طفّي الشاشة) وشوف.");
                        int n = 0;
                        for (String a : acts) {
                            if (a.startsWith("android.intent.action.")) continue;
                            append("   " + a);
                            if (++n >= 30) { append("   ..."); break; }
                        }
                    }
                });
            }
        }).start();
    }

    // ---------- 4) ELM327 CAN test ----------
    private void chooseElm() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        if (ad == null || !ad.isEnabled()) {
            append("البلوتوث مقفول أو مش موجود. شغّله واقرن الـ ELM327 الأول (PIN غالباً 1234 أو 0000).");
            return;
        }
        final List<BluetoothDevice> devs = new ArrayList<>(ad.getBondedDevices());
        if (devs.isEmpty()) {
            append("مفيش أجهزة مقترنة. اقرن الـ ELM327 من إعدادات البلوتوث الأول.");
            return;
        }
        String[] names = new String[devs.size()];
        for (int i = 0; i < names.length; i++) names[i] = devs.get(i).getName() + "  " + devs.get(i).getAddress();
        new AlertDialog.Builder(this)
                .setTitle("اختر الـ ELM327")
                .setItems(names, (d, which) -> startElm(devs.get(which)))
                .show();
    }

    private void startElm(final BluetoothDevice dev) {
        final String filter = filterField.getText().toString().trim().toUpperCase(Locale.US);
        elm = new ElmCan(new ElmCan.UI() {
            @Override public void log(String s) { append(s); }
            @Override public void banner(String s) { setBanner(s); }
            @Override public void beep() {
                try {
                    ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 100);
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 250);
                } catch (Exception ignored) { }
            }
        });
        append("=== فحص CAN عبر ELM327 ===");
        append("شغّل الكونتاكت (ON) قبل ما نبدأ.");
        new Thread(new Runnable() {
            @Override public void run() { elm.run(dev, filter); }
        }).start();
    }
}
