package com.basa.bashtalk;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drži Engine živim. U "pozadinskom" načinu radi kao foreground usluga s
 * obavještenjem, pa prima i šalje glas i dok je ekran ugašen.
 */
public class TalkService extends Service implements Engine.Listener {

    static final String ACTION_FG = "com.basa.bashtalk.FG";
    static final String ACTION_BG = "com.basa.bashtalk.BG";
    static final String ACTION_TALK = "com.basa.bashtalk.TALK";
    static final String ACTION_STOP = "com.basa.bashtalk.STOP";

    static final String CH_SERVICE = "bt_service";
    static final String CH_MSG = "bt_msg";
    static final int NOTIF_ID = 1;

    static volatile TalkService inst;
    static volatile Engine.Listener ui;
    private static final List<String[]> backlog = new ArrayList<>();

    Engine engine;
    private SharedPreferences prefs;
    private boolean foreground;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private int lastCount = -1;
    private boolean lastTalk;
    private int msgNotifId = 100;

    static Engine eng() {
        TalkService s = inst;
        return s == null ? null : s.engine;
    }

    /** Aktivnost se kači na usluge i dobija sve propuštene događaje. */
    static void attach(Engine.Listener l) {
        ui = l;
        List<String[]> copy;
        synchronized (backlog) {
            copy = new ArrayList<>(backlog);
            backlog.clear();
        }
        for (String[] e : copy) {
            try { l.onEvent(e[0], new JSONObject(e[1])); } catch (Exception ignored) { }
        }
        TalkService s = inst;
        if (s != null) {
            try {
                NotificationManager nm = (NotificationManager) s.getSystemService(NOTIFICATION_SERVICE);
                for (int i = 100; i <= s.msgNotifId; i++) nm.cancel(i);
            } catch (Exception ignored) { }
        }
    }

    static void detach(Engine.Listener l) {
        if (ui == l) ui = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("bashtalk", MODE_PRIVATE);
        String id = prefs.getString("id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            prefs.edit().putString("id", id).apply();
        }
        engine = new Engine(this, id, prefs.getString("name", Build.MODEL), this);
        engine.channel = prefs.getInt("ch", 1);
        engine.gain = prefs.getFloat("gain", 1.5f);
        engine.sounds = prefs.getBoolean("sounds", true);
        engine.auto = prefs.getBoolean("auto", true);
        createChannels();
        inst = this;
        engine.start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (a == null) a = prefs.getBoolean("bg", false) ? ACTION_FG : "";
        switch (a) {
            case ACTION_FG:
                goForeground();
                break;
            case ACTION_BG:
                goBackground();
                break;
            case ACTION_TALK:
                engine.toggleTalk(prefs.getString("pttTarget", ""));
                break;
            case ACTION_STOP:
                goBackground();
                Engine.Listener l = ui;
                if (l != null) {
                    try { l.onEvent("stopped", new JSONObject()); } catch (Exception ignored) { }
                }
                stopSelf();
                return START_NOT_STICKY;
        }
        return foreground ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        inst = null;
        engine.stop();
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------ foreground

    private void goForeground() {
        Notification n = buildNotif();
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                    type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                startForeground(NOTIF_ID, n, type);
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            } else {
                startForeground(NOTIF_ID, n);
            }
            foreground = true;
            acquireLocks();
        } catch (Exception e) {
            try { startForeground(NOTIF_ID, n); foreground = true; acquireLocks(); } catch (Exception ignored) { }
        }
    }

    private void goBackground() {
        if (!foreground) return;
        foreground = false;
        try {
            if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE);
            else stopForeground(true);
        } catch (Exception ignored) { }
        releaseLocks();
    }

    private void acquireLocks() {
        try {
            if (wake == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bashtalk:engine");
                wake.setReferenceCounted(false);
            }
            wake.acquire();
        } catch (Exception ignored) { }
        try {
            if (wifiLock == null) {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wm.createWifiLock(mode, "bashtalk:wifi");
                wifiLock.setReferenceCounted(false);
            }
            wifiLock.acquire();
        } catch (Exception ignored) { }
    }

    private void releaseLocks() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ notifications

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Walkie-talkie u pozadini", NotificationManager.IMPORTANCE_LOW);
        s.setShowBadge(false);
        nm.createNotificationChannel(s);
        NotificationChannel m = new NotificationChannel(CH_MSG, "Poruke i pozivi", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(m);
    }

    private Notification.Builder builder(String ch) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, ch);
        return new Notification.Builder(this);
    }

    private PendingIntent svc(String action, int req) {
        Intent i = new Intent(this, TalkService.class).setAction(action);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification buildNotif() {
        int c = engine.peerCount();
        boolean t = engine.isTalking();
        String title = t ? "🎙️ Emitiraš…" : "#Bash Talk · kanal " + engine.channel;
        String text = c == 0 ? "Tražim uređaje u blizini…" : (c == 1 ? "1 uređaj u blizini" : c + " uređaja u blizini");
        Notification.Builder b = builder(CH_SERVICE)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openApp())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, t ? "⏹ Stop" : "🎙️ Pričaj", svc(ACTION_TALK, 1)).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Isključi", svc(ACTION_STOP, 2)).build());
        if (Build.VERSION.SDK_INT >= 21) b.setColor(0xFF8DFF1A);
        return b.build();
    }

    private void updateNotif() {
        if (!foreground) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.notify(NOTIF_ID, buildNotif());
        } catch (Exception ignored) { }
    }

    private void notifyMsg(String title, String text) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            Notification n = builder(CH_MSG)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openApp())
                    .setColor(0xFF8DFF1A)
                    .build();
            msgNotifId = msgNotifId >= 140 ? 100 : msgNotifId + 1;
            nm.notify(msgNotifId, n);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ Engine.Listener

    @Override
    public void onEvent(String type, JSONObject data) {
        if ("peers".equals(type) || "tx".equals(type)) {
            int c = engine.peerCount();
            boolean t = engine.isTalking();
            if (c != lastCount || t != lastTalk) {
                lastCount = c;
                lastTalk = t;
                updateNotif();
            }
        }
        if ("buzz".equals(type) || "msg".equals(type)) {
            vibrate("buzz".equals(type));
        }

        Engine.Listener l = ui;
        if (l != null) {
            l.onEvent(type, data);
            return;
        }
        // UI nije vidljiv: sačuvaj bitne događaje i pokaži obavještenje
        switch (type) {
            case "msg":
            case "buzz":
            case "join":
            case "leave":
            case "delivered":
                synchronized (backlog) {
                    if (backlog.size() > 300) backlog.remove(0);
                    backlog.add(new String[]{type, data.toString()});
                }
                break;
        }
        if ("msg".equals(type)) {
            String to = data.optString("to", "");
            notifyMsg((to.isEmpty() ? "💬 " : "🔒 ") + data.optString("name"), data.optString("text"));
        } else if ("buzz".equals(type)) {
            notifyMsg("📳 " + data.optString("name") + " te zove!", "Otvori #Bash Talk");
        }
    }

    private void vibrate(boolean strong) {
        if (!strong && !prefs.getBoolean("vib", true)) return;
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                v.vibrate(strong
                        ? android.os.VibrationEffect.createWaveform(new long[]{0, 300, 150, 300, 150, 600}, -1)
                        : android.os.VibrationEffect.createOneShot(60, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                v.vibrate(strong ? 1000 : 60);
            }
        } catch (Exception ignored) { }
    }
}
