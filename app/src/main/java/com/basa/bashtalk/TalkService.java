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
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.KeyEvent;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Drži Engine živim; u pozadinskom načinu radi kao foreground usluga.
 * Ovdje su i: SOS sirena, detekcija pada, barometar, dugme slušalica, web server.
 */
public class TalkService extends Service implements Engine.Listener, SensorHub.Sink, OnlineLink.Sink {

    static final String ACTION_FG = "com.basa.bashtalk.FG";
    static final String ACTION_BG = "com.basa.bashtalk.BG";
    static final String ACTION_TALK = "com.basa.bashtalk.TALK";
    static final String ACTION_STOP = "com.basa.bashtalk.STOP";
    static final String ACTION_FALL_OK = "com.basa.bashtalk.FALL_OK";
    static final String ACTION_MUTE = "com.basa.bashtalk.MUTE";

    static final String CH_SERVICE = "bt_service";
    static final String CH_MSG = "bt_msg";
    static final String CH_SOS = "bt_sos";
    static final int NOTIF_ID = 1;
    static final int NOTIF_SOS = 60;
    static final int NOTIF_FALL = 61;

    static volatile TalkService inst;
    static volatile Engine.Listener ui;
    private static final List<String[]> backlog = new ArrayList<>();

    Engine engine;
    SensorHub sensors;
    Siren siren;
    WebServer webServer;
    OnlineLink online;
    private MediaSession media;
    private SharedPreferences prefs;
    private boolean foreground;
    private PowerManager.WakeLock wake;
    private WifiManager.WifiLock wifiLock;
    private int lastCount = -1;
    private boolean lastTalk;
    private int msgNotifId = 100;
    private final Handler h = new Handler(Looper.getMainLooper());
    private int fallLeft = -1;
    private boolean sirenMuted;

