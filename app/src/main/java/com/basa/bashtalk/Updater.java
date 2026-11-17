package com.basa.bashtalk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Provjera novih verzija na GitHubu (Basaofficial/bashtalk) i instalacija
 * preko PackageInstallera. Načini: "auto" (sam preuzme i instalira),
 * "ask" (pita), "off". Bez interneta tiho preskače.
 */
public class Updater {

    static final String API = "https://api.github.com/repos/Basaofficial/bashtalk/releases/latest";
    static final String CH = "bt_update";
    static final int NOTIF = 50;

    static volatile Engine.Listener ui;
    static volatile boolean busy;
    static volatile JSONObject latest;      // {code, tag, url, size, notes}
    static volatile File readyFile;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("bashtalk", Context.MODE_PRIVATE);
    }

    static int currentCode(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? (int) pi.getLongVersionCode() : pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    static String currentName(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return false;
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Exception e) {
            return false;
        }
    }

    static void emit(String state, JSONObject extra) {
        Engine.Listener l = ui;
        if (l == null) return;
        try {
            JSONObject o = extra != null ? new JSONObject(extra.toString()) : new JSONObject();
            o.put("state", state);
            l.onEvent("update", o);
        } catch (Exception ignored) { }
    }

    static void emit(String state) { emit(state, latest); }

    /** Provjera ako je prošlo dovoljno vremena od zadnje. */
    static void maybeCheck(Context c, long minGapMs) {
        long last = prefs(c).getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last > minGapMs) checkAsync(c, false);
        else if (latest != null) offer(c.getApplicationContext(), false);
    }

    static void checkAsync(Context c, boolean manual) {
        final Context app = c.getApplicationContext();
        new Thread(() -> check(app, manual), "update-check").start();
    }

    private static void check(Context c, boolean manual) {
        SharedPreferences p = prefs(c);
        String mode = p.getString("upd", "auto");
        if ("off".equals(mode) && !manual) return;
        if (busy) return;
        if (!online(c)) {
            if (manual) emit("offline", null);
            return;
        }
        if (manual) emit("checking", null);
        try {
            HttpURLConnection con = (HttpURLConnection) new URL(API).openConnection();
            con.setRequestProperty("Accept", "application/vnd.github+json");
            con.setRequestProperty("User-Agent", "BashTalk-Android");
            con.setConnectTimeout(10000);
            con.setReadTimeout(15000);
            if (con.getResponseCode() != 200) throw new Exception("HTTP " + con.getResponseCode());
            InputStream in = con.getInputStream();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            JSONObject r = new JSONObject(bo.toString("UTF-8"));
            p.edit().putLong("lastCheck", System.currentTimeMillis()).apply();

            String tag = r.optString("tag_name", "v0");
            int code = Integer.parseInt(tag.replaceAll("[^0-9]", "").isEmpty() ? "0" : tag.replaceAll("[^0-9]", ""));
            String url = null;
            long size = 0;
            JSONArray assets = r.optJSONArray("assets");
            if (assets != null) for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (a.optString("name").endsWith(".apk")) {
                    url = a.optString("browser_download_url");
                    size = a.optLong("size");
                    break;
                }
            }
            if (url == null || code <= currentCode(c)) {
                latest = null;
                if (manual) emit("none", null);
                return;
            }
            JSONObject l = new JSONObject();
            java.util.regex.Matcher vm = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)").matcher(r.optString("name", ""));
            l.put("ver", vm.find() ? vm.group(1) : String.valueOf(code));
            l.put("code", code);
            l.put("tag", tag);
            l.put("url", url);
            l.put("size", size);
            l.put("notes", r.optString("body", "").trim());
            latest = l;
            offer(c, manual);
        } catch (Exception e) {
            if (manual) {
                try {
                    JSONObject x = new JSONObject();
                    x.put("msg", e.getMessage());
                    emit("error", x);
                } catch (Exception ignored) { }
            }
        }
    }

    /** Provjera iz pozadinskog posla (aplikacija može biti zatvorena). */
    static void checkNow(Context c) {
        check(c.getApplicationContext(), false);
    }

    /** Kratko obavještenje, jednom po verziji. */
    static void notifyNew(Context c, boolean installing) {
        JSONObject l = latest;
        if (l == null || ui != null) return;
        int code = l.optInt("code");
        if (prefs(c).getInt("notifiedCode", 0) >= code) return;
        prefs(c).edit().putInt("notifiedCode", code).apply();
        notify(c, "🆕 Novi update", "Verzija " + l.optString("ver") + (installing ? " · instalira se…" : " · dodirni za instalaciju"), openApp(c));
    }

    /** Nova verzija postoji: automatski instaliraj ili ponudi. */
    static void offer(Context c, boolean manual) {
        if (latest == null || busy) return;
        String mode = prefs(c).getString("upd", "auto");
        notifyNew(c, "auto".equals(mode) && !manual);
        if ("auto".equals(mode) && !manual) {
            downloadAndInstall(c);
            return;
        }
        long snooze = prefs(c).getLong("snoozeUntil", 0);
        if (!manual && System.currentTimeMillis() < snooze) return;
        emit("available");
    }

    static void snooze(Context c) {
        prefs(c).edit().putLong("snoozeUntil", System.currentTimeMillis() + 12 * 3600_000L).apply();
    }

    static void downloadAndInstall(Context c) {
        if (latest == null || busy) return;
        final Context app = c.getApplicationContext();
        busy = true;
        new Thread(() -> {
            try {
                File f = new File(app.getCacheDir(), "update-" + latest.optInt("code") + ".apk");
                long size = latest.optLong("size");
                if (!f.exists() || f.length() != size) {
                    emit("downloading");
                    HttpURLConnection con = (HttpURLConnection) new URL(latest.getString("url")).openConnection();
                    con.setInstanceFollowRedirects(true);
                    con.setRequestProperty("User-Agent", "BashTalk-Android");
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(30000);
                    // GitHub preusmjerava na drugi host – prati ručno ako treba
                    int rc = con.getResponseCode();
                    int hops = 0;
                    while ((rc == 301 || rc == 302 || rc == 303 || rc == 307 || rc == 308) && hops++ < 5) {
                        String loc = con.getHeaderField("Location");
                        con.disconnect();
                        con = (HttpURLConnection) new URL(loc).openConnection();
                        con.setRequestProperty("User-Agent", "BashTalk-Android");
                        con.setConnectTimeout(15000);
                        con.setReadTimeout(30000);
                        rc = con.getResponseCode();
                    }
                    if (rc != 200) throw new Exception("Preuzimanje: HTTP " + rc);
                    long total = size > 0 ? size : con.getContentLengthLong();
                    try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[16384];
                        long done = 0;
                        int n, lastPct = -1;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            int pct = total > 0 ? (int) (done * 100 / total) : 0;
                            if (pct != lastPct && pct % 5 == 0) {
                                lastPct = pct;
                                JSONObject x = new JSONObject(latest.toString());
                                x.put("pct", pct);
                                emit("progress", x);
                            }
                        }
                    }
                    if (size > 0 && f.length() != size) throw new Exception("APK nije kompletno preuzet");
                }
                readyFile = f;
                install(app, f);
            } catch (Exception e) {
                try {
                    JSONObject x = new JSONObject();
                    x.put("msg", e.getMessage());
                    emit("error", x);
                } catch (Exception ignored) { }
            } finally {
                busy = false;
            }
        }, "update-dl").start();
    }

    static boolean canInstall(Context c) {
        return Build.VERSION.SDK_INT < 26 || c.getPackageManager().canRequestPackageInstalls();
    }

    static void install(Context c, File f) throws Exception {
        if (!canInstall(c)) {
            emit("perm");
            if (ui == null) notify(c, "⬆️ #Bash Talk ažuriranje", "Dozvoli instalaciju ažuriranja – dodirni ovdje.", openApp(c));
            return;
        }
        emit("installing");
        PackageInstaller pi = c.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(c.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) {
            // tiha nadogradnja kad je ova aplikacija već instalirala samu sebe
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (OutputStream out = s.openWrite("base.apk", 0, f.length()); InputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent i = new Intent(c, UpdateReceiver.class).setAction(UpdateReceiver.ACTION_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getBroadcast(c, id, i, flags);
            s.commit(p.getIntentSender());
        }
    }

    /** Ponovo pokreni instalaciju već preuzetog fajla (npr. nakon davanja dozvole). */
    static void installReady(Context c) {
        File f = readyFile;
        if (f != null && f.exists()) {
            final Context app = c.getApplicationContext();
            new Thread(() -> {
                try { install(app, f); } catch (Exception e) {
                    try {
                        JSONObject x = new JSONObject();
                        x.put("msg", e.getMessage());
                        emit("error", x);
                    } catch (Exception ignored) { }
                }
            }).start();
        } else {
            downloadAndInstall(c);
        }
    }

    // ------------------------------------------------------------ obavještenja

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra("openUpdate", true);
        return PendingIntent.getActivity(c, 77, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void notify(Context c, String title, String text, PendingIntent tap) {
        try {
            NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(new NotificationChannel(CH, "Ažuriranja", NotificationManager.IMPORTANCE_DEFAULT));
                b = new Notification.Builder(c, CH);
            } else {
                b = new Notification.Builder(c);
            }
            b.setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setColor(0xFF8DFF1A)
                    .setContentIntent(tap);
            nm.notify(NOTIF, b.build());
        } catch (Exception ignored) { }
    }
}
