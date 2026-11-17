package com.basa.bashtalk;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.media.ToneGenerator;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.net.wifi.WifiManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Mrežni + audio motor. Dva transporta istovremeno:
 *  - Nearby (Bluetooth/BLE/Wi-Fi) – automatsko povezivanje bez ikakve mreže
 *  - LAN (UDP) – kad su uređaji na istom Wi-Fiju ili hotspotu (brže)
 * Glas: 16 kHz, G.711 µ-law (128 kbit/s), paketi po 20 ms.
 */
public class Engine implements NearbyLink.Sink, WifiDirectLink.Sink, UwbLink.Sink {

    public interface Listener {
        void onEvent(String type, JSONObject data);
    }

    static final int CTRL_PORT = 47100;
    static final int AUDIO_PORT = 47101;
    static final int RATE = 16000;
    static final int SAMPLES = 320;        // 20 ms
    static final int FRAME = SAMPLES * 2;  // PCM16 bajtova
    static final int HEADER = 14;
    static final long PEER_TIMEOUT = 7000;
    static final long LAN_FRESH = 5000;

    static class Peer {
        String id, name;
        long hash;
        InetAddress addr;
        long lastLan;
        String ep;
        int ch;
        long lastSeen, joined;
        boolean talking;
        int rtt = -1;
        boolean wfdCap, wfdGo, uwbCap;
        float dist = -1f, az = Float.NaN, el = Float.NaN;
        long rangeAt, rangeEmit;
    }

    static class Pending {
        JSONObject msg;
        Set<String> waiting;
        int tries, total;
    }

    interface Job { void run() throws Exception; }

    private final Context ctx;
    private final Listener listener;

    final String myId;
    final long myHash;
    volatile String name;
    volatile int channel = 1;
    volatile float gain = 1.5f;
    volatile boolean sounds = true;
    volatile boolean auto = true;

    private DatagramSocket ctrlSock, audioSock;
    private volatile boolean running;
    private WifiManager.MulticastLock mlock;
    private volatile NearbyLink nearby;
    private volatile String nearbyState = "off";
    private volatile String nearbyMsg = "";
    volatile boolean wfdEnabled = true;
    private volatile WifiDirectLink wfd;
    private volatile String wfdState = "off", wfdMsg = "";
    private volatile int wfdClients;
    private long wfdLonely;
    private volatile UwbLink uwb;
    private volatile String uwbState = "off", uwbMsg = "";