    static Engine eng() {
        TalkService s = inst;
        return s == null ? null : s.engine;
    }

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
                for (int i = 100; i <= 140; i++) nm.cancel(i);
            } catch (Exception ignored) { }
            if (s.fallLeft >= 0) s.emitFall();
        }
    }

    static void detach(Engine.Listener l) {
        if (ui == l) ui = null;
    }

    // ------------------------------------------------------------ lifecycle

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
        if (!prefs.getBoolean("wfdMig1", false)) {
            // Wi-Fi Direct je sad isključen po defaultu (Samsung iskače s upozorenjima)
            prefs.edit().putBoolean("wfd", false).putBoolean("wfdMig1", true).apply();
        }
        engine.wfdEnabled = prefs.getBoolean("wfd", false);
        engine.mesh = prefs.getBoolean("mesh", true);
        engine.headsetMic = prefs.getBoolean("hsPtt", false);
        createChannels();
        inst = this;
        siren = new Siren(this);
        engine.start();
        sensors = new SensorHub(this, this);
        sensors.start(prefs.getBoolean("fall", false));
        setWeb(prefs.getBoolean("web", true));
        setHeadset(prefs.getBoolean("hsPtt", false));
        online = new OnlineLink(this, id, this);
        refreshOnline();
        if (prefs.getBoolean("online", true)) online.start();
        android.content.IntentFilter sf = new android.content.IntentFilter();
        sf.addAction(Intent.ACTION_SCREEN_OFF);
        sf.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenRx, sf);
        h.postDelayed(updTick, 60_000L);
    }

    private final Runnable updTick = new Runnable() {
        @Override
        public void run() {
            Updater.maybeCheck(TalkService.this, 6 * 3600_000L);
            h.postDelayed(this, 30 * 60_000L);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent != null ? intent.getAction() : null;
        if (a == null) a = prefs.getBoolean("bg", false) ? ACTION_FG : "";
        switch (a) {
            case ACTION_FG: goForeground(); break;
            case ACTION_BG: goBackground(); break;
            case ACTION_TALK: engine.toggleTalk(prefs.getString("pttTarget", "")); break;
            case ACTION_FALL_OK: fallOk(); break;
            case ACTION_MUTE: muteSiren(); break;
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

    private final android.content.BroadcastReceiver screenRx = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            engine.setLowPower(Intent.ACTION_SCREEN_OFF.equals(i.getAction()));
        }
    };

    /** Soba za online: grupa (ako je postavljena) ili javni kanal-N. */
    void refreshOnline() {
        if (online == null) return;
        String g = prefs.getString("ogroup", "");
        online.myName = engine.name;
        online.net = MainActivity.netTypeOf(this);
        online.setRoom(g.isEmpty() ? "kanal-" + engine.channel : g, prefs.getString("opass", ""));
    }

    void setOnline(boolean on) {
        if (online == null) return;
        if (on) online.start(); else online.stop();
    }

    // ------------------------------------------------------------ OnlineLink.Sink

    @Override
    public void onOnlineMsg(JSONObject m, boolean dm) {
        try {
            if (!dm && prefs.getBoolean("gw", true) && m.has("text") && !m.has("gw")) engine.injectOnline(m);
            JSONObject ev = new JSONObject();
            ev.put("m", m);
            onEvent("onlineMsg", ev);
        } catch (Exception ignored) { }
    }

    @Override
    public void onOnlineAudio(String id, String name, byte[] ulaw) {
        engine.playExternal(ulaw, id, name);
    }

    @Override
    public void onOnlineState(boolean on) {
        engine.onlineAudio = on;
        if (on) online.net = MainActivity.netTypeOf(this);
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try { unregisterReceiver(screenRx); } catch (Exception ignored) { }
        try { if (online != null) online.stop(); } catch (Exception ignored) { }
        inst = null;
        try { sensors.stop(); } catch (Exception ignored) { }
        try { siren.stop(); } catch (Exception ignored) { }
        setWeb(false);
        setHeadset(false);
        engine.stop();
        releaseLocks();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ------------------------------------------------------------ opcije

    void setWeb(boolean on) {
        if (on) {
            if (webServer == null) webServer = new WebServer(this, engine);
            webServer.start();
        } else if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
    }

    void setFall(boolean on) { if (sensors != null) sensors.setFall(on); }

    boolean hasBarometer() { return sensors != null && sensors.hasBarometer(); }

    /** Dugme na (Bluetooth) slušalicama = pričaj/stop. */
    void setHeadset(boolean on) {
        engine.headsetMic = on;
        if (on && media == null) {
            try {
                media = new MediaSession(this, "bashtalk");
                media.setCallback(new MediaSession.Callback() {
                    @Override
                    public boolean onMediaButtonEvent(Intent i) {
                        KeyEvent k = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                        if (k == null) return false;
                        int c = k.getKeyCode();
                        boolean ours = c == KeyEvent.KEYCODE_HEADSETHOOK || c == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                                || c == KeyEvent.KEYCODE_MEDIA_PLAY || c == KeyEvent.KEYCODE_MEDIA_PAUSE;
                        if (!ours) return false;
                        if (k.getAction() == KeyEvent.ACTION_DOWN && k.getRepeatCount() == 0) {
                            engine.toggleTalk(prefs.getString("pttTarget", ""));
                        }
                        return true;
                    }
                });
                media.setPlaybackState(new PlaybackState.Builder()
                        .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE)
                        .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                        .build());
                media.setActive(true);
            } catch (Exception ignored) { }
        } else if (!on && media != null) {
            try { media.setActive(false); media.release(); } catch (Exception ignored) { }
            media = null;
        }
    }

    // ------------------------------------------------------------ senzori

    @Override
    public void onPressure(float hPa) {
        engine.pressure = hPa;
        if (prefs.contains("flRef") && prefs.contains("pRef")) {
            int ref = prefs.getInt("flRef", 0);
            float pRef = prefs.getFloat("pRef", hPa);
            engine.floor = Math.round(ref + (pRef - hPa) * 8.3f / 3.2f);
        }
    }

    /** Kalibracija: "sad sam na spratu X". */
    void setFloor(Integer fl) {
        if (fl == null) {
            prefs.edit().remove("flRef").remove("pRef").apply();
            engine.floor = Integer.MIN_VALUE;
            return;
        }
        if (Float.isNaN(engine.pressure)) return;
        prefs.edit().putInt("flRef", fl).putFloat("pRef", engine.pressure).apply();
        engine.floor = fl;
    }

    @Override
    public void onFall() {
        h.post(() -> {
            if (fallLeft >= 0 || engine.isSos()) return;
            fallLeft = 30;
            fallTick.run();
        });
    }

    private final Runnable fallTick = new Runnable() {
        @Override
        public void run() {
            if (fallLeft < 0) return;
            if (fallLeft == 0) {
                fallLeft = -1;
                cancelNotif(NOTIF_FALL);
                engine.startSos("Pad – nema pokreta");
                emitFall();
                return;
            }
            try {
                ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 400);
                h.postDelayed(tg::release, 600);
            } catch (Exception ignored) { }
            vibrate(new long[]{0, 400});
            notifyFall();
            emitFall();
            fallLeft--;
            h.postDelayed(this, 1000);
        }
    };

    void fallOk() {
        fallLeft = -1;
        h.removeCallbacks(fallTick);
        cancelNotif(NOTIF_FALL);
        emitFall();
    }

    private void emitFall() {
        Engine.Listener l = ui;
        if (l == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("left", fallLeft);
            l.onEvent("fall", o);
        } catch (Exception ignored) { }
    }

    void muteSiren() {
        sirenMuted = true;
        siren.stop();
        Vibrator v = vib();
        if (v != null) v.cancel();
    }

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
            try {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
                else startForeground(NOTIF_ID, n);
                foreground = true;
                acquireLocks();
            } catch (Exception ignored) { }
        }
    }

    private void goBackground() {
        if (!foreground) return;
        foreground = false;
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) { }
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

    // ------------------------------------------------------------ obavještenja

    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel s = new NotificationChannel(CH_SERVICE, "Walkie-talkie u pozadini", NotificationManager.IMPORTANCE_LOW);
        s.setShowBadge(false);
        nm.createNotificationChannel(s);
        nm.createNotificationChannel(new NotificationChannel(CH_MSG, "Poruke i pozivi", NotificationManager.IMPORTANCE_HIGH));
        NotificationChannel sos = new NotificationChannel(CH_SOS, "SOS i pad", NotificationManager.IMPORTANCE_HIGH);
        sos.setBypassDnd(true);
        sos.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(sos);
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String ch) {
        if (Build.VERSION.SDK_INT >= 26) return new Notification.Builder(this, ch);
        return new Notification.Builder(this);
    }

    private PendingIntent svc(String action, int req) {
        Intent i = new Intent(this, TalkService.class).setAction(action);
        return PendingIntent.getService(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private PendingIntent openApp(String extra) {
        Intent i = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        if (extra != null) i.putExtra(extra, true);
        return PendingIntent.getActivity(this, extra == null ? 0 : extra.hashCode() & 0xffff, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
                .setColor(0xFF8DFF1A)
                .setContentIntent(openApp(null))
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, t ? "⏹ Stop" : "🎙️ Pričaj", svc(ACTION_TALK, 1)).build())
                .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "Isključi", svc(ACTION_STOP, 2)).build());
        return b.build();
    }

    private void updateNotif() {
        if (!foreground) return;
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotif()); } catch (Exception ignored) { }
    }

    private void cancelNotif(int id) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(id); } catch (Exception ignored) { }
    }

    private void notifyMsg(String title, String text) {
        try {
            Notification n = builder(CH_MSG)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(null))
                    .setColor(0xFF8DFF1A)
                    .build();
            msgNotifId = msgNotifId >= 140 ? 100 : msgNotifId + 1;
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(msgNotifId, n);
        } catch (Exception ignored) { }
    }

    private void notifySos(String who, String reason) {
        try {
            Notification n = builder(CH_SOS)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("🆘 SOS – " + who)
                    .setContentText(reason == null || reason.isEmpty() ? "Treba pomoć! Dodirni za strelicu do njega." : reason)
                    .setColor(0xFFFF3B4E)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setPriority(Notification.PRIORITY_MAX)
                    .setContentIntent(openApp("openSos"))
                    .setFullScreenIntent(openApp("openSos"), true)
                    .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "🔇 Utišaj", svc(ACTION_MUTE, 5)).build())
                    .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_SOS, n);
        } catch (Exception ignored) { }
    }

    private void notifyFall() {
        try {
            Notification n = builder(CH_SOS)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle("⚠️ Pad detektovan – jesi li dobro?")
                    .setContentText("SOS se šalje za " + fallLeft + " s")
                    .setColor(0xFFFFD21A)
                    .setOnlyAlertOnce(true)
                    .setCategory(Notification.CATEGORY_ALARM)
                    .setContentIntent(openApp(null))
                    .setFullScreenIntent(openApp(null), true)
                    .addAction(new Notification.Action.Builder((android.graphics.drawable.Icon) null, "✅ Dobro sam", svc(ACTION_FALL_OK, 4)).build())
                    .build();
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_FALL, n);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ Engine.Listener

    @Override
    public void onEvent(String type, JSONObject data) {
        switch (type) {
            case "peers":
            case "tx": {
                int c = engine.peerCount();
                boolean t = engine.isTalking();
                if (c != lastCount || t != lastTalk) {
                    lastCount = c;
                    lastTalk = t;
                    updateNotif();
                }
                break;
            }
            case "buzz": vibrate(new long[]{0, 300, 150, 300, 150, 600}); break;
            case "otx":
                if (online != null) try { online.sendAudio(android.util.Base64.decode(data.optString("b64"), android.util.Base64.DEFAULT)); } catch (Exception ignored) { }
                return;
            case "onlineMsg":
                if (prefs.getBoolean("vib", true)) vibrate(new long[]{0, 60});
                break;
            case "msg":
                // most: poruka iz blizine → i online (za one daleko)
                if (online != null && online.isConnected() && prefs.getBoolean("gw", true)
                        && data.optInt("on") != 1 && data.optString("to", "").isEmpty()) {
                    try {
                        JSONObject m = new JSONObject();
                        m.put("t", "msg");
                        m.put("mid", data.optString("mid"));
                        m.put("id", "a" + data.optString("id"));
                        m.put("name", data.optString("name"));
                        m.put("text", data.optString("text"));
                        m.put("ts", data.optLong("ts", System.currentTimeMillis()));
                        m.put("gw", engine.name);
                        online.sendRoomMsg(m);
                    } catch (Exception ignored) { }
                }
                if (prefs.getBoolean("vib", true)) vibrate(new long[]{0, 60});
                break;
            case "media":
                if (prefs.getBoolean("vib", true)) vibrate(new long[]{0, 60});
                break;
            case "sos":
                sirenMuted = false;
                if (!siren.isOn()) siren.start();
                vibrate(new long[]{0, 800, 300, 800, 300, 800, 300, 800});
                notifySos(data.optString("name"), data.optString("reason"));
                break;
            case "sosOff":
                if (engine.sosMap.isEmpty()) {
                    siren.stop();
                    cancelNotif(NOTIF_SOS);
                }
                break;
        }

        Engine.Listener l = ui;
        if (l != null) {
            l.onEvent(type, data);
            return;
        }
        switch (type) {
            case "msg": case "media": case "buzz": case "join": case "leave": case "delivered": case "onlineMsg":
            case "sos": case "sosOff": case "sosack": case "tasks": case "hist": case "mySos":
                synchronized (backlog) {
                    if (backlog.size() > 400) backlog.remove(0);
                    backlog.add(new String[]{type, data.toString()});
                }
                break;
        }
        if ("msg".equals(type)) {
            String to = data.optString("to", "");
            notifyMsg((to.isEmpty() ? "💬 " : "🔒 ") + data.optString("name"), data.optString("text"));
        } else if ("media".equals(type)) {
            notifyMsg(("img".equals(data.optString("kind")) ? "📷 " : "🎤 ") + data.optString("name"),
                    "img".equals(data.optString("kind")) ? "Poslao/la je sliku" : "Glasovna poruka");
        } else if ("buzz".equals(type)) {
            notifyMsg("📳 " + data.optString("name") + " te zove!", "Otvori #Bash Talk");
        } else if ("onlineMsg".equals(type)) {
            JSONObject m = data.optJSONObject("m");
            if (m != null) {
                String body = m.has("img") ? "📷 Slika" : m.has("voice") ? "🎤 Glasovna poruka" : m.optString("text");
                notifyMsg((m.optBoolean("dm") ? "🔒 " : "🌍 ") + m.optString("name"), body);
            }
        } else if ("sosack".equals(type)) {
            notifyMsg("🆘 " + data.optString("name"), "Ide u pomoć!");
        }
    }

    private Vibrator vib() {
        try { return (Vibrator) getSystemService(Context.VIBRATOR_SERVICE); } catch (Exception e) { return null; }
    }

    @SuppressWarnings("deprecation")
    private void vibrate(long[] pattern) {
        Vibrator v = vib();
        if (v == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
            else v.vibrate(pattern, -1);
        } catch (Exception ignored) { }
    }
}
