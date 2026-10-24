package com.ts18.cardiag;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class ElmCan {
    public interface UI {
        void log(String s);
        void banner(String s);
        void beep();
    }

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final UI ui;
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private volatile boolean stop = false;

    public ElmCan(UI ui) { this.ui = ui; }

    public void stop() { stop = true; }

    public void close() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) { }
    }

    static class Stat { long and = -1L; long or = 0L; int n = 0; }

    static class Phase {
        Map<String, Stat> m = new HashMap<>();
        int frames = 0;
        int bufFull = 0;
    }

    private void write(String s) throws Exception {
        out.write((s + "\r").getBytes("US-ASCII"));
        out.flush();
    }

    private void drain() throws Exception {
        while (in.available() > 0) in.read();
    }

    private String cmd(String c, long timeoutMs) throws Exception {
        drain();
        write(c);
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end && !stop) {
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) break;
                char ch = (char) b;
                if (ch == '>') break;
                sb.append(ch);
            } else {
                Thread.sleep(15);
            }
        }
        return sb.toString().replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static boolean isHex(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return s.length() > 0;
    }

    private void parseLine(String l, Phase ph) {
        if (l.length() == 0) return;
        String u = l.toUpperCase(Locale.US);
        if (u.contains("BUFFER FULL")) { ph.bufFull++; return; }
        String[] t = u.split("\\s+");
        if (t.length < 2) return;
        String id = t[0];
        if (!(id.length() == 3 || id.length() == 8) || !isHex(id)) return;
        int len = t.length - 1;
        if (len > 8) return;
        for (int i = 1; i < t.length; i++) {
            if (t[i].length() != 2 || !isHex(t[i])) return;
        }
        long data = 0;
        for (int i = 0; i < 8; i++) {
            int v = 0;
            if (i < len) v = Integer.parseInt(t[i + 1], 16);
            data = (data << 8) | v;
        }
        Stat s = ph.m.get(id);
        if (s == null) { s = new Stat(); ph.m.put(id, s); }
        s.and &= data;
        s.or |= data;
        s.n++;
        ph.frames++;
    }

    private Phase monitor(long ms) throws Exception {
        Phase ph = new Phase();
        drain();
        write("ATMA");
        StringBuilder line = new StringBuilder();
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && !stop) {
            if (in.available() > 0) {
                int b = in.read();
                if (b < 0) break;
                if (b == '\r' || b == '\n') {
                    if (line.length() > 0) {
                        parseLine(line.toString().trim(), ph);
                        line.setLength(0);
                    }
                } else {
                    line.append((char) b);
                }
            } else {
                Thread.sleep(5);
            }
        }
        write("");
        long end2 = System.currentTimeMillis() + 1500;
        while (System.currentTimeMillis() < end2) {
            if (in.available() > 0) {
                int b = in.read();
                if (b == '>' || b < 0) break;
            } else {
                Thread.sleep(10);
            }
        }
        return ph;
    }

    private void sleepStop(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end && !stop) Thread.sleep(50);
    }

    private String topIds(Phase ph, int max) {
        List<Map.Entry<String, Stat>> list = new ArrayList<>(ph.m.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, Stat>>() {
            @Override public int compare(Map.Entry<String, Stat> a, Map.Entry<String, Stat> b) {
                return b.getValue().n - a.getValue().n;
            }
        });
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, list.size()); i++) {
            sb.append(list.get(i).getKey()).append("(").append(list.get(i).getValue().n).append(") ");
        }
        return sb.toString().trim();
    }

    private int analyze(String name, Phase b0, Phase o0, Phase c0) {
        List<String> res = new ArrayList<>();
        for (Map.Entry<String, Stat> e : b0.m.entrySet()) {
            String id = e.getKey();
            Stat b = e.getValue();
            Stat o = o0.m.get(id);
            Stat c = c0.m.get(id);
            if (o == null || c == null) continue;
            long cm = (b.and | ~b.or) & (o.and | ~o.or) & (c.and | ~c.or);
            long diff = (o.and ^ b.and) & cm & ~(b.and ^ c.and);
            if (diff == 0) continue;
            for (int pos = 63; pos >= 0; pos--) {
                if (((diff >>> pos) & 1L) != 0) {
                    int byteIdx = (63 - pos) / 8;
                    int bit = pos - (56 - 8 * byteIdx);
                    String from = ((b.and >>> pos) & 1L) == 1L ? "1" : "0";
                    String to = ((o.and >>> pos) & 1L) == 1L ? "1" : "0";
                    res.add("ID " + id + " byte" + byteIdx + " bit" + bit + ": " + from + " -> " + to
                            + "  (frames " + b.n + "/" + o.n + "/" + c.n + ")");
                }
            }
        }
        if (res.isEmpty()) {
            ui.log("[" + name + "] لا توجد أي إشارة اتغيرت بشكل ثابت.");
        } else {
            ui.log("[" + name + "] مرشحات (" + res.size() + "):");
            for (int i = 0; i < Math.min(10, res.size()); i++) ui.log("   " + res.get(i));
        }
        return res.size();
    }

    public void run(BluetoothDevice dev, String filter) {
        stop = false;
        try {
            BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
            if (ad != null) ad.cancelDiscovery();
            ui.log("الاتصال بـ " + dev.getName() + " ...");
            socket = dev.createRfcommSocketToServiceRecord(SPP);
            socket.connect();
            in = socket.getInputStream();
            out = socket.getOutputStream();
        } catch (Exception e) {
            ui.log("فشل الاتصال: " + e.getMessage());
            ui.log("تأكد إن الـ ELM327 بلوتوث عادي (مش BLE) ومقترن بالشاشة وموصل بالـ OBD وشغال.");
            close();
            return;
        }
        try {
            ui.log("ATZ: " + cmd("ATZ", 3000));
            cmd("ATE0", 1000);
            cmd("ATL0", 1000);
            cmd("ATS1", 1000);
            cmd("ATH1", 1000);
            cmd("ATD0", 1000);
            cmd("ATCAF0", 1000);
            ui.log("الإصدار: " + cmd("ATI", 1000));
            ui.log("جهد البطارية: " + cmd("ATRV", 1000));

            int[] protos = {6, 7, 8, 9};
            String[] names = {"CAN 11bit 500k", "CAN 29bit 500k", "CAN 11bit 250k", "CAN 29bit 250k"};
            int bestIdx = -1;
            int bestFrames = 0;
            Phase bestPhase = null;
            ui.log("--- مرحلة 1: هل فيه رسائل CAN على فيشة الـ OBD؟ ---");
            for (int i = 0; i < protos.length && !stop; i++) {
                ui.banner("فحص " + names[i] + " ...");
                cmd("ATSP" + protos[i], 1500);
                Phase ph = monitor(3000);
                ui.log(names[i] + ": " + ph.frames + " رسالة، " + ph.m.size() + " ID"
                        + (ph.bufFull > 0 ? " (BUFFER FULL x" + ph.bufFull + ")" : ""));
                if (ph.frames > bestFrames) { bestFrames = ph.frames; bestIdx = i; bestPhase = ph; }
            }
            if (stop) { ui.log("اتوقف الفحص."); ui.banner("اتوقف"); close(); return; }
            if (bestIdx < 0) {
                ui.log("النتيجة: ما سمعتش أي رسالة CAN على pin 6/14 بأي سرعة.");
                ui.log("المعنى المحتمل: الكونتاكت مقفول، أو العربية مفيهاش بث CAN على فيشة الـ OBD (ميجان 2 القديمة ساعات بتستخدم K-line)، أو الـ ELM مش بيدعم CAN.");
                ui.banner("انتهى: لا يوجد CAN");
                close();
                return;
            }
            ui.log("أفضل بروتوكول: " + names[bestIdx] + " — أكتر IDs: " + topIds(bestPhase, 12));
            cmd("ATSP" + protos[bestIdx], 1500);
            if (filter != null && filter.length() > 0) {
                ui.log("فلتر ID: " + filter + " -> " + cmd("ATCRA" + filter, 1000));
            }

            ui.log("--- مرحلة 2: اختبار الأبواب (اتبع التعليمات على الشاشة، هيطلع صوت بيب عند كل خطوة) ---");
            ui.banner("اقفل كل الأبواب وماتلمسش حاجة");
            ui.beep();
            sleepStop(3000);
            ui.beep();
            Phase base = monitor(5000);
            ui.log("الأساس: " + base.frames + " رسالة، " + base.m.size() + " ID");
            String[] steps = {"باب السواق", "باب الراكب الأمامي", "الباب الخلفي الشمال", "الباب الخلفي اليمين", "الشنطة"};
            int total = 0;
            for (int i = 0; i < steps.length && !stop; i++) {
                ui.banner("افتح " + steps[i] + " وسيبه مفتوح");
                ui.beep();
                sleepStop(4000);
                ui.banner("ثبّت — بيتسجل (" + steps[i] + " مفتوح)");
                ui.beep();
                Phase open = monitor(5000);
                ui.banner("اقفل " + steps[i]);
                ui.beep();
                sleepStop(4000);
                ui.banner("ثبّت — بيتسجل (" + steps[i] + " مقفول)");
                ui.beep();
                Phase closed = monitor(5000);
                total += analyze(steps[i], base, open, closed);
            }
            ui.log("--- الخلاصة ---");
            if (total == 0) {
                ui.log("ما لقيتش إشارة أبواب على الـ CAN اللي في الـ OBD. غالباً الأبواب على شبكة تانية، أو الـ ELM بيفوّت الرسائل (جرّب تحدد ID بالفلتر).");
            } else {
                ui.log("لقيت " + total + " مرشح. ابعت التقرير كله عشان نحدد أنهي ID هو الأبواب بالظبط.");
            }
            ui.banner("انتهى الفحص");
        } catch (Exception e) {
            ui.log("خطأ أثناء الفحص: " + e);
            ui.banner("حصل خطأ");
        }
        close();
    }
}
