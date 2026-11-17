package com.basa.bashtalk;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements Engine.Listener {

    private WebView web;
    private SharedPreferences prefs;
    private volatile boolean ready, started;
    private final Handler main = new Handler(Looper.getMainLooper());
    static volatile boolean visible;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        prefs = getSharedPreferences("bashtalk", MODE_PRIVATE);
        applyKeepScreen(prefs.getBoolean("keepOn", true));

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        requestNeeded();
        startEngine();
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        visible = true;
        if (TalkService.inst == null) startEngine();
        if (ready) attachAll();
    }

    @Override
    protected void onStop() {
        started = false;
        visible = false;
        TalkService.detach(this);
        if (Updater.ui == this) Updater.ui = null;
        if (!prefs.getBoolean("bg", false) && !isChangingConfigurations()) {
            stopService(new Intent(this, TalkService.class));
        }
        super.onStop();
    }

    private void attachAll() {
        TalkService.attach(this);
        Updater.ui = this;
        Updater.maybeCheck(this, 30 * 60_000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra("openUpdate", false) && ready) {
            main.post(() -> web.evaluateJavascript("window.openUpdate && window.openUpdate()", null));
        }
    }

    private void startEngine() {
        Intent i = new Intent(this, TalkService.class);
        if (prefs.getBoolean("bg", false)) {
            i.setAction(TalkService.ACTION_FG);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } else {
            startService(i);
        }
    }

    // ------------------------------------------------------------ dozvole

    private String[] neededPerms() {
        List<String> l = new ArrayList<>();
        l.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN);
            l.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            l.add(Manifest.permission.BLUETOOTH_CONNECT);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            l.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            l.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            l.add(Manifest.permission.ACCESS_FINE_LOCATION);
            l.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        return l.toArray(new String[0]);
    }

    private boolean allGranted() {
        for (String p : neededPerms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void requestNeeded() {
        List<String> miss = new ArrayList<>();
        for (String p : neededPerms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) miss.add(p);
        if (!miss.isEmpty()) requestPermissions(miss.toArray(new String[0]), 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        Engine e = TalkService.eng();
        if (e != null && e.auto) e.startNearby();     // ponovo pokreni nakon dozvola
        if (prefs.getBoolean("bg", false)) startEngine();  // osvježi tip foreground usluge (mikrofon)
    }

    // ------------------------------------------------------------ tipke / back

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);
        });
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        Engine e = TalkService.eng();
        if (e != null && prefs.getBoolean("volPtt", true) && ev.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN && ev.getRepeatCount() == 0) {
                e.startTalk(prefs.getString("pttTarget", ""));
            } else if (ev.getAction() == KeyEvent.ACTION_UP) {
                e.stopTalk();
            }
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    private void applyKeepScreen(boolean on) {
        runOnUiThread(() -> {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    // ------------------------------------------------------------ događaji → JS

    @Override
    public void onEvent(String type, JSONObject data) {
        if ("stopped".equals(type)) {
            runOnUiThread(this::finishAndRemoveTask);
            return;
        }
        if (!ready) return;
        final String js = "window.onEvent && window.onEvent(" + JSONObject.quote(type) + "," + data.toString() + ")";
        main.post(() -> web.evaluateJavascript(js, null));
    }

    // ------------------------------------------------------------ JS bridge

    class Bridge {
        @JavascriptInterface
        public void ready() {
            ready = true;
            main.post(() -> { if (started) attachAll(); });
        }

        @JavascriptInterface
        public String getState() {
            try {
                Engine e = TalkService.eng();
                JSONObject o = new JSONObject();
                o.put("name", e != null ? e.name : prefs.getString("name", Build.MODEL));
                o.put("ch", prefs.getInt("ch", 1));
                o.put("gain", prefs.getFloat("gain", 1.5f));
                o.put("sounds", prefs.getBoolean("sounds", true));
                o.put("keepOn", prefs.getBoolean("keepOn", true));
                o.put("volPtt", prefs.getBoolean("volPtt", true));
                o.put("vib", prefs.getBoolean("vib", true));
                o.put("auto", prefs.getBoolean("auto", true));
                o.put("bg", prefs.getBoolean("bg", false));
                o.put("ips", Engine.localIps());
                o.put("micOk", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                o.put("permsOk", allGranted());
                o.put("upd", prefs.getString("upd", "auto"));
                o.put("version", Updater.currentName(MainActivity.this));
                o.put("build", Updater.currentCode(MainActivity.this));
                return o.toString();
            } catch (Exception ex) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setName(String n) {
            n = n == null ? "" : n.trim();
            if (n.isEmpty()) n = Build.MODEL;
            if (n.length() > 24) n = n.substring(0, 24);
            prefs.edit().putString("name", n).apply();
            Engine e = TalkService.eng();
            if (e != null) {
                e.name = n;
                if (e.auto) e.startNearby();
            }
        }

        @JavascriptInterface
        public void setChannel(int ch) {
            ch = Math.max(1, Math.min(99, ch));
            prefs.edit().putInt("ch", ch).apply();
            Engine e = TalkService.eng();
            if (e != null) e.channel = ch;
        }

        @JavascriptInterface
        public void setGain(float g) {
            prefs.edit().putFloat("gain", g).apply();
            Engine e = TalkService.eng();
            if (e != null) e.gain = g;
        }

        @JavascriptInterface
        public void setOption(String key, boolean v) {
            prefs.edit().putBoolean(key, v).apply();
            Engine e = TalkService.eng();
            switch (key) {
                case "keepOn": applyKeepScreen(v); break;
                case "sounds": if (e != null) e.sounds = v; break;
                case "auto": if (e != null) e.setAuto(v); break;
                case "bg": {
                    Intent i = new Intent(MainActivity.this, TalkService.class);
                    if (v) {
                        i.setAction(TalkService.ACTION_FG);
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                    } else {
                        i.setAction(TalkService.ACTION_BG);
                        startService(i);
                    }
                    break;
                }
            }
        }

        @JavascriptInterface
        public String sendChat(String text, String to) {
            Engine e = TalkService.eng();
            return e != null ? e.sendChat(text, to) : "";
        }

        @JavascriptInterface
        public void startTalk(String target) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                main.post(MainActivity.this::requestNeeded);
                return;
            }
            Engine e = TalkService.eng();
            if (e != null) e.startTalk(target);
        }

        @JavascriptInterface
        public void stopTalk() {
            Engine e = TalkService.eng();
            if (e != null) e.stopTalk();
        }

        @JavascriptInterface
        public void setPttTarget(String t) { prefs.edit().putString("pttTarget", t == null ? "" : t).apply(); }

        @JavascriptInterface
        public void buzz(String id) {
            Engine e = TalkService.eng();
            if (e != null) e.buzz(id);
        }

        @JavascriptInterface
        public void vib(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                else v.vibrate(ms);
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void setUpdMode(String m) { prefs.edit().putString("upd", m).apply(); }

        @JavascriptInterface
        public void checkUpdate() { Updater.checkAsync(MainActivity.this, true); }

        @JavascriptInterface
        public void installUpdate() {
            if (!Updater.canInstall(MainActivity.this)) { allowInstalls(); return; }
            Updater.installReady(MainActivity.this);
        }

        @JavascriptInterface
        public void snoozeUpdate() { Updater.snooze(MainActivity.this); }

        @JavascriptInterface
        public void allowInstalls() {
            main.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ex) { openAppSettings(); }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            main.post(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void askPerms() { main.post(MainActivity.this::requestNeeded); }

        @JavascriptInterface
        public void openHotspot() {
            main.post(() -> {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
                try { startActivity(i); return; } catch (Exception ignored) { }
                try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void openWifi() {
            main.post(() -> { try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) { } });
        }

        @JavascriptInterface
        public void openBluetooth() {
            main.post(() -> { try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { } });
        }

        @JavascriptInterface
        public void openLocation() {
            main.post(() -> { try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (Exception ignored) { } });
        }

        @JavascriptInterface
        public void openAppSettings() {
            main.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void batteryOpt() {
            main.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ex) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
                }
            });
        }
    }
}
