package com.basa.bashtalk;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.core.content.FileProvider;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity implements Engine.Listener {

    static final int REQ_PERMS = 1, REQ_GALLERY = 11, REQ_CAMERA = 12;

    private WebView web;
    private SharedPreferences prefs;
    private volatile boolean ready, started;
    private final Handler main = new Handler(Looper.getMainLooper());
    static volatile boolean visible;
    private File camFile;
    static volatile String cellGen = "";
    private String pickTo = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true); }
        prefs = getSharedPreferences("bashtalk", MODE_PRIVATE);
        applyKeepScreen(prefs.getBoolean("keepOn", true));

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final android.webkit.PermissionRequest req) {
                // samo naša online stranica smije tražiti mikrofon
                String origin = req.getOrigin() != null ? req.getOrigin().toString() : "";
                boolean ok = origin.startsWith("https://basaofficial.github.io");
                runOnUiThread(() -> {
                    if (ok && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        req.deny();
                        if (ok) requestNeeded();
                    }
                });
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        requestNeeded();
        startEngine();
        handleIntent(getIntent());
        watchCellGen();
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
        if (!prefs.getBoolean("bg", false) && !isChangingConfigurations() && !picking) {
            stopService(new Intent(this, TalkService.class));
        }
        super.onStop();
    }

    private boolean picking;

    private void attachAll() {
        TalkService.attach(this);
        Updater.ui = this;
        Updater.maybeCheck(this, 30 * 60_000L);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (intent.getBooleanExtra("openUpdate", false)) js("window.openUpdate && window.openUpdate()");
        if (intent.getBooleanExtra("openSos", false)) js("window.openSos && window.openSos()");
    }

    private void js(String code) {
        main.postDelayed(() -> { if (ready) web.evaluateJavascript(code, null); }, ready ? 0 : 1500);
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

    // ------------------------------------------------------------ vrsta mreže (4G/5G/Wi-Fi)

    private void watchCellGen() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            TelephonyManager tm = getSystemService(TelephonyManager.class);
            tm.registerTelephonyCallback(getMainExecutor(), new GenCallback());
        } catch (Throwable ignored) { }
    }

    @android.annotation.TargetApi(31)
    static class GenCallback extends android.telephony.TelephonyCallback implements android.telephony.TelephonyCallback.DisplayInfoListener {
        @Override
        public void onDisplayInfoChanged(TelephonyDisplayInfo d) {
            int o = d.getOverrideNetworkType(), t = d.getNetworkType();
            if (t == TelephonyManager.NETWORK_TYPE_NR || o == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA
                    || o == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED) cellGen = "5G";
            else if (t == TelephonyManager.NETWORK_TYPE_LTE || o == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA
                    || o == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO) cellGen = "4G";
            else if (t == TelephonyManager.NETWORK_TYPE_UNKNOWN) cellGen = "";
            else cellGen = "3G";
        }
    }

    @SuppressWarnings("deprecation")
    String netType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            if (nc == null) return "";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "LAN";
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                if (!cellGen.isEmpty()) return cellGen;
                if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                    int t = ((TelephonyManager) getSystemService(TELEPHONY_SERVICE)).getDataNetworkType();
                    if (t == TelephonyManager.NETWORK_TYPE_NR) return "5G";
                    if (t == TelephonyManager.NETWORK_TYPE_LTE) return "4G";
                    return "3G";
                }
                return "mobilni";
            }
        } catch (Exception ignored) { }
        return "";
    }

    // ------------------------------------------------------------ dozvole

    private String[] neededPerms() {
        List<String> l = new ArrayList<>();
        l.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 31) {
            l.add(Manifest.permission.BLUETOOTH_SCAN);
            l.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            l.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (UwbLink.hasHardware(this)) l.add(Manifest.permission.UWB_RANGING);
        }
        l.add(Manifest.permission.READ_PHONE_STATE);
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
        if (!miss.isEmpty()) requestPermissions(miss.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        Engine e = TalkService.eng();
        if (e != null && e.auto) e.startNearby();
        if (e != null && e.wfdEnabled) e.startWfd();
        if (prefs.getBoolean("bg", false)) startEngine();
        watchCellGen();
    }

    // ------------------------------------------------------------ slike

    private void pickImage(boolean camera, String to) {
        pickTo = to == null ? "" : to;
        picking = true;
        try {
            if (camera) {
                File d = new File(getCacheDir(), "cam");
                d.mkdirs();
                camFile = new File(d, "cam_" + System.currentTimeMillis() + ".jpg");
                Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", camFile);
                Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, u);
                i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_CAMERA);
            } else {
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.setType("image/*");
                startActivityForResult(Intent.createChooser(i, "Odaberi sliku"), REQ_GALLERY);
            }
        } catch (Exception ex) {
            picking = false;
            toastJs("Ne mogu otvoriti " + (camera ? "kameru" : "galeriju"));
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        picking = false;
        if (res != RESULT_OK) return;
        final Uri uri = req == REQ_CAMERA ? (camFile != null ? Uri.fromFile(camFile) : null)
                : (data != null ? data.getData() : null);
        if (uri == null) return;
        final String to = pickTo;
        new Thread(() -> {
            try {
                byte[] jpg = compress(uri);
                Engine e = TalkService.eng();
                if (e == null || jpg == null) return;
                JSONObject meta = new JSONObject();
                JSONObject r = e.sendBlob("img", jpg, meta, to, e.myId, e.name);
                r.put("kind", "img");
                r.put("to", to);
                r.put("b64", android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP));
                onEvent("ownMedia", r);
                if (camFile != null) camFile.delete();
            } catch (Exception ex) {
                toastJs("Slika nije poslana: " + ex.getMessage());
            }
        }).start();
    }

    private InputStream open(Uri u) throws Exception {
        if ("file".equals(u.getScheme())) return new java.io.FileInputStream(u.getPath());
        return getContentResolver().openInputStream(u);
    }

    private byte[] compress(Uri uri) throws Exception {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = open(uri)) { BitmapFactory.decodeStream(in, null, o); }
        int max = Math.max(o.outWidth, o.outHeight);
        int sample = 1;
        while (max / (sample * 2) >= 1280) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = open(uri)) { bmp = BitmapFactory.decodeStream(in, null, o2); }
        if (bmp == null) return null;
        int rot = 0;
        try (InputStream in = open(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Exception ignored) { }
        float scale = Math.min(1f, 1280f / Math.max(bmp.getWidth(), bmp.getHeight()));
        Matrix m = new Matrix();
        m.postScale(scale, scale);
        if (rot != 0) m.postRotate(rot);
        Bitmap out = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.JPEG, 70, bo);
        return bo.toByteArray();
    }

    private void toastJs(String t) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg", t);
            onEvent("error", o);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ tipke / back

    @Override
    @SuppressWarnings("deprecation")
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
        final String code = "window.onEvent && window.onEvent(" + JSONObject.quote(type) + "," + data.toString() + ")";
        main.post(() -> web.evaluateJavascript(code, null));
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
                TalkService svc = TalkService.inst;
                JSONObject o = new JSONObject();
                o.put("id", e != null ? e.myId : prefs.getString("id", ""));
                o.put("name", e != null ? e.name : prefs.getString("name", Build.MODEL));
                o.put("ch", prefs.getInt("ch", 1));
                o.put("gain", prefs.getFloat("gain", 1.5f));
                for (String k : new String[]{"sounds", "keepOn", "volPtt", "vib", "auto", "mesh", "web"})
                    o.put(k, prefs.getBoolean(k, true));
                for (String k : new String[]{"bg", "fall", "hsPtt", "wfd"})
                    o.put(k, prefs.getBoolean(k, false));
                o.put("ips", Engine.localIps());
                o.put("micOk", checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED);
                o.put("permsOk", allGranted());
                o.put("upd", prefs.getString("upd", "auto"));
                o.put("version", Updater.currentName(MainActivity.this));
                o.put("build", Updater.currentCode(MainActivity.this));
                o.put("hasUwb", UwbLink.hasHardware(MainActivity.this));
                o.put("hasWfd", WifiDirectLink.supported(MainActivity.this));
                o.put("hasBaro", svc != null && svc.hasBarometer());
                if (prefs.contains("flRef")) o.put("flRef", prefs.getInt("flRef", 0));
                o.put("wfdSsid", WifiDirectLink.SSID);
                o.put("wfdPass", WifiDirectLink.PASS);
                o.put("webPort", WebServer.PORT);
                o.put("sos", e != null && e.isSos());
                o.put("ogroup", prefs.getString("ogroup", ""));
                o.put("opass", prefs.getString("opass", ""));
                o.put("online", prefs.getBoolean("online", true));
                o.put("gw", prefs.getBoolean("gw", true));
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
            TalkService svc = TalkService.inst;
            switch (key) {
                case "keepOn": applyKeepScreen(v); break;
                case "sounds": if (e != null) e.sounds = v; break;
                case "auto": if (e != null) e.setAuto(v); break;
                case "wfd": if (e != null) e.setWfd(v); break;
                case "mesh": if (e != null) e.mesh = v; break;
                case "fall": if (svc != null) main.post(() -> svc.setFall(v)); break;
                case "hsPtt": if (svc != null) main.post(() -> svc.setHeadset(v)); break;
                case "web": if (svc != null) main.post(() -> svc.setWeb(v)); break;
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

        // ---- chat / mediji
        @JavascriptInterface
        public String sendChat(String text, String to) {
            Engine e = TalkService.eng();
            return e != null ? e.sendChat(text, to) : "";
        }

        @JavascriptInterface
        public void pickImage(boolean camera, String to) { main.post(() -> MainActivity.this.pickImage(camera, to)); }

        @JavascriptInterface
        public boolean startNote() {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                main.post(MainActivity.this::requestNeeded);
                return false;
            }
            Engine e = TalkService.eng();
            return e != null && e.startVoiceNote();
        }

        @JavascriptInterface
        public String stopNote(boolean cancel, String to) {
            Engine e = TalkService.eng();
            return e != null ? e.stopVoiceNote(cancel, to).toString() : "{}";
        }

        // ---- walkie
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
        public String getHistory() {
            Engine e = TalkService.eng();
            return e != null ? e.historyJson().toString() : "{\"list\":[]}";
        }

        // ---- SOS / pad
        @JavascriptInterface
        public void sos(boolean on, String reason) {
            Engine e = TalkService.eng();
            if (e == null) return;
            if (on) e.startSos(reason); else e.stopSos();
        }

        @JavascriptInterface
        public void sosAck(String id) {
            Engine e = TalkService.eng();
            if (e != null) e.ackSos(id);
            muteSiren();
        }

        @JavascriptInterface
        public void muteSiren() {
            TalkService s = TalkService.inst;
            if (s != null) main.post(s::muteSiren);
        }

        @JavascriptInterface
        public String sosList() {
            Engine e = TalkService.eng();
            return e != null ? new org.json.JSONArray(e.sosMap.values()).toString() : "[]";
        }

        @JavascriptInterface
        public void fallOk() {
            TalkService s = TalkService.inst;
            if (s != null) main.post(s::fallOk);
        }

        // ---- zadaci
        @JavascriptInterface
        public String getTasks() {
            Engine e = TalkService.eng();
            return e != null ? e.tasksJson().toString() : "{\"list\":[]}";
        }

        @JavascriptInterface
        public void saveTask(String json) {
            Engine e = TalkService.eng();
            if (e == null) return;
            try { e.saveTask(new JSONObject(json)); } catch (Exception ignored) { }
        }

        // ---- sprat
        @JavascriptInterface
        public boolean setFloor(int fl) {
            TalkService s = TalkService.inst;
            Engine e = TalkService.eng();
            if (s == null || e == null || Float.isNaN(e.pressure)) return false;
            s.setFloor(fl);
            return true;
        }

        @JavascriptInterface
        public void clearFloor() {
            TalkService s = TalkService.inst;
            if (s != null) s.setFloor(null);
        }

        // ---- ostalo
        @JavascriptInterface
        public void vib(int ms) {
            try {
                Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                if (v == null) return;
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String netType() { return MainActivity.this.netType(); }

        @JavascriptInterface
        public void playOnline(String b64, String id, String name) {
            Engine e = TalkService.eng();
            if (e == null) return;
            try { e.playExternal(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), id, name); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public void injectOnline(String json) {
            Engine e = TalkService.eng();
            if (e == null) return;
            try { e.injectOnline(new JSONObject(json)); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String saveOnlineMedia(String kind, String b64) {
            Engine e = TalkService.eng();
            if (e == null) return "";
            try { return e.saveOnlineMedia(kind, android.util.Base64.decode(b64, android.util.Base64.DEFAULT)); } catch (Exception ex) { return ""; }
        }

        @JavascriptInterface
        public void setOnlineAudio(boolean on) {
            Engine e = TalkService.eng();
            if (e != null) e.onlineAudio = on;
        }

        @JavascriptInterface
        public void setStr(String k, String v) { prefs.edit().putString(k, v == null ? "" : v).apply(); }

        @JavascriptInterface
        public boolean isOnline() { return Updater.online(MainActivity.this); }

        @JavascriptInterface
        public void share(String text) {
            main.post(() -> {
                try {
                    Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                    startActivity(Intent.createChooser(i, "Pozovi na #Bash Talk"));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void askPerms() { main.post(MainActivity.this::requestNeeded); }

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
                    startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName())));
                } catch (Exception ex) { openAppSettings(); }
            });
        }

        @JavascriptInterface
        public void openUrl(String url) {
            main.post(() -> {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) { }
            });
        }

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
        public void openWifi() { main.post(() -> { try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) { } }); }

        @JavascriptInterface
        public void openBluetooth() { main.post(() -> { try { startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); } catch (Exception ignored) { } }); }

        @JavascriptInterface
        public void openLocation() { main.post(() -> { try { startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)); } catch (Exception ignored) { } }); }

        @JavascriptInterface
        public void openAppSettings() {
            main.post(() -> {
                try { startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))); } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void batteryOpt() {
            main.post(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName())));
                } catch (Exception ex) {
                    try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) { }
                }
            });
        }
    }
}