    final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> epToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Boolean> seenMsgs = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 600; }
            });

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<byte[]> audioQ = new LinkedBlockingQueue<>(200);

    private volatile boolean talking;
    private volatile String talkTarget;
    private volatile List<InetAddress> bcast = new ArrayList<>();
    private ToneGenerator tone;

    private static final short[] ULAW_DEC = new short[256];
    static {
        for (int i = 0; i < 256; i++) {
            int x = ~i & 0xff;
            int sign = x & 0x80;
            int exp = (x >> 4) & 7;
            int man = x & 0x0f;
            int s = (((man << 3) + 0x84) << exp) - 0x84;
            ULAW_DEC[i] = (short) (sign != 0 ? -s : s);
        }
    }

    static byte ulaw(int pcm) {
        int sign = (pcm >> 8) & 0x80;
        if (sign != 0) pcm = -pcm;
        if (pcm > 32635) pcm = 32635;
        pcm += 0x84;
        int exp = 7;
        for (int mask = 0x4000; (pcm & mask) == 0 && exp > 0; exp--, mask >>= 1) { }
        int man = (pcm >> (exp + 3)) & 0x0f;
        return (byte) ~(sign | (exp << 4) | man);
    }

    public Engine(Context ctx, String id, String name, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
        this.myId = id;
        this.myHash = id.hashCode();
        this.name = name;
    }

    // ------------------------------------------------------------ lifecycle

    public void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                mlock = wm.createMulticastLock("bashtalk");
                mlock.setReferenceCounted(false);
                mlock.acquire();
            }
        } catch (Exception ignored) { }
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70); } catch (Exception ignored) { }

        try {
            ctrlSock = new DatagramSocket(null);
            ctrlSock.setReuseAddress(true);
            ctrlSock.setBroadcast(true);
            ctrlSock.bind(new InetSocketAddress(CTRL_PORT));

            audioSock = new DatagramSocket(null);
            audioSock.setReuseAddress(true);
            audioSock.setBroadcast(true);
            audioSock.bind(new InetSocketAddress(AUDIO_PORT));
            new Thread(this::ctrlLoop, "ctrl-rx").start();
            new Thread(this::udpAudioLoop, "udp-audio-rx").start();
        } catch (Exception e) {
            emitError("Wi-Fi port nije dostupan: " + e.getMessage());
        }

        new Thread(this::playerLoop, "player").start();
        new Thread(this::tickLoop, "tick").start();
        if (auto) startNearby();
        if (wfdEnabled) startWfd();
        try {
            uwb = new UwbLink(ctx, myId, this);
            uwb.start();
        } catch (Throwable t) {
            onUwbState("error", t.getMessage());
        }
    }

    public void stop() {
        if (!running) return;
        try { sendToAll(base("bye")); } catch (Exception ignored) { }
        running = false;
        talking = false;
        stopNearby();
        try { if (wfd != null) wfd.stop(); } catch (Throwable ignored) { }
        try { if (uwb != null) uwb.shutdown(); } catch (Throwable ignored) { }
        try { if (ctrlSock != null) ctrlSock.close(); } catch (Exception ignored) { }
        try { if (audioSock != null) audioSock.close(); } catch (Exception ignored) { }
        try { if (mlock != null) mlock.release(); } catch (Exception ignored) { }
        try { if (tone != null) tone.release(); } catch (Exception ignored) { }
        worker.shutdownNow();
    }

    public void startNearby() {
        try {
            if (nearby == null) nearby = new NearbyLink(ctx, myId, this);
            nearby.start(name);
        } catch (Throwable t) {
            onState("error", "Google Play usluge nisu dostupne: " + t.getMessage());
        }
    }

    public void stopNearby() {
        try { if (nearby != null) nearby.stop(); } catch (Throwable ignored) { }
    }

    public void startWfd() {
        try {
            if (wfd == null) wfd = new WifiDirectLink(ctx, this);
            wfd.start();
        } catch (Throwable t) {
            onWfdState("error", t.getMessage(), 0);
        }
    }

    public void setWfd(boolean on) {
        wfdEnabled = on;
        if (on) startWfd();
        else try { if (wfd != null) wfd.stop(); } catch (Throwable ignored) { }
    }

    private boolean wfdCapable() {
        WifiDirectLink w = wfd;
        return wfdEnabled && w != null && w.isOn();
    }

    // ------------------------------------------------------------ Wi-Fi Direct / UWB sinks

    @Override
    public void onWfdState(String state, String msg, int clients) {
        wfdState = state;
        wfdMsg = msg == null ? "" : msg;
        wfdClients = clients;
    }

    @Override
    public void uwbSend(String peerId, String json) {
        exec(() -> {
            Peer p = peers.get(peerId);
            if (p == null) return;
            JSONObject o = new JSONObject(json);
            o.put("id", myId);
            o.put("name", name);
            o.put("ch", channel);
            sendTo(p, o);
        });
    }

    @Override
    public void onRange(String peerId, float dist, float azimuth, float elevation) {
        Peer p = peers.get(peerId);
        if (p == null) return;
        long now = System.currentTimeMillis();
        p.dist = dist;
        p.az = azimuth;
        p.el = elevation;
        p.rangeAt = dist >= 0 ? now : 0;
        if (now - p.rangeEmit < 150) return;
        p.rangeEmit = now;
        try {
            JSONObject o = new JSONObject();
            o.put("id", peerId);
            o.put("d", dist);
            if (!Float.isNaN(azimuth)) o.put("az", azimuth);
            if (!Float.isNaN(elevation)) o.put("el", elevation);
            emit("range", o);
        } catch (Exception ignored) { }
    }

    @Override
    public void onUwbState(String state, String msg) {
        uwbState = state;
        uwbMsg = msg == null ? "" : msg;
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("msg", uwbMsg);
            emit("uwb", o);
        } catch (Exception ignored) { }
    }

    /** Svake 3 s: dogovor oko Wi-Fi Direct grupe i UWB sesija. */
    private void coordinate() {
        long now = System.currentTimeMillis();
        WifiDirectLink w = wfd;
        if (wfdCapable()) {
            String leader = myId;
            boolean any = false;
            for (Peer p : peers.values()) {
                if (!p.wfdCap) continue;
                any = true;
                if (p.id.compareTo(leader) < 0) leader = p.id;
            }
            if (!any) {
                if (w.isGo && w.clients == 0) {
                    if (wfdLonely == 0) wfdLonely = now;
                    else if (now - wfdLonely > 90000) { w.leave(); wfdLonely = 0; }
                }
            } else {
                wfdLonely = 0;
                if (leader.equals(myId)) {
                    w.ensureGroup();
                } else {
                    Peer L = peers.get(leader);
                    if (L != null && L.wfdGo) {
                        if (w.isGo) w.leave();               // spoji se u vođinu grupu
                        else if (!w.isClient) w.join();
                    }
                }
            }
        }
        UwbLink u = uwb;
        if (u != null && u.getAvailable()) {
            for (Peer p : peers.values()) {
                if (p.uwbCap && now - p.lastSeen < 4000) u.tryStart(p.id);
            }
        }
    }

    public void setAuto(boolean on) {
        auto = on;
        if (on) startNearby(); else stopNearby();
    }

    // ------------------------------------------------------------ NearbyLink.Sink

    @Override
    public void onBytes(String ep, byte[] b) {
        if (b.length > HEADER && b[0] == 'B' && b[1] == '2') {
            audioQ.offer(b);
        } else if (b.length > 1 && b[0] == '{') {
            final String s = new String(b, StandardCharsets.UTF_8);
            exec(() -> {
                JSONObject o = new JSONObject(s);
                String id = o.optString("id");
                if (!id.isEmpty() && !id.equals(myId)) handleCtrl(o, null, ep);
            });
        }
    }

    @Override
    public void onConnected(String ep, String peerId) {
        if (peerId != null) epToId.put(ep, peerId);
        exec(() -> {
            JSONObject h = hello();
            NearbyLink nl = nearby;
            if (nl != null) nl.send(ep, bytes(h));
        });
    }

    @Override
    public void onDisconnected(String ep) {
        String id = epToId.remove(ep);
        if (id != null) {
            Peer p = peers.get(id);
            if (p != null && ep.equals(p.ep)) p.ep = null;
        }
    }

    @Override
    public void onState(String state, String msg) {
        nearbyState = state;
        nearbyMsg = msg == null ? "" : msg;
        try {
            JSONObject o = new JSONObject();
            o.put("state", state);
            o.put("msg", nearbyMsg);
            emit("nearby", o);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ helpers

    private void exec(Job j) {
        try {
            worker.execute(() -> { try { j.run(); } catch (Exception ignored) { } });
        } catch (Exception ignored) { }
    }

    private JSONObject hello() throws Exception {
        JSONObject h = base("hello");
        h.put("talk", talking);
        h.put("wfd", wfdCapable());
        WifiDirectLink w = wfd;
        h.put("wfdGo", w != null && w.isGo);
        UwbLink u = uwb;
        h.put("uwb", u != null && u.getAvailable());
        return h;
    }

    private JSONObject base(String type) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", type);
        o.put("id", myId);
        o.put("name", name);
        o.put("ch", channel);
        return o;
    }

    private static byte[] bytes(JSONObject o) { return o.toString().getBytes(StandardCharsets.UTF_8); }

    private void udp(byte[] b, InetAddress to) {
        try { if (ctrlSock != null) ctrlSock.send(new DatagramPacket(b, b.length, to, CTRL_PORT)); } catch (Exception ignored) { }
    }

    private boolean lanFresh(Peer p) {
        return p.addr != null && System.currentTimeMillis() - p.lastLan < LAN_FRESH;
    }

    private boolean nearbyOk(Peer p) {
        NearbyLink nl = nearby;
        return nl != null && p.ep != null && nl.isConnected(p.ep);
    }

    private void sendTo(Peer p, JSONObject o) {
        byte[] b = bytes(o);
        if (lanFresh(p)) udp(b, p.addr);
        else if (nearbyOk(p)) nearby.send(p.ep, b);
        else if (p.addr != null) udp(b, p.addr);
    }

    private void reply(JSONObject o, InetAddress from, String ep) {
        byte[] b = bytes(o);
        NearbyLink nl = nearby;
        if (from != null) udp(b, from);
        else if (ep != null && nl != null) nl.send(ep, b);
    }

    private void sendToAll(JSONObject o) {
        byte[] b = bytes(o);
        for (InetAddress a : bcast) udp(b, a);
        for (Peer p : peers.values()) if (p.addr != null) udp(b, p.addr);
        NearbyLink nl = nearby;
        if (nl != null) nl.sendAll(b);
    }

    private void emit(String type, JSONObject data) {
        try { listener.onEvent(type, data == null ? new JSONObject() : data); } catch (Exception ignored) { }
    }

    private void emitError(String msg) {
        try {
            JSONObject o = new JSONObject();
            o.put("msg", msg);
            emit("error", o);
        } catch (Exception ignored) { }
    }

    private void beep(int t, int ms) {
        if (!sounds || tone == null) return;
        try { tone.startTone(t, ms); } catch (Exception ignored) { }
    }

    public static JSONArray localIps() {
        JSONArray arr = new JSONArray();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) {
                        JSONObject o = new JSONObject();
                        o.put("ip", a.getHostAddress());
                        o.put("iface", ni.getName());
                        arr.put(o);
                    }
                }
            }
        } catch (Exception ignored) { }
        return arr;
    }

    private void refreshBroadcast() {
        List<InetAddress> list = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
            while (en != null && en.hasMoreElements()) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress b = ia.getBroadcast();
                    if (b != null && ia.getAddress() instanceof Inet4Address && !list.contains(b)) list.add(b);
                }
            }
        } catch (Exception ignored) { }
        try {
            InetAddress g = InetAddress.getByName("255.255.255.255");
            if (!list.contains(g)) list.add(g);
        } catch (Exception ignored) { }
        bcast = list;
    }

    // ------------------------------------------------------------ control

    private void ctrlLoop() {
        byte[] buf = new byte[65000];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ctrlSock.receive(p);
                final String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                final InetAddress from = p.getAddress();
                exec(() -> {
                    JSONObject o = new JSONObject(s);
                    String id = o.optString("id");
                    if (!id.isEmpty() && !id.equals(myId)) handleCtrl(o, from, null);
                });
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private Peer touch(JSONObject o, InetAddress from, String ep) {
        String id = o.optString("id");
        Peer p = peers.get(id);
        boolean isNew = p == null;
        long now = System.currentTimeMillis();
        if (isNew) {
            p = new Peer();
            p.id = id;
            p.hash = id.hashCode();
            p.joined = now;
        }
        p.name = o.optString("name", "?");
        p.ch = o.optInt("ch", 1);
        p.lastSeen = now;
        if (from != null) { p.addr = from; p.lastLan = now; }
        if (ep != null) { p.ep = ep; epToId.put(ep, id); }
        if (isNew) {
            peers.put(id, p);
            try {
                JSONObject e = new JSONObject();
                e.put("id", id);
                e.put("name", p.name);
                e.put("ch", p.ch);
                emit("join", e);
                JSONObject h = hello();
                reply(h, from, ep);
            } catch (Exception ignored) { }
            beep(ToneGenerator.TONE_PROP_ACK, 150);
        }
        return p;
    }

    private void handleCtrl(JSONObject o, InetAddress from, String ep) throws Exception {
        switch (o.optString("t")) {
            case "hello": {
                Peer p = touch(o, from, ep);
                p.talking = o.optBoolean("talk", false);
                p.wfdCap = o.optBoolean("wfd", false);
                p.wfdGo = o.optBoolean("wfdGo", false);
                p.uwbCap = o.optBoolean("uwb", false);
                break;
            }
            case "uwbInit":
            case "uwbAddr": {
                touch(o, from, ep);
                UwbLink u = uwb;
                if (u == null || !myId.equals(o.optString("to"))) break;
                if ("uwbInit".equals(o.optString("t"))) u.onInit(o.optString("id"), o.toString());
                else u.onAddr(o.optString("id"), o.toString());
                break;
            }
            case "bye": {
                Peer p = peers.remove(o.optString("id"));
                if (p != null) { emitLeave(p); if (uwb != null) uwb.stopPeer(p.id); }
                break;
            }
            case "ping": {
                touch(o, from, ep);
                JSONObject r = base("pong");
                r.put("ts", o.optLong("ts"));
                reply(r, from, ep);
                break;
            }
            case "pong": {
                Peer p = touch(o, from, ep);
                p.rtt = (int) Math.max(1, System.currentTimeMillis() - o.optLong("ts"));
                break;
            }
            case "msg": {
                touch(o, from, ep);
                String mid = o.optString("mid");
                JSONObject ack = base("ack");
                ack.put("mid", mid);
                reply(ack, from, ep);
                if (seenMsgs.containsKey(mid)) break;
                seenMsgs.put(mid, true);
                String to = o.optString("to", "");
                if (!to.isEmpty() && !to.equals(myId)) break;
                if (to.isEmpty() && o.optInt("ch", 1) != channel) break;
                emit("msg", o);
                break;
            }
            case "ack": {
                String mid = o.optString("mid");
                Pending pd = pending.get(mid);
                if (pd != null) {
                    pd.waiting.remove(o.optString("id"));
                    JSONObject d = new JSONObject();
                    d.put("mid", mid);
                    d.put("got", pd.total - pd.waiting.size());
                    d.put("total", pd.total);
                    emit("delivered", d);
                    if (pd.waiting.isEmpty()) pending.remove(mid);
                }
                break;
            }
            case "buzz": {
                touch(o, from, ep);
                if (myId.equals(o.optString("to"))) {
                    beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
                    emit("buzz", o);
                }
                break;
            }
        }
    }

    private void emitLeave(Peer p) {
        try {
            JSONObject e = new JSONObject();
            e.put("id", p.id);
            e.put("name", p.name);
            emit("leave", e);
        } catch (Exception ignored) { }
        beep(ToneGenerator.TONE_PROP_NACK, 200);
    }

    // ------------------------------------------------------------ tick

    private void tickLoop() {
        long n = 0;
        while (running) {
            try {
                if (n % 20 == 0) refreshBroadcast();
                NearbyLink nl = nearby;
                if (nl != null && n % 2 == 0) nl.tick();
                if (n % 6 == 0) exec(() -> {
                    JSONObject h = hello();
                    sendToAll(h);
                });
                if (n % 12 == 3) exec(() -> {
                    JSONObject ping = base("ping");
                    ping.put("ts", System.currentTimeMillis());
                    for (Peer p : peers.values()) sendTo(p, ping);
                });
                if (n % 2 == 0) exec(this::retryPending);
                if (n % 12 == 6) exec(this::coordinate);
                if (n % 4 == 0) exec(() -> {
                    long now = System.currentTimeMillis();
                    Iterator<Peer> it = peers.values().iterator();
                    while (it.hasNext()) {
                        Peer p = it.next();
                        if (now - p.lastSeen > PEER_TIMEOUT) {
                            it.remove();
                            emitLeave(p);
                            if (uwb != null) uwb.stopPeer(p.id);
                        }
                    }
                    emitPeers();
                });
                n++;
                Thread.sleep(250);
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    public int peerCount() { return peers.size(); }

    private void emitPeers() throws Exception {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        for (Peer p : peers.values()) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("ch", p.ch);
            o.put("ip", p.addr != null ? p.addr.getHostAddress() : "");
            boolean direct = p.addr != null && p.addr.getHostAddress().startsWith("192.168.49.");
            o.put("via", lanFresh(p) ? (direct ? "wfd" : "wifi") : nearbyOk(p) ? "nearby" : "wifi");
            if (p.rangeAt > 0 && now - p.rangeAt < 3000) {
                o.put("d", p.dist);
                if (!Float.isNaN(p.az)) o.put("az", p.az);
            }
            o.put("uwb", p.uwbCap);
            o.put("age", now - p.lastSeen);
            o.put("rtt", p.rtt);
            o.put("talk", p.talking);
            arr.put(o);
        }
        NearbyLink nl = nearby;
        JSONObject d = new JSONObject();
        d.put("peers", arr);
        d.put("ips", localIps());
        d.put("nearby", nearbyState);
        d.put("nearbyMsg", nearbyMsg);
        d.put("links", nl != null ? nl.connectedCount() : 0);
        d.put("wfd", wfdEnabled ? wfdState : "off");
        d.put("wfdMsg", wfdMsg);
        d.put("wfdClients", wfdClients);
        d.put("uwbSt", uwbState);
        d.put("uwbMsg", uwbMsg);
        emit("peers", d);
    }

    private void retryPending() {
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending pd = it.next().getValue();
            pd.tries++;
            if (pd.tries > 10 || pd.waiting.isEmpty()) { it.remove(); continue; }
            for (String id : new ArrayList<>(pd.waiting)) {
                Peer p = peers.get(id);
                if (p == null) { pd.waiting.remove(id); continue; }
                sendTo(p, pd.msg);
            }
        }
    }

    // ------------------------------------------------------------ chat API

    public String sendChat(final String text, final String to) {
        final String mid = UUID.randomUUID().toString().substring(0, 12);
        seenMsgs.put(mid, true);
        exec(() -> {
            JSONObject o = base("msg");
            o.put("mid", mid);
            o.put("text", text);
            o.put("ts", System.currentTimeMillis());
            boolean priv = to != null && !to.isEmpty();
            if (priv) o.put("to", to);

            Pending pd = new Pending();
            pd.msg = o;
            pd.waiting = ConcurrentHashMap.newKeySet();
            for (Peer p : peers.values()) {
                if (priv ? p.id.equals(to) : p.ch == channel) pd.waiting.add(p.id);
            }
            pd.total = pd.waiting.size();
            if (!pd.waiting.isEmpty()) pending.put(mid, pd);
            for (String id : pd.waiting) {
                Peer p = peers.get(id);
                if (p != null) sendTo(p, o);
            }
            if (!priv) {
                byte[] b = bytes(o);
                for (InetAddress a : bcast) udp(b, a);
            }
        });
        return mid;
    }

    public void buzz(final String peerId) {
        exec(() -> {
            Peer p = peers.get(peerId);
            if (p == null) return;
            JSONObject o = base("buzz");
            o.put("to", peerId);
            for (int i = 0; i < 2; i++) sendTo(p, o);
        });
    }

    // ------------------------------------------------------------ walkie-talkie TX

    public synchronized void startTalk(String target) {
        if (talking) return;
        talking = true;
        talkTarget = (target == null || target.isEmpty()) ? null : target;
        beep(ToneGenerator.TONE_PROP_BEEP, 80);
        new Thread(this::talkLoop, "talk").start();
    }

    public synchronized void stopTalk() { talking = false; }

    public boolean isTalking() { return talking; }

    public synchronized void toggleTalk(String target) {
        if (talking) stopTalk(); else startTalk(target);
    }

    private void talkLoop() {
        AudioRecord rec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        AcousticEchoCanceler aec = null;
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, FRAME * 8));
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                emitError("Mikrofon nije dostupan. Dozvoli mikrofon u postavkama aplikacije.");
                return;
            }
            int sid = rec.getAudioSessionId();
            try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(sid); if (ns != null) ns.setEnabled(true); } } catch (Exception ignored) { }
            try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(sid); if (agc != null) agc.setEnabled(true); } } catch (Exception ignored) { }
            try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(sid); if (aec != null) aec.setEnabled(true); } } catch (Exception ignored) { }

            rec.startRecording();
            emitTx(true);

            short[] pcm = new short[SAMPLES];
            byte[] pkt = new byte[HEADER + SAMPLES];
            pkt[0] = 'B';
            pkt[1] = '2';
            for (int i = 0; i < 8; i++) pkt[4 + i] = (byte) (myHash >>> (56 - 8 * i));
            int seq = 0, frames = 0;

            while (talking && running) {
                int n = rec.read(pcm, 0, SAMPLES);
                if (n <= 0) continue;
                float g = gain;
                long sum = 0;
                for (int i = 0; i < n; i++) {
                    int s = Math.round(pcm[i] * g);
                    if (s > 32767) s = 32767;
                    if (s < -32768) s = -32768;
                    sum += (long) s * s;
                    pkt[HEADER + i] = ulaw(s);
                }
                String tgt = talkTarget;
                pkt[2] = (byte) channel;
                pkt[3] = (byte) (tgt != null ? 1 : 0);
                pkt[12] = (byte) (seq >> 8);
                pkt[13] = (byte) seq;
                seq = (seq + 1) & 0xffff;
                byte[] out = Arrays.copyOf(pkt, HEADER + n);

                int sent = 0;
                if (tgt != null) {
                    Peer p = peers.get(tgt);
                    if (p != null) { sendAudio(p, out); sent++; }
                } else {
                    for (Peer p : peers.values()) if (p.ch == channel) { sendAudio(p, out); sent++; }
                    if (sent == 0) {
                        for (InetAddress b : bcast) udpAudio(out, b);
                        NearbyLink nl = nearby;
                        if (nl != null) nl.sendAll(out);
                    }
                }

                if (++frames % 4 == 0) {
                    double rms = Math.sqrt(sum / (double) Math.max(1, n)) / 32768.0;
                    JSONObject l = new JSONObject();
                    l.put("v", Math.min(1.0, rms * 4));
                    l.put("to", sent);
                    emit("level", l);
                }
            }
        } catch (Exception e) {
            emitError("Greška mikrofona: " + e.getMessage());
        } finally {
            try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
            try { if (ns != null) ns.release(); } catch (Exception ignored) { }
            try { if (agc != null) agc.release(); } catch (Exception ignored) { }
            try { if (aec != null) aec.release(); } catch (Exception ignored) { }
            talking = false;
            beep(ToneGenerator.TONE_PROP_BEEP2, 120);
            emitTx(false);
        }
    }

    private void sendAudio(Peer p, byte[] b) {
        if (lanFresh(p)) udpAudio(b, p.addr);
        else if (nearbyOk(p)) nearby.send(p.ep, b);
        else if (p.addr != null) udpAudio(b, p.addr);
    }

    private void udpAudio(byte[] b, InetAddress to) {
        try { if (audioSock != null) audioSock.send(new DatagramPacket(b, b.length, to, AUDIO_PORT)); } catch (Exception ignored) { }
    }

    private void emitTx(boolean on) {
        try {
            JSONObject o = new JSONObject();
            o.put("on", on);
            emit("tx", o);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ walkie-talkie RX

    private void udpAudioLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                audioSock.receive(p);
                if (p.getLength() > HEADER && buf[0] == 'B' && buf[1] == '2') {
                    audioQ.offer(Arrays.copyOf(buf, p.getLength()));
                }
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private void playerLoop() {
        AudioTrack track = null;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    Math.max(min, FRAME * 10),
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE);
        } catch (Exception e) {
            emitError("Zvučnik nije dostupan: " + e.getMessage());
        }

        short[] silence = new short[SAMPLES * 3];
        short[] pcm = new short[SAMPLES * 4];
        long speaker = 0, lastPkt = 0;
        int lastSeq = -1;

        while (running) {
            try {
                byte[] b = audioQ.poll(150, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (speaker != 0 && now - lastPkt > 500) {
                    endRx(track, speaker);
                    speaker = 0;
                }
                if (b == null) continue;

                long h = 0;
                for (int i = 0; i < 8; i++) h = (h << 8) | (b[4 + i] & 0xff);
                if (h == myHash || talking) continue;
                boolean priv = b[3] == 1;
                if (!priv && b[2] != channel) continue;
                int seq = ((b[12] & 0xff) << 8) | (b[13] & 0xff);

                if (speaker == 0) {
                    speaker = h;
                    lastSeq = -1;
                    beep(ToneGenerator.TONE_PROP_BEEP, 60);
                    if (track != null) {
                        track.play();
                        track.write(silence, 0, silence.length);
                    }
                    Peer who = peerByHash(h);
                    JSONObject o = new JSONObject();
                    o.put("on", true);
                    o.put("id", who != null ? who.id : "");
                    o.put("name", who != null ? who.name : "?");
                    o.put("priv", priv);
                    emit("rx", o);
                }
                if (h != speaker || seq == lastSeq) continue;
                lastSeq = seq;
                lastPkt = now;
                int n = Math.min(b.length - HEADER, pcm.length);
                for (int i = 0; i < n; i++) pcm[i] = ULAW_DEC[b[HEADER + i] & 0xff];
                if (track != null) track.write(pcm, 0, n);
            } catch (Exception e) {
                if (!running) break;
            }
        }
        try { if (track != null) track.release(); } catch (Exception ignored) { }
    }

    private void endRx(AudioTrack track, long speaker) {
        try { if (track != null) { track.stop(); track.flush(); } } catch (Exception ignored) { }
        beep(ToneGenerator.TONE_PROP_BEEP2, 100);
        try {
            Peer who = peerByHash(speaker);
            JSONObject o = new JSONObject();
            o.put("on", false);
            o.put("id", who != null ? who.id : "");
            emit("rx", o);
        } catch (Exception ignored) { }
    }

    private Peer peerByHash(long h) {
        for (Peer p : peers.values()) if (p.hash == h) return p;
        return null;
    }
}
