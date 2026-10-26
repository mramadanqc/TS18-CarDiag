package com.ts18.cardiag;

import android.content.Context;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Append-only event log file (rotates at ~1 MB). */
public class BlackBox {
    private static final long MAX = 1000000L;

    static File file(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        return new File(d, "blackbox.log");
    }

    public static synchronized void add(Context c, String msg) {
        try {
            File f = file(c);
            if (f.length() > MAX) {
                File old = new File(f.getParentFile(), "blackbox.old.log");
                old.delete();
                f.renameTo(old);
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                    + " | up " + (SystemClock.elapsedRealtime() / 1000) + "s | " + msg + "\n";
            FileOutputStream o = new FileOutputStream(f, true);
            o.write(line.getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
    }

    public static synchronized String tail(Context c, int lines) {
        try {
            File f = file(c);
            if (!f.exists()) return "(الصندوق الأسود فاضي)";
            FileInputStream is = new FileInputStream(f);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            String[] all = bo.toString("UTF-8").split("\n");
            int start = Math.max(0, all.length - lines);
            StringBuilder sb = new StringBuilder();
            for (int i = start; i < all.length; i++) sb.append(all[i]).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "(تعذر القراءة: " + e + ")";
        }
    }

    public static synchronized void clear(Context c) {
        File f = file(c);
        f.delete();
        new File(f.getParentFile(), "blackbox.old.log").delete();
    }
}
