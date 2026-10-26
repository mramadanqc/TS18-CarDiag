package com.ts18.cardiag;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Live speed, trip stats from GPS, and the saved parking spot. */
public class TripActivity extends Activity implements LocationListener {
    private LocationManager lm;
    private TextView tvSpeed, tvStats, tvPark;
    private Location last;
    private float tripM = 0f;
    private float maxKmh = 0f;
    private long movingMs = 0;
    private long lastTs = 0;
    private final long startMs = SystemClock.elapsedRealtime();

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private TextView text(String s, int size) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        return t;
    }

    private Button btn(String s, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(s);
        b.setMinHeight(dp(48));
        b.setOnClickListener(l);
        return b;
    }

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setPadding(dp(16), dp(10), dp(16), dp(10));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER);
        root.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 5f));
        tvSpeed = text("0", 110);
        tvSpeed.setGravity(Gravity.CENTER);
        left.addView(tvSpeed);
        left.addView(text("كم/س", 24));

        ScrollView sc = new ScrollView(this);
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(12), 0, dp(8), 0);
        sc.addView(right);
        root.addView(sc, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 5f));

        tvStats = text("", 18);
        right.addView(tvStats);
        tvPark = text("", 15);
        tvPark.setPadding(0, dp(12), 0, dp(8));
        right.addView(tvPark);
        right.addView(btn("فتح موقف العربية في الخرائط", v -> openPark()));
        right.addView(btn("حفظ موقعي الحالي كموقف", v -> saveParkNow()));
        right.addView(btn("تصفير الرحلة", v -> resetTrip()));
        right.addView(btn("إغلاق", v -> finish()));
        setContentView(root);
        refreshUi();
    }

    private boolean hasPerm() {
        return getPackageManager().checkPermission(Manifest.permission.ACCESS_FINE_LOCATION, getPackageName())
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        startUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { lm.removeUpdates(this); } catch (Exception ignored) { }
    }

    private void startUpdates() {
        if (!hasPerm()) {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 21);
            tvStats.setText("محتاج صلاحية الموقع.");
            return;
        }
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
        } catch (Exception e) {
            tvStats.setText("تعذر تشغيل الـ GPS: " + e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 21) startUpdates();
    }

    @Override
    public void onLocationChanged(Location l) {
        long now = SystemClock.elapsedRealtime();
        float kmh = l.hasSpeed() ? l.getSpeed() * 3.6f : 0f;
        if (last != null && !l.hasSpeed()) {
            float dt = (l.getTime() - last.getTime()) / 1000f;
            if (dt > 0) kmh = last.distanceTo(l) / dt * 3.6f;
        }
        if (l.getAccuracy() <= 40f) {
            if (last != null && kmh >= 3f) {
                tripM += last.distanceTo(l);
                if (lastTs > 0) movingMs += now - lastTs;
            }
            if (kmh > maxKmh && kmh < 300f) maxKmh = kmh;
        }
        last = l;
        lastTs = now;
        tvSpeed.setText(String.format(Locale.US, "%.0f", kmh));
        refreshUi();
    }

    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public void onProviderEnabled(String provider) { }
    @Override public void onProviderDisabled(String provider) { }

    private void refreshUi() {
        float km = tripM / 1000f;
        float hrs = movingMs / 3600000f;
        float avg = hrs > 0 ? km / hrs : 0f;
        long totalMin = (SystemClock.elapsedRealtime() - startMs) / 60000;
        String acc = last == null ? "لسه مفيش إشارة GPS" : String.format(Locale.US, "دقة الـ GPS: %.0f متر", last.getAccuracy());
        tvStats.setText(String.format(Locale.US, "مسافة الرحلة: %.2f كم\nأقصى سرعة: %.0f كم/س\nمتوسط السرعة (أثناء الحركة): %.0f كم/س\nمدة الفتح: %d دقيقة\n%s",
                km, maxKmh, avg, totalMin, acc));
        SharedPreferences p = getSharedPreferences("park", MODE_PRIVATE);
        if (p.contains("park_lat")) {
            long t = p.getLong("park_time", 0);
            tvPark.setText("آخر موقف: " + p.getString("park_lat", "") + " , " + p.getString("park_lon", "")
                    + "\n" + new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(t))
                    + "  (" + p.getString("park_reason", "") + ")");
        } else {
            tvPark.setText("مفيش موقف محفوظ لسه. شغّل الصندوق الأسود من الأدوات المتقدمة عشان يحفظه تلقائياً.");
        }
    }

    private void resetTrip() {
        tripM = 0f;
        maxKmh = 0f;
        movingMs = 0;
        refreshUi();
    }

    private void saveParkNow() {
        SharedPreferences p = getSharedPreferences("park", MODE_PRIVATE);
        if (last == null && !p.contains("fix_lat")) {
            Toast.makeText(this, "مفيش موقع معروف لسه", Toast.LENGTH_SHORT).show();
            return;
        }
        String lat = last != null ? String.valueOf(last.getLatitude()) : p.getString("fix_lat", "0");
        String lon = last != null ? String.valueOf(last.getLongitude()) : p.getString("fix_lon", "0");
        p.edit().putString("park_lat", lat).putString("park_lon", lon).putLong("park_time", System.currentTimeMillis())
                .putString("park_reason", "manual").apply();
        refreshUi();
        Toast.makeText(this, "تم حفظ الموقف", Toast.LENGTH_SHORT).show();
    }

    private void openPark() {
        SharedPreferences p = getSharedPreferences("park", MODE_PRIVATE);
        if (!p.contains("park_lat")) {
            Toast.makeText(this, "مفيش موقف محفوظ", Toast.LENGTH_SHORT).show();
            return;
        }
        String lat = p.getString("park_lat", "0");
        String lon = p.getString("park_lon", "0");
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + lat + "," + lon + "?q=" + lat + "," + lon + "(العربية)")));
        } catch (Exception e) {
            Toast.makeText(this, "مفيش تطبيق خرائط. الإحداثيات: " + lat + " , " + lon, Toast.LENGTH_LONG).show();
        }
    }
}
