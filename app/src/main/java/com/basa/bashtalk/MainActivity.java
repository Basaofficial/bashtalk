package com.basa.bashtalk;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONObject;

import java.util.UUID;

public class MainActivity extends Activity implements Engine.Listener {

    private WebView web;
    private Engine engine;
    private SharedPreferences prefs;
    private volatile boolean ready;
    private boolean volPtt = true;
    private String pttTarget = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);

        prefs = getSharedPreferences("bashtalk", MODE_PRIVATE);
        String id = prefs.getString("id", null);
        if (id == null) {
            id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            prefs.edit().putString("id", id).apply();
        }
        String name = prefs.getString("name", Build.MODEL);
        engine = new Engine(this, id, name, this);
        engine.channel = prefs.getInt("ch", 1);
        engine.gain = prefs.getFloat("gain", 1.5f);
        engine.sounds = prefs.getBoolean("sounds", true);
        volPtt = prefs.getBoolean("volPtt", true);
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

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
        }
        engine.start();
    }

    @Override
    protected void onDestroy() {
        engine.stop();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.onBack && window.onBack()", v -> {
            if (!"true".equals(v)) moveTaskToBack(true);   // ostaje aktivan u pozadini
        });
    }

    // Tipka za smanjenje glasnoće = pričaj (drži)
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (volPtt && e.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) {
                engine.startTalk(pttTarget);
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                engine.stopTalk();
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void applyKeepScreen(boolean on) {
        runOnUiThread(() -> {
            if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    private void vibrate(long ms) {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            else v.vibrate(ms);
        } catch (Exception ignored) { }
    }

    @Override
    public void onEvent(String type, JSONObject data) {
        if ("buzz".equals(type)) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v != null && Build.VERSION.SDK_INT >= 26)
                    v.vibrate(VibrationEffect.createWaveform(new long[]{0, 300, 150, 300, 150, 600}, -1));
                else vibrate(1000);
            } catch (Exception ignored) { }
        }
        if (("msg".equals(type) || "join".equals(type)) && prefs.getBoolean("vib", true)) {
            vibrate("msg".equals(type) ? 60 : 30);
        }
        if (!ready) return;
        final String js = "window.onEvent && window.onEvent(" + JSONObject.quote(type) + "," + data.toString() + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    // ------------------------------------------------------------ JS bridge

    class Bridge {
        @JavascriptInterface
        public void ready() { ready = true; }

        @JavascriptInterface
        public String getState() {
            try {
                JSONObject o = new JSONObject();
                o.put("id", engine.myId);
                o.put("name", engine.name);
                o.put("ch", engine.channel);
                o.put("gain", engine.gain);
                o.put("sounds", engine.sounds);
                o.put("keepOn", prefs.getBoolean("keepOn", true));
                o.put("volPtt", volPtt);
                o.put("vib", prefs.getBoolean("vib", true));
                o.put("ips", Engine.localIps());
                o.put("micOk", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void setName(String n) {
            n = n == null ? "" : n.trim();
            if (n.isEmpty()) n = Build.MODEL;
            if (n.length() > 24) n = n.substring(0, 24);
            engine.name = n;
            prefs.edit().putString("name", n).apply();
        }

        @JavascriptInterface
        public void setChannel(int ch) {
            ch = Math.max(1, Math.min(99, ch));
            engine.channel = ch;
            prefs.edit().putInt("ch", ch).apply();
        }

        @JavascriptInterface
        public void setGain(float g) {
            engine.gain = g;
            prefs.edit().putFloat("gain", g).apply();
        }

        @JavascriptInterface
        public void setOption(String key, boolean v) {
            prefs.edit().putBoolean(key, v).apply();
            switch (key) {
                case "keepOn": applyKeepScreen(v); break;
                case "volPtt": volPtt = v; break;
                case "sounds": engine.sounds = v; break;
            }
        }

        @JavascriptInterface
        public String sendChat(String text, String to) { return engine.sendChat(text, to); }

        @JavascriptInterface
        public void startTalk(String target) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1));
                return;
            }
            engine.startTalk(target);
        }

        @JavascriptInterface
        public void stopTalk() { engine.stopTalk(); }

        @JavascriptInterface
        public void setPttTarget(String t) { pttTarget = t == null ? "" : t; }

        @JavascriptInterface
        public void buzz(String id) { engine.buzz(id); }

        @JavascriptInterface
        public void vib(int ms) { vibrate(ms); }

        @JavascriptInterface
        public void openHotspot() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.setClassName("com.android.settings", "com.android.settings.TetherSettings");
                try { startActivity(i); return; } catch (Exception ignored) { }
                try { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void openWifi() {
            runOnUiThread(() -> {
                try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (ActivityNotFoundException ignored) { }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
            });
        }
    }
}
