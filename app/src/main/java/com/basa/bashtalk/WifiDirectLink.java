package com.basa.bashtalk;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Looper;

/**
 * Wi-Fi Direct bez ikakvog pitanja: jedan telefon ("vođa", najmanji ID)
 * napravi Wi-Fi Direct grupu s poznatim imenom i šifrom na 2,4 GHz,
 * ostali se sami spoje. Tako svi dobiju lokalnu mrežu (192.168.49.x)
 * i Engine preko nje šalje glas i poruke – veći domet i bolji zvuk
 * od Bluetootha. Treba Android 10+.
 */
public class WifiDirectLink {

    public interface Sink {
        void onWfdState(String state, String msg, int clients);
    }

    static final String SSID = "DIRECT-bt-BashTalk";
    static final String PASS = "BashTalk-1332-wd";

    private final Context ctx;
    private final Sink sink;
    private WifiP2pManager mgr;
    private WifiP2pManager.Channel ch;
    private volatile boolean on;
    volatile boolean isGo, isClient;
    volatile int clients;
    private long lastAttempt;
    private boolean registered;

    public WifiDirectLink(Context ctx, Sink sink) {
        this.ctx = ctx.getApplicationContext();
        this.sink = sink;
    }

    public static boolean supported(Context c) {
        return Build.VERSION.SDK_INT >= 29
                && c.getPackageManager().hasSystemFeature("android.hardware.wifi.direct");
    }

    public boolean isOn() { return on; }

    public void start() {
        if (on || !supported(ctx)) {
            if (!supported(ctx)) sink.onWfdState("unsupported", "Treba Android 10+ s Wi-Fi Directom", 0);
            return;
        }
        try {
            mgr = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
            ch = mgr.initialize(ctx, Looper.getMainLooper(), () -> { on = false; });
            IntentFilter f = new IntentFilter();
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
            f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
            else ctx.registerReceiver(rx, f);
            registered = true;
            on = true;
            sink.onWfdState("idle", "", 0);
            refresh();
        } catch (Exception e) {
            sink.onWfdState("error", e.getMessage(), 0);
        }
    }

    public void stop() {
        if (!on) return;
        on = false;
        try { if (isGo || isClient) mgr.removeGroup(ch, null); } catch (Exception ignored) { }
        try { if (registered) ctx.unregisterReceiver(rx); } catch (Exception ignored) { }
        registered = false;
        isGo = isClient = false;
        sink.onWfdState("off", "", 0);
    }

    private boolean wifiOn() {
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            return wm != null && wm.isWifiEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    @SuppressLint("MissingPermission")
    private WifiP2pConfig config() {
        return new WifiP2pConfig.Builder()
                .setNetworkName(SSID)
                .setPassphrase(PASS)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)   // bolji domet kroz beton
                .enablePersistentMode(false)
                .build();
    }

    /** Ja sam vođa: napravi grupu (ako već nije). */
    @SuppressLint("MissingPermission")
    public void ensureGroup() {
        if (!on || isGo || isClient) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 15000) return;
        lastAttempt = now;
        if (!wifiOn()) { sink.onWfdState("error", "Uključi Wi-Fi (ne mora biti spojen)", 0); return; }
        try {
            mgr.createGroup(ch, config(), new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { sink.onWfdState("creating", "", 0); }
                @Override public void onFailure(int reason) {
                    if (reason == WifiP2pManager.BUSY) refresh();
                    else sink.onWfdState("error", "Grupa: kod " + reason, 0);
                }
            });
        } catch (SecurityException se) {
            sink.onWfdState("error", "Nedostaje dozvola za uređaje u blizini", 0);
        } catch (Exception e) {
            sink.onWfdState("error", e.getMessage(), 0);
        }
    }

    /** Spoji se na vođinu grupu. */
    @SuppressLint("MissingPermission")
    public void join() {
        if (!on || isGo || isClient) return;
        long now = System.currentTimeMillis();
        if (now - lastAttempt < 15000) return;
        lastAttempt = now;
        if (!wifiOn()) { sink.onWfdState("error", "Uključi Wi-Fi (ne mora biti spojen)", 0); return; }
        try {
            mgr.connect(ch, config(), new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { sink.onWfdState("joining", "", 0); }
                @Override public void onFailure(int reason) { sink.onWfdState("idle", "Spajanje: kod " + reason, 0); }
            });
        } catch (SecurityException se) {
            sink.onWfdState("error", "Nedostaje dozvola za uređaje u blizini", 0);
        } catch (Exception e) {
            sink.onWfdState("error", e.getMessage(), 0);
        }
    }

    /** Raspusti grupu (npr. nema nikoga). */
    public void leave() {
        if (!on || !(isGo || isClient)) return;
        try { mgr.removeGroup(ch, null); } catch (Exception ignored) { }
    }

    @SuppressLint("MissingPermission")
    private void refresh() {
        if (!on) return;
        try {
            mgr.requestGroupInfo(ch, this::onGroup);
        } catch (Exception ignored) { }
    }

    private void onGroup(WifiP2pGroup g) {
        boolean ours = g != null && SSID.equals(g.getNetworkName());
        isGo = ours && g.isGroupOwner();
        isClient = ours && !g.isGroupOwner();
        clients = isGo ? g.getClientList().size() : 0;
        if (isGo) sink.onWfdState("go", "", clients);
        else if (isClient) sink.onWfdState("client", "", 0);
        else sink.onWfdState("idle", "", 0);
    }

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refresh();
        }
    };
}
