package com.ts18.cardiag;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ActivityInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only probes of installed head-unit packages (components, manifest strings, dex string search). */
public class Probe {
    public interface Out {
        void line(String s);
        void banner(String s);
    }

    public static volatile boolean cancel = false;

    public static boolean looksTarget(String pkg) {
        String l = pkg.toLowerCase(Locale.US);
        return l.startsWith("com.tw.") || l.startsWith("com.dofun.") || l.contains("carinfo") || l.contains("canbox")
                || l.contains("canbus") || l.contains("ldfy") || l.contains("zlink") || l.contains("txz")
                || l.contains("syu") || l.contains("mcu");
    }

    public static List<ApplicationInfo> targets(Context c) {
        List<ApplicationInfo> res = new ArrayList<>();
        for (ApplicationInfo ai : c.getPackageManager().getInstalledApplications(0)) {
            if (looksTarget(ai.packageName) && !ai.packageName.equals(c.getPackageName())) res.add(ai);
        }
        return res;
    }

    // ---- components ----
    public static void components(Context c, ApplicationInfo ai, Out out) {
        PackageManager pm = c.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_SERVICES
                    | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS);
            out.line("== " + ai.packageName + "  v" + pi.versionName + "  uid=" + ai.uid
                    + (pi.sharedUserId != null ? " sharedUser=" + pi.sharedUserId : "")
                    + ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? " [system]" : ""));
            if (pi.services != null) {
                for (int i = 0; i < Math.min(40, pi.services.length); i++) {
                    ServiceInfo s = pi.services[i];
                    out.line("  service  " + s.name + (s.exported ? " [exported]" : "")
                            + (s.permission != null ? " perm=" + s.permission : ""));
                }
            }
            if (pi.receivers != null) {
                for (int i = 0; i < Math.min(40, pi.receivers.length); i++) {
                    ActivityInfo r = pi.receivers[i];
                    out.line("  receiver " + r.name + (r.exported ? " [exported]" : "")
                            + (r.permission != null ? " perm=" + r.permission : ""));
                }
            }
            if (pi.providers != null) {
                for (int i = 0; i < Math.min(40, pi.providers.length); i++) {
                    ProviderInfo p = pi.providers[i];
                    out.line("  provider " + p.authority + " (" + p.name + ")" + (p.exported ? " [exported]" : "")
                            + (p.readPermission != null ? " r=" + p.readPermission : "")
                            + (p.writePermission != null ? " w=" + p.writePermission : ""));
                }
            }
        } catch (Exception e) {
            out.line("== " + ai.packageName + "  (تعذر القراءة: " + e + ")");
        }
    }

    // ---- binary AndroidManifest string pool ----
    public static List<String> manifestStrings(String apk) {
        List<String> res = new ArrayList<>();
        ZipFile z = null;
        try {
            z = new ZipFile(apk);
            ZipEntry e = z.getEntry("AndroidManifest.xml");
            if (e == null) return res;
            InputStream is = z.getInputStream(e);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
            is.close();
            byte[] d = bo.toByteArray();
            ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
            int pos = 8;
            int type = bb.getShort(pos) & 0xFFFF;
            if (type != 0x0001) return res;
            int headerSize = bb.getShort(pos + 2) & 0xFFFF;
            int strCount = bb.getInt(pos + 8);
            int flags = bb.getInt(pos + 16);
            int strStart = bb.getInt(pos + 20);
            boolean utf8 = (flags & 0x100) != 0;
            int offsetsPos = pos + headerSize;
            for (int i = 0; i < strCount && i < 6000; i++) {
                int off = bb.getInt(offsetsPos + 4 * i);
                int p = pos + strStart + off;
                String s;
                if (utf8) {
                    int len = d[p] & 0xFF;
                    p++;
                    if ((len & 0x80) != 0) p++;
                    int blen = d[p] & 0xFF;
                    p++;
                    if ((blen & 0x80) != 0) {
                        blen = ((blen & 0x7F) << 8) | (d[p] & 0xFF);
                        p++;
                    }
                    s = new String(d, p, blen, "UTF-8");
                } else {
                    int len = bb.getShort(p) & 0xFFFF;
                    p += 2;
                    if ((len & 0x8000) != 0) {
                        len = ((len & 0x7FFF) << 16) | (bb.getShort(p) & 0xFFFF);
                        p += 2;
                    }
                    s = new String(d, p, len * 2, "UTF-16LE");
                }
                res.add(s);
            }
        } catch (Throwable ignored) {
        } finally {
            if (z != null) try { z.close(); } catch (Exception ignored) { }
        }
        return res;
    }

    private static boolean classLike(String s) {
        int dot = s.lastIndexOf('.');
        String last = dot >= 0 ? s.substring(dot + 1) : s;
        if (last.length() == 0) return true;
        boolean upperFirst = Character.isUpperCase(last.charAt(0));
        boolean hasLower = false;
        for (int i = 0; i < last.length(); i++) if (Character.isLowerCase(last.charAt(i))) hasLower = true;
        return upperFirst && hasLower;
    }

    public static boolean actionLike(String s) {
        if (s.length() < 6 || s.length() > 120) return false;
        if (s.indexOf(' ') >= 0 || s.indexOf('/') >= 0 || s.indexOf('.') < 0) return false;
        if (s.startsWith("android.") || s.startsWith("com.google") || s.startsWith("androidx.")) return false;
        if (classLike(s)) return false;
        String l = s.toLowerCase(Locale.US);
        String[] kw = {"action", "broadcast", "intent", "door", "reverse", "brake", "acc", "can", "mcu", "gear", "light", "tw.", "car", "key", "power"};
        for (String k : kw) if (l.contains(k)) return true;
        return false;
    }

    public static Set<String> actionsOf(String apk) {
        Set<String> res = new TreeSet<>();
        for (String s : manifestStrings(apk)) if (actionLike(s)) res.add(s);
        return res;
    }

    public static Set<String> collectActions(Context c) {
        Set<String> res = new LinkedHashSet<>();
        for (ApplicationInfo ai : targets(c)) {
            if (ai.sourceDir != null) res.addAll(actionsOf(ai.sourceDir));
            if (res.size() > 400) break;
        }
        return res;
    }

    // ---- dex string search ----
    private static int indexOf(byte[] d, byte[] p) {
        outer:
        for (int i = 0; i <= d.length - p.length; i++) {
            for (int j = 0; j < p.length; j++) if (d[i + j] != p[j]) continue outer;
            return i;
        }
        return -1;
    }

    private static void scanStream(InputStream is, byte[][] pats, String[] needles, int maxLen, Set<String> hits) throws Exception {
        byte[] buf = new byte[65536];
        byte[] tail = new byte[0];
        int r;
        while ((r = is.read(buf)) > 0) {
            byte[] comb = new byte[tail.length + r];
            System.arraycopy(tail, 0, comb, 0, tail.length);
            System.arraycopy(buf, 0, comb, tail.length, r);
            for (int i = 0; i < pats.length; i++) if (indexOf(comb, pats[i]) >= 0) hits.add(needles[i]);
            int keep = Math.min(maxLen - 1, comb.length);
            tail = Arrays.copyOfRange(comb, comb.length - keep, comb.length);
        }
    }

    private static Set<String> scanApk(String path, byte[][] pats, String[] needles, int maxLen) {
        Set<String> hits = new LinkedHashSet<>();
        ZipFile z = null;
        try {
            z = new ZipFile(path);
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".dex")) continue;
                InputStream is = z.getInputStream(e);
                scanStream(is, pats, needles, maxLen, hits);
                is.close();
            }
        } catch (Throwable ignored) {
        } finally {
            if (z != null) try { z.close(); } catch (Exception ignored) { }
        }
        // system apps may keep their dex only in oat/*.vdex or *.odex
        try {
            File parent = new File(path).getParentFile();
            File oat = parent == null ? null : new File(parent, "oat");
            File[] archs = oat == null ? null : oat.listFiles();
            if (archs != null) {
                for (File a : archs) {
                    File[] files = a.listFiles();
                    if (files == null) continue;
                    for (File f : files) {
                        String n = f.getName();
                        if ((n.endsWith(".vdex") || n.endsWith(".odex")) && f.length() < 60L * 1024 * 1024) {
                            FileInputStream is = new FileInputStream(f);
                            try { scanStream(is, pats, needles, maxLen, hits); } finally { is.close(); }
                        }
                    }
                }
            }
        } catch (Throwable ignored) { }
        return hits;
    }

    public static void scanDex(Context c, String[] needles, Out out, long maxMs) {
        cancel = false;
        try {
            PackageManager pm = c.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            long start = System.currentTimeMillis();
            byte[][] pats = new byte[needles.length][];
            int maxLen = 1;
            for (int i = 0; i < needles.length; i++) {
                pats[i] = needles[i].getBytes("US-ASCII");
                maxLen = Math.max(maxLen, pats[i].length);
            }
            int scanned = 0;
            int found = 0;
            for (ApplicationInfo ai : apps) {
                if (cancel) { out.line("اتوقف الفحص."); break; }
                if (System.currentTimeMillis() - start > maxMs) { out.line("خلص الوقت المسموح للفحص."); break; }
                String pn = ai.packageName;
                if (pn.startsWith("com.google.") || pn.startsWith("com.android.") || pn.equals("android")
                        || pn.equals(c.getPackageName()) || ai.sourceDir == null) continue;
                scanned++;
                out.banner("فحص " + scanned + ": " + pn);
                Set<String> hits = scanApk(ai.sourceDir, pats, needles, maxLen);
                if (!hits.isEmpty()) {
                    found++;
                    out.line("• " + pn + "  <- " + hits
                            + ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 ? " [system]" : "") + "  (" + ai.sourceDir + ")");
                }
            }
            out.line("انتهى. فُحص " + scanned + " تطبيق، المطابق: " + found);
            out.banner("انتهى الفحص");
        } catch (Exception e) {
            out.line("خطأ: " + e);
        }
    }
}
