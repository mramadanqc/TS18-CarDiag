package com.ts18.cardiag;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
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
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
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

        TextView fl = new TextView(this);
        fl.setText("فلتر ID لفحص CAN (اختياري):");
        fl.setTextSize(13);
        left.addView(fl);
        filterField = new EditText(this);
        filterField.setSingleLine(true);
        filterField.setHint("مثال: 3A2");
        filterField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        left.addView(filterField);
        left.addView(btn("3) فحص CAN عبر ELM327", v -> chooseElm()));
        left.addView(btn("إيقاف", v -> stopAll()));
        left.addView(btn("نسخ التقرير", v -> copyReport()));
        left.addView(btn("مشاركة التقرير", v -> shareReport()));
        left.addView(btn("☁ إعداد Google Drive", v -> driveSetup()));
        left.addView(btn("☁ ربط حساب Drive", v -> driveLink()));
        left.addView(btn("☁ رفع التقرير إلى Drive", v -> driveUpload()));
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

    private void append(final String s) {
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

    private void setBanner(final String s) {
        runOnUiThread(new Runnable() {
            @Override public void run() { banner.setText(s); }
        });
    }

    private void stopAll() {
        watching = false;
        watchBtn.setText("2) مراقب التغيّرات: ابدأ");
        if (elm != null) elm.stop();
        setBanner("اتوقف");
    }

    // ---------- Google Drive upload ----------
    private DriveUploader drive() {
        return new DriveUploader(getSharedPreferences("drive", MODE_PRIVATE), new DriveUploader.Log() {
            @Override public void log(String s) { append(s); }
            @Override public void banner(String s) { setBanner(s); }
        });
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setSingleLine(true);
        return e;
    }

    private void driveSetup() {
        final SharedPreferences p = getSharedPreferences("drive", MODE_PRIVATE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(8), dp(16), 0);
        final EditText name = field("اسم الشاشة (اسم مجلد Drive)", p.getString("screen_name", "TS18-" + Build.MODEL));
        final EditText cid = field("Client ID", p.getString("client_id", ""));
        final EditText sec = field("Client Secret", p.getString("client_secret", ""));
        box.addView(name);
        box.addView(cid);
        box.addView(sec);
        new AlertDialog.Builder(this)
                .setTitle("إعداد Google Drive")
                .setView(box)
                .setPositiveButton("حفظ", (d, w) -> {
                    String newId = cid.getText().toString().trim();
                    SharedPreferences.Editor ed = p.edit()
                            .putString("screen_name", name.getText().toString().trim())
                            .putString("client_id", newId)
                            .putString("client_secret", sec.getText().toString().trim());
                    if (!newId.equals(p.getString("client_id", ""))) ed.remove("refresh_token");
                    ed.apply();
                    Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void driveLink() {
        append("=== ربط Google Drive ===");
        final DriveUploader up = drive();
        new Thread(new Runnable() {
            @Override public void run() { up.link(); }
        }).start();
    }

    private void driveUpload() {
        SharedPreferences p = getSharedPreferences("drive", MODE_PRIVATE);
        String screen = p.getString("screen_name", "TS18-" + Build.MODEL).trim();
        if (screen.length() == 0) screen = "TS18";
        final String folder = screen;
        final String fileName = "report_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        final String content = "Screen: " + folder + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\nAndroid: " + Build.VERSION.RELEASE + "\nTime: " + new Date() + "\n\n" + report.toString();
        append("=== رفع التقرير إلى Drive (" + folder + "/" + fileName + ") ===");
        final DriveUploader up = drive();
        new Thread(new Runnable() {
            @Override public void run() { up.upload(folder, fileName, content); }
        }).start();
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

    // ---------- 3) change watcher ----------
    private void toggleWatch() {
        if (watching) {
            watching = false;
            watchBtn.setText("2) مراقب التغيّرات: ابدأ");
            return;
        }
        watching = true;
        watchBtn.setText("2) مراقب التغيّرات: إيقاف");
        setBanner("المراقب شغال — افتح/اقفل باب وشوف اللي بيتغير");
        new Thread(new Runnable() {
            @Override public void run() {
                Map<String, String> prev = snapshot(true);
                append("لقطة أولية: " + prev.size() + " مفتاح. أي تغيير هيظهر هنا:");
                Map<String, Integer> cnt = new HashMap<>();
                SimpleDateFormat f = new SimpleDateFormat("HH:mm:ss", Locale.US);
                while (watching) {
                    try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
                    Map<String, String> cur = snapshot(true);
                    for (Map.Entry<String, String> e : cur.entrySet()) {
                        String old = prev.get(e.getKey());
                        if (old == null || !old.equals(e.getValue())) {
                            Integer prevCount = cnt.get(e.getKey());
                            int n = prevCount == null ? 1 : prevCount + 1;
                            cnt.put(e.getKey(), n);
                            if (n <= 6) {
                                append(f.format(new Date()) + " " + e.getKey() + ": " + trunc(old) + " -> " + trunc(e.getValue()));
                            } else if (n == 7) {
                                append("(تجاهل مفتاح كتير التغيير: " + e.getKey() + ")");
                            }
                        }
                    }
                    prev = cur;
                }
                append("تم إيقاف المراقب.");
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
