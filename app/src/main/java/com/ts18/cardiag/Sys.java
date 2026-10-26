package com.ts18.cardiag;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Static helpers for reading system settings/properties. */
public class Sys {
    static final String[] NOISE = {"phenotype", "backup_agent", "ble_scan", "wifi_scan", "boot_count", "uptime",
            "elapsed", "timestamp", "last_", "_time", "time_", "ntp", "gservices", "finsky", "vending", "bluetooth_address",
            "wifi_", "netstats", "cell_", "net.", "dhcp", "init.svc", "sys.sysctl", "ril.", "gsm.", "debug.", "dalvik",
            "persist.sys.timezone", "vendor.cam", "bootanim", "sys.boot", "sys.lmk", "mobile_data", "adb_", "vendor.sys",
            "media_button_receiver", "usage", "stats", "heartbeat", "clock"};
    static final String[] HOT = {"door", "can", "acc", "brake", "reverse", "gear", "park", "light", "mcu", "ldfy",
            "speed", "ignit", "sleep", "tw"};

    static boolean hasAny(String s, String[] words) {
        String l = s.toLowerCase(Locale.US);
        for (String w : words) if (l.contains(w)) return true;
        return false;
    }

    static boolean isNoise(String k) { return hasAny(k, NOISE); }

    static boolean isHot(String k) { return hasAny(k, HOT); }

    static String trunc(String s, int n) {
        if (s == null) return "(لا يوجد)";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    static Map<String, String> getprops() {
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

    static void readTable(ContentResolver cr, Uri uri, String prefix, Map<String, String> m) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{"name", "value"}, null, null, null);
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

    static Map<String, String> snapshot(Context c, boolean withProps) {
        Map<String, String> m = new HashMap<>();
        ContentResolver cr = c.getContentResolver();
        readTable(cr, Settings.System.CONTENT_URI, "System:", m);
        readTable(cr, Settings.Secure.CONTENT_URI, "Secure:", m);
        readTable(cr, Settings.Global.CONTENT_URI, "Global:", m);
        if (withProps) {
            for (Map.Entry<String, String> e : getprops().entrySet()) m.put("prop:" + e.getKey(), e.getValue());
        }
        return m;
    }

    static String querySetting(ContentResolver cr, Uri uri) {
        Cursor c = null;
        try {
            c = cr.query(uri, new String[]{"value"}, null, null, null);
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
}
