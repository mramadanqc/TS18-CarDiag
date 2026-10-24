package com.ts18.cardiag;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Uploads text reports to Google Drive using the OAuth "limited-input device" flow and the drive.file scope. */
public class DriveUploader {
    public interface Log {
        void log(String s);
        void banner(String s);
    }

    static class HttpException extends Exception {
        final int code;
        final String body;
        HttpException(int code, String body) {
            super("HTTP " + code);
            this.code = code;
            this.body = body;
        }
    }

    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private final SharedPreferences p;
    private final Log ui;
    private volatile boolean cancel = false;

    public DriveUploader(SharedPreferences p, Log ui) {
        this.p = p;
        this.ui = ui;
    }

    public void cancel() { cancel = true; }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        is.close();
        return bo.toString("UTF-8");
    }

    private static String request(String method, String url, String bearer, String contentType, byte[] body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestMethod(method);
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", contentType);
            OutputStream o = c.getOutputStream();
            o.write(body);
            o.close();
        }
        int code = c.getResponseCode();
        String resp = readAll(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 400) throw new HttpException(code, resp);
        return resp;
    }

    private static String form(String... kv) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], "UTF-8")).append('=').append(URLEncoder.encode(kv[i + 1], "UTF-8"));
        }
        return sb.toString();
    }

    private static String errorOf(String body) {
        try { return new JSONObject(body).optString("error", body); } catch (Exception e) { return body; }
    }

    private static String shortText(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    /** Step 1: link a Google account (user enters a code on another device). */
    public void link() {
        cancel = false;
        String clientId = p.getString("client_id", "");
        String secret = p.getString("client_secret", "");
        if (clientId.length() == 0 || secret.length() == 0) {
            ui.log("ادخل Client ID و Client Secret الأول من زر إعداد Google Drive.");
            return;
        }
        try {
            JSONObject d = new JSONObject(request("POST", "https://oauth2.googleapis.com/device/code", null, FORM,
                    form("client_id", clientId, "scope", "https://www.googleapis.com/auth/drive.file").getBytes("UTF-8")));
            String deviceCode = d.getString("device_code");
            String userCode = d.getString("user_code");
            String url = d.optString("verification_url", d.optString("verification_uri", "https://www.google.com/device"));
            int interval = d.optInt("interval", 5);
            int expires = d.optInt("expires_in", 1800);
            ui.log("افتح من موبايلك: " + url + "  واكتب الكود: " + userCode);
            ui.banner("الكود: " + userCode + "  —  " + url);
            long end = System.currentTimeMillis() + expires * 1000L;
            while (!cancel && System.currentTimeMillis() < end) {
                Thread.sleep(interval * 1000L);
                try {
                    JSONObject t = new JSONObject(request("POST", TOKEN_URL, null, FORM,
                            form("client_id", clientId, "client_secret", secret, "device_code", deviceCode,
                                    "grant_type", "urn:ietf:params:oauth:grant-type:device_code").getBytes("UTF-8")));
                    p.edit().putString("refresh_token", t.getString("refresh_token")).apply();
                    ui.log("تم ربط Google Drive ✔");
                    ui.banner("تم ربط Google Drive");
                    return;
                } catch (HttpException e) {
                    String err = errorOf(e.body);
                    if ("authorization_pending".equals(err)) continue;
                    if ("slow_down".equals(err)) { interval += 5; continue; }
                    ui.log("فشل الربط: " + shortText(e.body));
                    ui.banner("فشل الربط");
                    return;
                }
            }
            ui.log(cancel ? "اتلغى الربط." : "انتهت مهلة الكود، اضغط ربط تاني.");
        } catch (HttpException e) {
            ui.log("فشل طلب الكود (HTTP " + e.code + "): " + shortText(e.body));
            ui.log("اتأكد إن الـ Client من نوع TVs and Limited Input devices وإن Drive API مفعّل.");
        } catch (Exception e) {
            ui.log("خطأ: " + e + " (تأكد إن الشاشة متصلة بالإنترنت)");
        }
    }

    private String accessToken() throws Exception {
        String rt = p.getString("refresh_token", "");
        if (rt.length() == 0) throw new IllegalStateException("not linked");
        JSONObject t = new JSONObject(request("POST", TOKEN_URL, null, FORM,
                form("client_id", p.getString("client_id", ""), "client_secret", p.getString("client_secret", ""),
                        "refresh_token", rt, "grant_type", "refresh_token").getBytes("UTF-8")));
        return t.getString("access_token");
    }

    private String findOrCreateFolder(String at, String name) throws Exception {
        String cachedName = p.getString("folder_name", "");
        String cachedId = p.getString("folder_id", "");
        if (cachedId.length() > 0 && cachedName.equals(name)) {
            try {
                JSONObject f = new JSONObject(request("GET",
                        "https://www.googleapis.com/drive/v3/files/" + cachedId + "?fields=id,trashed", at, null, null));
                if (!f.optBoolean("trashed", false)) return cachedId;
            } catch (HttpException ignored) { }
        }
        String q = "name='" + name.replace("\\", "\\\\").replace("'", "\\'")
                + "' and mimeType='application/vnd.google-apps.folder' and trashed=false";
        JSONObject list = new JSONObject(request("GET",
                "https://www.googleapis.com/drive/v3/files?fields=files(id,name)&q=" + URLEncoder.encode(q, "UTF-8"),
                at, null, null));
        JSONArray arr = list.optJSONArray("files");
        String id;
        if (arr != null && arr.length() > 0) {
            id = arr.getJSONObject(0).getString("id");
        } else {
            JSONObject meta = new JSONObject();
            meta.put("name", name);
            meta.put("mimeType", "application/vnd.google-apps.folder");
            JSONObject created = new JSONObject(request("POST", "https://www.googleapis.com/drive/v3/files?fields=id",
                    at, "application/json; charset=UTF-8", meta.toString().getBytes("UTF-8")));
            id = created.getString("id");
            ui.log("اتعمل مجلد جديد في Drive: " + name);
        }
        p.edit().putString("folder_id", id).putString("folder_name", name).apply();
        return id;
    }

    /** Step 2: upload a text file into a Drive folder named after the screen. */
    public void upload(String folderName, String fileName, String content) {
        try {
            String at = accessToken();
            String folderId = findOrCreateFolder(at, folderName);
            String boundary = "ts18boundary" + System.currentTimeMillis();
            JSONObject meta = new JSONObject();
            meta.put("name", fileName);
            meta.put("parents", new JSONArray().put(folderId));
            String body = "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta.toString()
                    + "\r\n--" + boundary + "\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + content
                    + "\r\n--" + boundary + "--";
            request("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name", at,
                    "multipart/related; boundary=" + boundary, body.getBytes("UTF-8"));
            ui.log("تم الرفع ✔ المجلد: " + folderName + " — الملف: " + fileName);
            ui.banner("تم الرفع إلى Drive");
        } catch (IllegalStateException e) {
            ui.log("الحساب مش مربوط. اضغط 'ربط حساب Drive' الأول.");
        } catch (HttpException e) {
            ui.log("فشل الرفع (HTTP " + e.code + "): " + shortText(e.body));
            if (e.code == 400 || e.code == 401) {
                ui.log("غالباً الربط انتهى أو بيانات الـ Client غلط. اضغط 'ربط حساب Drive' تاني.");
            }
            ui.banner("فشل الرفع");
        } catch (Exception e) {
            ui.log("خطأ أثناء الرفع: " + e + " (تأكد إن الشاشة متصلة بالإنترنت)");
            ui.banner("فشل الرفع");
        }
    }
}
