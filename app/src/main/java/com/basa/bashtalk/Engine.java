package com.basa.bashtalk;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
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
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
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
 * Mrežni + audio motor #Bash Talk.
 * Transporti: Nearby (BT/BLE), Wi-Fi Direct / Wi-Fi / hotspot (UDP).
 * Mesh: poruke, glas, slike i SOS preskaču preko telefona u sredini (TTL).
 * Outbox: poruke i mediji se čuvaju 2 h i šalju čim se primalac pojavi.
 */
public class Engine implements NearbyLink.Sink, WifiDirectLink.Sink, UwbLink.Sink {

    public interface Listener {
        void onEvent(String type, JSONObject data);
    }

    /** Za web (iPhone) klijente koje poslužuje WebServer. */
    public interface WebSink {
        void webEvent(String type, JSONObject data);
        void webAudio(byte[] ulaw, int off, int len);
        Map<String, String> webClients();          // id -> ime (aktivni)
    }

    static final int CTRL_PORT = 47100;
    static final int AUDIO_PORT = 47101;
    static final int RATE = 16000;
    static final int SAMPLES = 320;
    static final int FRAME = SAMPLES * 2;
    static final int HEADER = 14;
    static final int CHUNK = 1100;
    static final int BHEAD = 16;
    static final long PEER_TIMEOUT = 7000;
    static final long REMOTE_TIMEOUT = 12000;
    static final long LAN_FRESH = 5000;
    static final long OUTBOX_TTL = 2 * 3600_000L;
    static final int MESH_TTL = 3;

    static class Peer {
        String id, name;
        long hash;
        InetAddress addr;
        long lastLan;
        String ep;
        int ch;
        long lastSeen, joined;
        boolean talking, virt;
        int rtt = -1;
        boolean wfdCap, wfdGo, uwbCap;
        float dist = -1f, az = Float.NaN;
        long rangeAt, rangeEmit;
        float p = Float.NaN;
        int fl = Integer.MIN_VALUE;
    }

    static class Remote {
        String id, name, via;
        int ch, hops;
        long lastSeen;
        float p = Float.NaN;
        int fl = Integer.MIN_VALUE;
    }

    static class Out {
        String mid, kind, to;   // kind: msg | blob
        int ch;
        long ts;
        JSONObject msg;         // tekst ili blob meta
        Set<String> acked = ConcurrentHashMap.newKeySet();
        Map<String, Integer> sends = new ConcurrentHashMap<>();
        Map<String, Long> lastSent = new ConcurrentHashMap<>();
        int att;
    }

    static class In {
        String bid, origin;
        JSONObject meta;
        byte[][] chunks;
        int got, n, nacks;
        long lastRx;
    }

    interface Job { void run() throws Exception; }

    private final Context ctx;
    private final Listener listener;
    private final SharedPreferences prefs;

    final String myId;
    final long myHash;
    volatile String name;
    volatile int channel = 1;
    volatile float gain = 1.5f;
    volatile boolean sounds = true;
    volatile boolean auto = true;
    volatile boolean mesh = true;
    volatile boolean headsetMic = false;
    volatile float pressure = Float.NaN;
    volatile int floor = Integer.MIN_VALUE;
    volatile WebSink web;
    volatile boolean onlineAudio;
    private final Map<Long, String> onlineNames = new ConcurrentHashMap<>();
    private final Map<Long, Integer> onlineSeq = new ConcurrentHashMap<>();

    private DatagramSocket ctrlSock, audioSock;
    private volatile boolean running;
    private WifiManager.MulticastLock mlock;
    private volatile NearbyLink nearby;
    private volatile String nearbyState = "off", nearbyMsg = "";
    volatile boolean wfdEnabled = false;
    private volatile WifiDirectLink wfd;
    private volatile String wfdState = "off", wfdMsg = "";
    private volatile int wfdClients;
    private long wfdLonely;
    private volatile UwbLink uwb;
    private volatile String uwbState = "off", uwbMsg = "";

    final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, Remote> remotes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> epToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Out> outbox = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, byte[]> blobOut = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, In> blobIn = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, JSONObject> tasks = new ConcurrentHashMap<>();
    final ConcurrentHashMap<String, JSONObject> sosMap = new ConcurrentHashMap<>();

    private final Map<String, Boolean> seenMsgs = lru(1500);
    private final Map<String, Boolean> seenRelay = lru(4000);
    private final Map<Long, Long> audioSeen = Collections.synchronizedMap(new LinkedHashMap<Long, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Long> e) { return size() > 4000; }
    });
    private final Map<String, Long> chunkSeen = Collections.synchronizedMap(new LinkedHashMap<String, Long>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Long> e) { return size() > 8000; }
    });

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private final LinkedBlockingQueue<byte[]> audioQ = new LinkedBlockingQueue<>(300);

    private volatile boolean talking, noting;
    private volatile String talkTarget;
    private volatile List<InetAddress> bcast = new ArrayList<>();
    private ToneGenerator tone;
    private volatile boolean mySos;
    private volatile String mySosReason = "";
    private int bcnSeq;
    private final ArrayDeque<JSONObject> history = new ArrayDeque<>();
    private ByteArrayOutputStream noteBuf;
    private long noteStart;
    private final SecureRandom rnd = new SecureRandom();

    private static <K> Map<K, Boolean> lru(final int cap) {
        return Collections.synchronizedMap(new LinkedHashMap<K, Boolean>() {
            @Override protected boolean removeEldestEntry(Map.Entry<K, Boolean> e) { return size() > cap; }
        });
    }

    public Engine(Context ctx, String id, String name, Listener l) {
        this.ctx = ctx.getApplicationContext();
        this.listener = l;
        this.myId = id;
        this.myHash = id.hashCode();
        this.name = name;
        this.prefs = this.ctx.getSharedPreferences("bashtalk", Context.MODE_PRIVATE);
        loadTasks();
    }

    // ================================================================ lifecycle

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
            audioSock.setSendBufferSize(256 * 1024);
            audioSock.setReceiveBufferSize(512 * 1024);
            audioSock.bind(new InetSocketAddress(AUDIO_PORT));
            new Thread(this::ctrlLoop, "ctrl-rx").start();
            new Thread(this::udpBinLoop, "udp-bin-rx").start();
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
        loadHistory();
    }

    public void stop() {
        if (!running) return;
        try { sendToAll(base("bye")); } catch (Exception ignored) { }
        running = false;
        talking = false;
        noting = false;
        stopNearby();
        try { if (wfd != null) wfd.stop(); } catch (Throwable ignored) { }
        try { if (uwb != null) uwb.shutdown(); } catch (Throwable ignored) { }
        try { if (ctrlSock != null) ctrlSock.close(); } catch (Exception ignored) { }
        try { if (audioSock != null) audioSock.close(); } catch (Exception ignored) { }
        try { if (mlock != null) mlock.release(); } catch (Exception ignored) { }
        try { if (tone != null) tone.release(); } catch (Exception ignored) { }
        worker.shutdownNow();
        sender.shutdownNow();
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

    public void setAuto(boolean on) {
        auto = on;
        if (on) startNearby(); else stopNearby();
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

    // ================================================================ sinks

    @Override
    public void onBytes(String ep, byte[] b) {
        if (b.length > 2 && b[0] == 'B' && (b[1] == '2' || b[1] == '3')) {
            onBinary(b, b.length, "ep:" + ep);
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
            NearbyLink nl = nearby;
            if (nl != null) nl.send(ep, bytes(hello()));
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
        p.rangeAt = dist >= 0 ? now : 0;
        if (now - p.rangeEmit < 150) return;
        p.rangeEmit = now;
        try {
            JSONObject o = new JSONObject();
            o.put("id", peerId);
            o.put("d", dist);
            if (!Float.isNaN(azimuth)) o.put("az", azimuth);
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

    // ================================================================ helpers

    private void exec(Job j) {
        try { worker.execute(() -> { try { j.run(); } catch (Exception ignored) { } }); } catch (Exception ignored) { }
    }

    private JSONObject base(String type) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", type);
        o.put("id", myId);
        o.put("name", name);
        o.put("ch", channel);
        return o;
    }

    private JSONObject hello() throws Exception {
        JSONObject h = base("hello");
        h.put("talk", talking);
        h.put("wfd", wfdCapable());
        WifiDirectLink w = wfd;
        h.put("wfdGo", w != null && w.isGo);
        UwbLink u = uwb;
        h.put("uwb", u != null && u.getAvailable());
        if (!Float.isNaN(pressure)) h.put("p", pressure);
        if (floor != Integer.MIN_VALUE) h.put("fl", floor);
        return h;
    }

    private static byte[] bytes(JSONObject o) { return o.toString().getBytes(StandardCharsets.UTF_8); }

    private String newMid() { return UUID.randomUUID().toString().replace("-", "").substring(0, 16); }

    private void udp(byte[] b, InetAddress to) {
        try { if (ctrlSock != null) ctrlSock.send(new DatagramPacket(b, b.length, to, CTRL_PORT)); } catch (Exception ignored) { }
    }

    private void udpBin(byte[] b, int len, InetAddress to) {
        try { if (audioSock != null) audioSock.send(new DatagramPacket(b, len, to, AUDIO_PORT)); } catch (Exception ignored) { }
    }

    private boolean lanFresh(Peer p) {
        return p.addr != null && System.currentTimeMillis() - p.lastLan < LAN_FRESH;
    }

    private boolean nearbyOk(Peer p) {
        NearbyLink nl = nearby;
        return nl != null && p.ep != null && nl.isConnected(p.ep);
    }

    private void sendTo(Peer p, JSONObject o) {
        if (p.virt) return;
        byte[] b = bytes(o);
        if (lanFresh(p)) udp(b, p.addr);
        else if (nearbyOk(p)) nearby.send(p.ep, b);
        else if (p.addr != null) udp(b, p.addr);
    }

    private void sendBin(Peer p, byte[] b, int len) {
        if (p.virt) return;
        if (lanFresh(p)) udpBin(b, len, p.addr);
        else if (nearbyOk(p)) nearby.send(p.ep, len == b.length ? b : Arrays.copyOf(b, len));
        else if (p.addr != null) udpBin(b, len, p.addr);
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
        for (Peer p : peers.values()) if (p.addr != null && !p.virt) udp(b, p.addr);
        NearbyLink nl = nearby;
        if (nl != null) nl.sendAll(b);
    }

    /** Pošalji svim direktnim susjedima (osim izvora). */
    private void flood(JSONObject o, String exceptId) {
        for (Peer p : peers.values()) {
            if (p.virt || p.id.equals(exceptId)) continue;
            sendTo(p, o);
        }
    }

    /** Pošalji jednom čvoru: direktno ako je susjed, inače preko mesha. */
    private void route(String toId, JSONObject o) throws Exception {
        Peer p = peers.get(toId);
        if (p != null && !p.virt) { sendTo(p, o); return; }
        if (p != null && p.virt) return;
        o.put("ttl", MESH_TTL);
        o.put("att", rnd.nextInt(1 << 30));
        flood(o, null);
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

    private boolean isMine(String id) {
        if (myId.equals(id)) return true;
        WebSink w = web;
        return w != null && w.webClients().containsKey(id);
    }

    // ================================================================ control

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
                    if (id.isEmpty() || id.equals(myId)) return;
                    if (o.optBoolean("virt") && myId.equals(o.optString("host"))) return;
                    handleCtrl(o, from, null);
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
        p.virt = false;
        if (from != null) { p.addr = from; p.lastLan = now; }
        if (ep != null) { p.ep = ep; epToId.put(ep, id); }
        if (isNew) {
            peers.put(id, p);
            remotes.remove(id);
            try {
                JSONObject e = new JSONObject();
                e.put("id", id);
                e.put("name", p.name);
                e.put("ch", p.ch);
                emit("join", e);
                reply(hello(), from, ep);
                sendTasksTo(p, true);
            } catch (Exception ignored) { }
            beep(ToneGenerator.TONE_PROP_ACK, 150);
        }
        return p;
    }

    /** Relej kontrolne poruke dalje kroz mesh. */
    private void relay(JSONObject o, String neighbor) throws Exception {
        if (!mesh) return;
        int ttl = o.optInt("ttl", 0);
        if (ttl <= 0) return;
        String key = o.optString("t") + ":" + o.optString("mid") + ":" + o.optInt("att") + ":" + o.optString("from");
        if (seenRelay.containsKey(key)) return;
        seenRelay.put(key, true);
        JSONObject c = new JSONObject(o.toString());
        c.put("ttl", ttl - 1);
        c.put("hop", o.optInt("hop", 0) + 1);
        c.put("fw", myId);
        String origin = o.optString("id");
        for (Peer p : peers.values()) {
            if (p.virt || p.id.equals(neighbor) || p.id.equals(origin)) continue;
            sendTo(p, c);
        }
    }

    private void handleCtrl(JSONObject o, InetAddress from, String ep) throws Exception {
        String t = o.optString("t");
        boolean relayed = o.has("fw");
        String neighbor = relayed ? o.optString("fw") : o.optString("id");
        if (relayed) {
            Peer nb = peers.get(neighbor);
            if (nb != null) {
                nb.lastSeen = System.currentTimeMillis();
                if (from != null) { nb.addr = from; nb.lastLan = nb.lastSeen; }
            }
        }
        String origin = o.optString("id");

        switch (t) {
            case "hello": {
                Peer p = touch(o, from, ep);
                p.talking = o.optBoolean("talk", false);
                p.wfdCap = o.optBoolean("wfd", false);
                p.wfdGo = o.optBoolean("wfdGo", false);
                p.uwbCap = o.optBoolean("uwb", false);
                p.p = (float) o.optDouble("p", Double.NaN);
                p.fl = o.has("fl") ? o.optInt("fl") : Integer.MIN_VALUE;
                if (o.optBoolean("virt")) p.virt = false;   // virtualni (iPhone) – saobraćaj ide preko hosta
                break;
            }
            case "bye": {
                Peer p = peers.remove(origin);
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
            case "bcn": {
                relay(o, neighbor);
                if (peers.containsKey(origin) || origin.equals(myId)) break;
                Remote r = remotes.get(origin);
                boolean isNew = r == null;
                if (isNew) { r = new Remote(); r.id = origin; }
                int hops = o.optInt("hop", 0) + 1;
                if (isNew || hops <= r.hops || System.currentTimeMillis() - r.lastSeen > 4000) {
                    r.hops = hops;
                    Peer nb = peers.get(neighbor);
                    r.via = nb != null ? nb.name : neighbor;
                }
                r.name = o.optString("name", "?");
                r.ch = o.optInt("ch", 1);
                r.p = (float) o.optDouble("p", Double.NaN);
                r.fl = o.has("fl") ? o.optInt("fl") : Integer.MIN_VALUE;
                r.lastSeen = System.currentTimeMillis();
                if (isNew) {
                    remotes.put(origin, r);
                    JSONObject e = new JSONObject();
                    e.put("id", origin);
                    e.put("name", r.name);
                    e.put("hops", r.hops);
                    emit("join", e);
                }
                break;
            }
            case "msg": {
                if (!relayed) touch(o, from, ep);
                String mid = o.optString("mid");
                String to = o.optString("to", "");
                relay(o, neighbor);
                o.put("via", viaLabel(o, from, relayed, neighbor));
                if (o.optInt("on") == 1) {
                    if (!seenMsgs.containsKey(mid) && (to.isEmpty() ? o.optInt("ch", 1) == channel : isMine(to))) {
                        seenMsgs.put(mid, true);
                        deliverMsg(o, to);
                    }
                    break;
                }
                boolean forMe = to.isEmpty() ? o.optInt("ch", 1) == channel : isMine(to);
                if (!forMe) break;
                sendAck(mid, origin, to.isEmpty() ? myId : to);
                if (to.isEmpty()) {
                    WebSink w = web;
                    if (w != null) for (String wid : w.webClients().keySet()) sendAck(mid, origin, wid);
                }
                if (seenMsgs.containsKey(mid)) break;
                seenMsgs.put(mid, true);
                deliverMsg(o, to);
                break;
            }
            case "ack": {
                String to = o.optString("to");
                if (!isMine(to)) { relay(o, neighbor); break; }
                Out ob = outbox.get(o.optString("mid"));
                if (ob != null) {
                    ob.acked.add(o.optString("from", origin));
                    emitDelivered(ob);
                }
                break;
            }
            case "buzz": {
                if (!relayed) touch(o, from, ep);
                relay(o, neighbor);
                String to = o.optString("to");
                String key = "buzz:" + o.optString("mid");
                if (myId.equals(to) && !seenMsgs.containsKey(key)) {
                    seenMsgs.put(key, true);
                    beep(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 600);
                    emit("buzz", o);
                }
                break;
            }
            case "sos": {
                relay(o, neighbor);
                String key = "sos:" + origin;
                boolean on = o.optBoolean("on");
                if (on) {
                    boolean isNew = !sosMap.containsKey(origin);
                    sosMap.put(origin, o);
                    if (isNew) emit("sos", o);
                } else if (sosMap.remove(origin) != null) {
                    emit("sosOff", o);
                }
                seenMsgs.put(key, true);
                WebSink w = web;
                if (w != null) w.webEvent("sos", o);
                break;
            }
            case "sosack": {
                relay(o, neighbor);
                String key = "sosack:" + o.optString("mid");
                if (seenMsgs.containsKey(key)) break;
                seenMsgs.put(key, true);
                emit("sosack", o);
                break;
            }
            case "task": {
                relay(o, neighbor);
                JSONObject tk = o.optJSONObject("task");
                if (tk != null && mergeTask(tk)) { saveTasks(); emitTasks(); }
                break;
            }
            case "tasks": {
                if (!relayed) touch(o, from, ep);
                JSONArray arr = o.optJSONArray("list");
                boolean changed = false;
                if (arr != null) for (int i = 0; i < arr.length(); i++) changed |= mergeTask(arr.getJSONObject(i));
                if (changed) { saveTasks(); emitTasks(); }
                if (o.optBoolean("ask")) {
                    Peer p = peers.get(origin);
                    if (p != null) sendTasksTo(p, false);
                }
                break;
            }
            case "bmeta": {
                if (!relayed) touch(o, from, ep);
                relay(o, neighbor);
                o.put("via", viaLabel(o, from, relayed, neighbor));
                onBlobMeta(o);
                break;
            }
            case "bnack": {
                String to = o.optString("to");
                if (!myId.equals(to)) { relay(o, neighbor); break; }
                onBlobNack(o);
                break;
            }
            case "uwbInit":
            case "uwbAddr": {
                touch(o, from, ep);
                UwbLink u = uwb;
                if (u == null || !myId.equals(o.optString("to"))) break;
                if ("uwbInit".equals(t)) u.onInit(origin, o.toString());
                else u.onAddr(origin, o.toString());
                break;
            }
        }
    }

    private String viaLabel(JSONObject o, InetAddress from, boolean relayed, String neighbor) {
        Peer nb = peers.get(neighbor);
        if (o.optInt("on") == 1) return "online→offline" + (nb != null ? " · " + nb.name : "");
        if (relayed) return "mesh" + (nb != null ? " · " + nb.name : "");
        if (from != null) return from.getHostAddress().startsWith("192.168.49.") ? "Wi-Fi Direct" : "Wi-Fi";
        return "Bluetooth";
    }

    /** Poruka koja je stigla preko interneta → proslijedi je ljudima offline (most). */
    public void injectOnline(final JSONObject m) {
        exec(() -> {
            String mid = m.optString("mid");
            if (mid.isEmpty() || seenMsgs.containsKey(mid) || m.has("img") || m.has("voice")) return;
            seenMsgs.put(mid, true);
            JSONObject o = new JSONObject();
            o.put("t", "msg");
            o.put("id", m.optString("id"));
            o.put("name", m.optString("name"));
            o.put("ch", channel);
            o.put("mid", mid);
            o.put("text", m.optString("text"));
            o.put("ts", m.optLong("ts", System.currentTimeMillis()));
            o.put("on", 1);
            o.put("fw", myId);
            o.put("ttl", MESH_TTL);
            for (int i = 0; i < 2; i++) {
                o.put("att", rnd.nextInt(1 << 30));
                flood(o, null);
            }
        });
    }

    /** Glas koji je stigao preko interneta → pusti ga ovdje (i u historiju). */
    public void playExternal(byte[] ulaw, String id, String name) {
        long h = id.hashCode();
        onlineNames.put(h, name + " 🌍");
        int seq = onlineSeq.containsKey(h) ? onlineSeq.get(h) : 0;
        for (int off = 0; off < ulaw.length; off += SAMPLES) {
            int n = Math.min(SAMPLES, ulaw.length - off);
            byte[] pk = new byte[HEADER + n];
            pk[0] = 'B';
            pk[1] = '2';
            pk[2] = (byte) channel;
            pk[3] = 0;
            for (int i = 0; i < 8; i++) pk[4 + i] = (byte) (h >>> (56 - 8 * i));
            pk[12] = (byte) (seq >> 8);
            pk[13] = (byte) seq;
            seq = (seq + 1) & 0xffff;
            System.arraycopy(ulaw, off, pk, HEADER, n);
            audioQ.offer(pk);
        }
        onlineSeq.put(h, seq);
    }

    /** Medij koji je stigao preko interneta: spremi u fajl, vrati url. */
    public String saveOnlineMedia(String kind, byte[] data) {
        try {
            File f = saveMedia(Media.hex(rnd.nextLong()), kind, data, 0, data.length);
            return Media.url(f);
        } catch (Exception e) {
            return "";
        }
    }

    private void sendAck(String mid, String origin, String fromId) throws Exception {
        JSONObject a = base("ack");
        a.put("mid", mid);
        a.put("to", origin);
        a.put("from", fromId);
        route(origin, a);
    }

    private void deliverMsg(JSONObject o, String to) {
        WebSink w = web;
        boolean toWeb = w != null && !to.isEmpty() && w.webClients().containsKey(to);
        if (w != null && (to.isEmpty() || toWeb)) w.webEvent("msg", o);
        if (to.isEmpty() || myId.equals(to)) emit("msg", o);
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

    private void emitDelivered(Out ob) {
        try {
            JSONObject d = new JSONObject();
            d.put("mid", ob.mid);
            d.put("got", ob.acked.size());
            d.put("total", Math.max(ob.acked.size(), recipients(ob).size() + ob.acked.size()));
            emit("delivered", d);
        } catch (Exception ignored) { }
    }

    // ================================================================ tick

    private void tickLoop() {
        long n = 0;
        while (running) {
            try {
                if (n % 20 == 0) refreshBroadcast();
                NearbyLink nl = nearby;
                if (nl != null && n % 2 == 0) nl.tick();
                if (n % 6 == 0) exec(() -> {
                    sendToAll(hello());
                    sendVirtualHellos();
                });
                if (n % 12 == 9 && mesh) exec(() -> {
                    JSONObject b = hello();
                    b.put("t", "bcn");
                    b.put("mid", myId + ":" + (bcnSeq++));
                    b.put("ttl", MESH_TTL);
                    b.put("hop", 0);
                    flood(b, null);
                });
                if (n % 12 == 3) exec(() -> {
                    JSONObject ping = base("ping");
                    ping.put("ts", System.currentTimeMillis());
                    for (Peer p : peers.values()) sendTo(p, ping);
                });
                if (n % 4 == 1) exec(this::flushOutbox);
                if (n % 8 == 5) exec(this::blobHousekeeping);
                if (n % 16 == 2 && mySos) exec(this::broadcastSos);
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
                    Iterator<Remote> ir = remotes.values().iterator();
                    while (ir.hasNext()) {
                        Remote r = ir.next();
                        if (now - r.lastSeen > REMOTE_TIMEOUT) {
                            ir.remove();
                            JSONObject e = new JSONObject();
                            e.put("id", r.id);
                            e.put("name", r.name);
                            emit("leave", e);
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

    /** Web (iPhone) klijenti se drugima javljaju kao uređaji preko ovog telefona. */
    private void sendVirtualHellos() throws Exception {
        WebSink w = web;
        if (w == null) return;
        for (Map.Entry<String, String> e : w.webClients().entrySet()) {
            JSONObject h = new JSONObject();
            h.put("t", "hello");
            h.put("id", e.getKey());
            h.put("name", e.getValue());
            h.put("ch", channel);
            h.put("virt", true);
            h.put("host", myId);
            sendToAll(h);
        }
    }

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
                if ((w.isGo && w.clients == 0) || w.isClient) {
                    if (wfdLonely == 0) wfdLonely = now;
                    else if (now - wfdLonely > 20000) { w.leave(); wfdLonely = 0; }
                }
            } else {
                wfdLonely = 0;
                if (leader.equals(myId)) {
                    w.ensureGroup();
                } else {
                    Peer L = peers.get(leader);
                    if (L != null && L.wfdGo) {
                        if (w.isGo) w.leave();
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

    public int peerCount() { return peers.size() + remotes.size(); }

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
            o.put("age", now - p.lastSeen);
            o.put("rtt", p.rtt);
            o.put("talk", p.talking);
            o.put("uwb", p.uwbCap);
            o.put("hops", 1);
            if (p.rangeAt > 0 && now - p.rangeAt < 3000) {
                o.put("d", p.dist);
                if (!Float.isNaN(p.az)) o.put("az", p.az);
            }
            putFloor(o, p.fl, p.p);
            arr.put(o);
        }
        for (Remote r : remotes.values()) {
            JSONObject o = new JSONObject();
            o.put("id", r.id);
            o.put("name", r.name);
            o.put("ch", r.ch);
            o.put("via", "mesh");
            o.put("viaName", r.via);
            o.put("hops", r.hops);
            o.put("age", now - r.lastSeen);
            o.put("rtt", -1);
            putFloor(o, r.fl, r.p);
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
        if (floor != Integer.MIN_VALUE) d.put("myFl", floor);
        if (!Float.isNaN(pressure)) d.put("myP", pressure);
        emit("peers", d);
        WebSink w = web;
        if (w != null) w.webEvent("peers", d);
    }

    private void putFloor(JSONObject o, int fl, float p) throws Exception {
        if (fl != Integer.MIN_VALUE) o.put("fl", fl);
        if (!Float.isNaN(p) && !Float.isNaN(pressure)) {
            // ~8.3 m po hPa, ~3.2 m po spratu
            double dh = (pressure - p) * 8.3;
            o.put("dFl", Math.round(dh / 3.2));
        }
    }

    // ================================================================ outbox (tekst + mediji)

    /** Ko još treba dobiti ovu poruku (vidljivi, nisu potvrdili). */
    private List<String> recipients(Out ob) {
        List<String> l = new ArrayList<>();
        if (ob.to != null && !ob.to.isEmpty()) {
            if (!ob.acked.contains(ob.to) && (peers.containsKey(ob.to) || remotes.containsKey(ob.to))) l.add(ob.to);
            return l;
        }
        for (Peer p : peers.values()) if (p.ch == ob.ch && !ob.acked.contains(p.id)) l.add(p.id);
        for (Remote r : remotes.values()) if (r.ch == ob.ch && !ob.acked.contains(r.id)) l.add(r.id);
        return l;
    }

    private void flushOutbox() throws Exception {
        long now = System.currentTimeMillis();
        Iterator<Out> it = outbox.values().iterator();
        while (it.hasNext()) {
            Out ob = it.next();
            if (now - ob.ts > OUTBOX_TTL) {
                it.remove();
                if ("blob".equals(ob.kind)) blobOut.remove(ob.mid);
                continue;
            }
            List<String> due = new ArrayList<>();
            for (String id : recipients(ob)) {
                int s = ob.sends.containsKey(id) ? ob.sends.get(id) : 0;
                long last = ob.lastSent.containsKey(id) ? ob.lastSent.get(id) : 0;
                long gap = Math.min(60000, 4000L * (1 + s));
                if (s < 25 && now - last > gap) due.add(id);
            }
            if (due.isEmpty()) continue;
            for (String id : due) {
                ob.sends.put(id, (ob.sends.containsKey(id) ? ob.sends.get(id) : 0) + 1);
                ob.lastSent.put(id, now);
            }
            ob.att++;
            boolean anyRemote = false;
            for (String id : due) if (!peers.containsKey(id)) anyRemote = true;
            JSONObject m = new JSONObject(ob.msg.toString());
            m.put("att", ob.att);
            m.put("ttl", (mesh && anyRemote) ? MESH_TTL : (mesh ? 1 : 0));
            if ("blob".equals(ob.kind)) {
                sendBlobData(ob.mid, m, due);
            } else {
                for (String id : due) {
                    Peer p = peers.get(id);
                    if (p != null) sendTo(p, m);
                }
                if (anyRemote) flood(m, null);
            }
        }
    }

    public String sendChat(final String text, final String to) {
        return sendChatAs(myId, name, text, to);
    }

    /** Šalje poruku u ime ovog telefona ili web (iPhone) klijenta. */
    public String sendChatAs(final String fromId, final String fromName, final String text, final String to) {
        final String mid = newMid();
        seenMsgs.put(mid, true);
        exec(() -> {
            JSONObject o = new JSONObject();
            o.put("t", "msg");
            o.put("id", fromId);
            o.put("name", fromName);
            o.put("ch", channel);
            o.put("mid", mid);
            o.put("text", text);
            o.put("ts", System.currentTimeMillis());
            boolean priv = to != null && !to.isEmpty();
            if (priv) o.put("to", to);
            Out ob = new Out();
            ob.mid = mid;
            ob.kind = "msg";
            ob.to = priv ? to : "";
            ob.ch = channel;
            ob.ts = System.currentTimeMillis();
            ob.msg = o;
            outbox.put(mid, ob);
            if (!fromId.equals(myId)) {
                // poruka iPhone klijenta: pokaži je i ovdje i ostalim web klijentima
                if (!priv || myId.equals(to)) emit("msg", o);
                WebSink w = web;
                if (w != null) w.webEvent("msg", o);
            }
            flushOutbox();
        });
        return mid;
    }

    public void buzz(final String peerId) {
        exec(() -> {
            JSONObject o = base("buzz");
            o.put("to", peerId);
            o.put("mid", newMid());
            route(peerId, o);
        });
    }

    // ================================================================ blobovi (slike, glasovne poruke)

    /** Spremi i pošalji medij. Vraća {mid, url}. */
    public JSONObject sendBlob(String kind, byte[] data, JSONObject meta, String to, String fromId, String fromName) {
        JSONObject r = new JSONObject();
        try {
            final String bid = Media.hex(rnd.nextLong());
            File f = saveMedia(bid, kind, data, 0, data.length);
            r.put("mid", bid);
            r.put("url", Media.url(f));
            r.put("file", f.getName());
            blobOut.put(bid, data);
            JSONObject m = new JSONObject();
            m.put("t", "bmeta");
            m.put("id", fromId);
            m.put("name", fromName);
            m.put("ch", channel);
            m.put("mid", bid);
            m.put("bid", bid);
            m.put("kind", kind);
            m.put("size", data.length);
            m.put("n", (data.length + CHUNK - 1) / CHUNK);
            m.put("meta", meta == null ? new JSONObject() : meta);
            m.put("ts", System.currentTimeMillis());
            boolean priv = to != null && !to.isEmpty();
            if (priv) m.put("to", to);
            Out ob = new Out();
            ob.mid = bid;
            ob.kind = "blob";
            ob.to = priv ? to : "";
            ob.ch = channel;
            ob.ts = System.currentTimeMillis();
            ob.msg = m;
            outbox.put(bid, ob);
            seenMsgs.put(bid, true);
            if (!fromId.equals(myId)) {
                JSONObject ev = mediaEvent(m, f);
                if (!priv || myId.equals(to)) emit("media", ev);
                WebSink w = web;
                if (w != null) w.webEvent("media", ev);
            }
            exec(this::flushOutbox);
        } catch (Exception e) {
            emitError("Slanje nije uspjelo: " + e.getMessage());
        }
        return r;
    }

    private File saveMedia(String bid, String kind, byte[] data, int off, int len) throws Exception {
        if ("voice".equals(kind)) {
            File f = new File(Media.dir(ctx, "media"), bid + ".wav");
            Media.writeWavFromUlaw(f, data, off, len, 8000);
            return f;
        }
        File f = new File(Media.dir(ctx, "media"), bid + ".jpg");
        Media.writeFile(f, off == 0 && len == data.length ? data : Arrays.copyOfRange(data, off, off + len));
        return f;
    }

    private JSONObject mediaEvent(JSONObject meta, File f) throws Exception {
        JSONObject ev = new JSONObject();
        ev.put("mid", meta.optString("bid"));
        ev.put("id", meta.optString("id"));
        ev.put("name", meta.optString("name"));
        ev.put("kind", meta.optString("kind"));
        ev.put("url", Media.url(f));
        ev.put("file", f.getName());
        ev.put("ts", meta.optLong("ts", System.currentTimeMillis()));
        ev.put("to", meta.optString("to", ""));
        ev.put("meta", meta.optJSONObject("meta"));
        ev.put("via", meta.optString("via", ""));
        return ev;
    }

    /** Pošalji meta + sve komade (na sender niti, polako). */
    private void sendBlobData(String bid, JSONObject meta, List<String> due) {
        final byte[] data = blobOut.get(bid);
        if (data == null) return;
        final int ttl = meta.optInt("ttl", 0);
        final List<Peer> direct = new ArrayList<>();
        boolean anyRemote = false;
        for (String id : due) {
            Peer p = peers.get(id);
            if (p != null) direct.add(p); else anyRemote = true;
        }
        final List<Peer> targets = anyRemote ? new ArrayList<>(peers.values()) : direct;
        try {
            sender.execute(() -> {
                for (Peer p : targets) sendTo(p, meta);
                int n = (data.length + CHUNK - 1) / CHUNK;
                for (int i = 0; i < n; i++) {
                    byte[] pk = chunkPacket(bid, i, n, data, ttl);
                    for (Peer p : targets) sendBin(p, pk, pk.length);
                    if (i % 8 == 7) try { Thread.sleep(4); } catch (InterruptedException ignored) { }
                }
            });
        } catch (Exception ignored) { }
    }

    private byte[] chunkPacket(String bid, int i, int n, byte[] data, int ttl) {
        int off = i * CHUNK;
        int len = Math.min(CHUNK, data.length - off);
        byte[] pk = new byte[BHEAD + len];
        pk[0] = 'B';
        pk[1] = '3';
        pk[2] = (byte) ttl;
        pk[3] = 0;
        long b = new java.math.BigInteger(bid, 16).longValue();
        for (int k = 0; k < 8; k++) pk[4 + k] = (byte) (b >>> (56 - 8 * k));
        pk[12] = (byte) (i >> 8);
        pk[13] = (byte) i;
        pk[14] = (byte) (n >> 8);
        pk[15] = (byte) n;
        System.arraycopy(data, off, pk, BHEAD, len);
        return pk;
    }

    private void onBlobMeta(JSONObject o) throws Exception {
        String bid = o.optString("bid");
        String to = o.optString("to", "");
        String origin = o.optString("id");
        boolean forMe = to.isEmpty() ? o.optInt("ch", 1) == channel : isMine(to);
        if (seenMsgs.containsKey(bid)) {
            if (forMe) sendAck(bid, origin, to.isEmpty() ? myId : to);
            return;
        }
        if (!forMe) return;
        In in = blobIn.get(bid);
        if (in == null) {
            in = new In();
            in.bid = bid;
            in.n = o.optInt("n");
            in.chunks = new byte[in.n][];
            blobIn.put(bid, in);
        }
        in.meta = o;
        in.origin = origin;
        in.lastRx = System.currentTimeMillis();
        checkBlobDone(in);
    }

    private void onChunk(byte[] b, int len, String src) {
        int ttl = b[2];
        long bl = 0;
        for (int k = 0; k < 8; k++) bl = (bl << 8) | (b[4 + k] & 0xff);
        final String bid = Media.hex(bl);
        int i = ((b[12] & 0xff) << 8) | (b[13] & 0xff);
        int n = ((b[14] & 0xff) << 8) | (b[15] & 0xff);
        String key = bid + ":" + i;
        long now = System.currentTimeMillis();
        Long last = chunkSeen.get(key);
        if (last != null && now - last < 1500) return;
        chunkSeen.put(key, now);
        // relej
        if (mesh && ttl > 0) {
            byte[] c = Arrays.copyOf(b, len);
            c[2] = (byte) (ttl - 1);
            relayBin(c, src);
        }
        if (seenMsgs.containsKey(bid)) return;
        final byte[] data = Arrays.copyOfRange(b, BHEAD, len);
        final int fi = i, fn = n;
        exec(() -> {
            In in = blobIn.get(bid);
            if (in == null) {
                in = new In();
                in.bid = bid;
                in.n = fn;
                in.chunks = new byte[fn][];
                blobIn.put(bid, in);
            }
            if (fi < in.n && in.chunks[fi] == null) {
                in.chunks[fi] = data;
                in.got++;
            }
            in.lastRx = System.currentTimeMillis();
            checkBlobDone(in);
        });
    }

    private void checkBlobDone(In in) throws Exception {
        if (in.meta == null || in.got < in.n) return;
        JSONObject m = in.meta;
        String to = m.optString("to", "");
        boolean forMe = to.isEmpty() ? m.optInt("ch", 1) == channel : isMine(to);
        blobIn.remove(in.bid);
        seenMsgs.put(in.bid, true);
        if (!forMe) return;
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        for (byte[] c : in.chunks) bo.write(c);
        byte[] all = bo.toByteArray();
        File f = saveMedia(in.bid, m.optString("kind"), all, 0, all.length);
        sendAck(in.bid, in.origin, to.isEmpty() ? myId : to);
        if (to.isEmpty()) {
            WebSink w = web;
            if (w != null) for (String wid : w.webClients().keySet()) sendAck(in.bid, in.origin, wid);
        }
        JSONObject ev = mediaEvent(m, f);
        WebSink w = web;
        if (w != null) w.webEvent("media", ev);
        if (to.isEmpty() || myId.equals(to)) emit("media", ev);
    }

    private void blobHousekeeping() throws Exception {
        long now = System.currentTimeMillis();
        Iterator<In> it = blobIn.values().iterator();
        while (it.hasNext()) {
            In in = it.next();
            if (now - in.lastRx > 10 * 60_000L) { it.remove(); continue; }
            if (in.meta == null || in.got >= in.n) continue;
            if (now - in.lastRx > 2500 && in.nacks < 15) {
                in.nacks++;
                JSONArray miss = new JSONArray();
                for (int i = 0; i < in.n && miss.length() < 300; i++) if (in.chunks[i] == null) miss.put(i);
                JSONObject q = base("bnack");
                q.put("to", in.origin);
                q.put("bid", in.bid);
                q.put("mid", in.bid + ":" + in.nacks);
                q.put("miss", miss);
                route(in.origin, q);
                in.lastRx = now;
            }
        }
    }

    private void onBlobNack(JSONObject o) {
        final String bid = o.optString("bid");
        final byte[] data = blobOut.get(bid);
        if (data == null) return;
        final JSONArray miss = o.optJSONArray("miss");
        if (miss == null) return;
        final String req = o.optString("id");
        final Peer direct = peers.get(req);
        final int n = (data.length + CHUNK - 1) / CHUNK;
        try {
            sender.execute(() -> {
                for (int k = 0; k < miss.length(); k++) {
                    int i = miss.optInt(k, -1);
                    if (i < 0 || i >= n) continue;
                    byte[] pk = chunkPacket(bid, i, n, data, direct != null ? 0 : (mesh ? MESH_TTL : 0));
                    if (direct != null) sendBin(direct, pk, pk.length);
                    else for (Peer p : peers.values()) sendBin(p, pk, pk.length);
                    if (k % 8 == 7) try { Thread.sleep(4); } catch (InterruptedException ignored) { }
                }
            });
        } catch (Exception ignored) { }
    }

    // ================================================================ binarni prijem (glas + komadi)

    private void udpBinLoop() {
        byte[] buf = new byte[4096];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                audioSock.receive(p);
                int len = p.getLength();
                if (len > 2 && buf[0] == 'B' && (buf[1] == '2' || buf[1] == '3')) {
                    onBinary(Arrays.copyOf(buf, len), len, "ip:" + p.getAddress().getHostAddress());
                }
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private void onBinary(byte[] b, int len, String src) {
        if (b[1] == '3') {
            if (len > BHEAD) onChunk(b, len, src);
            return;
        }
        if (len <= HEADER) return;
        long h = 0;
        for (int i = 0; i < 8; i++) h = (h << 8) | (b[4 + i] & 0xff);
        if (h == myHash) return;
        int seq = ((b[12] & 0xff) << 8) | (b[13] & 0xff);
        long key = (h << 16) ^ seq;
        Long last = audioSeen.get(key);
        long now = System.currentTimeMillis();
        if (last != null && now - last < 30000) return;
        audioSeen.put(key, now);
        int ttl = (b[3] >> 4) & 0x0f;
        boolean priv = (b[3] & 1) == 1;
        if (mesh && ttl > 0 && !priv) {
            byte[] c = Arrays.copyOf(b, len);
            c[3] = (byte) ((c[3] & 0x0f) | ((ttl - 1) << 4));
            relayBin(c, src);
        }
        audioQ.offer(len == b.length ? b : Arrays.copyOf(b, len));
    }

    private void relayBin(byte[] c, String src) {
        for (Peer p : peers.values()) {
            if (p.virt) continue;
            if (src.startsWith("ip:") && p.addr != null && src.equals("ip:" + p.addr.getHostAddress())) continue;
            if (src.startsWith("ep:") && p.ep != null && src.equals("ep:" + p.ep)) continue;
            sendBin(p, c, c.length);
        }
    }

    // ================================================================ walkie-talkie TX

    public synchronized void startTalk(String target) {
        if (talking || noting) return;
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

    private boolean routeHeadset(AudioManager am) {
        if (!headsetMic || Build.VERSION.SDK_INT < 31) return false;
        try {
            for (AudioDeviceInfo d : am.getAvailableCommunicationDevices()) {
                if (d.getType() == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || d.getType() == AudioDeviceInfo.TYPE_BLE_HEADSET) {
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    if (am.setCommunicationDevice(d)) {
                        Thread.sleep(350);
                        return true;
                    }
                }
            }
        } catch (Exception ignored) { }
        return false;
    }

    private void talkLoop() {
        AudioRecord rec = null;
        NoiseSuppressor ns = null;
        AutomaticGainControl agc = null;
        AcousticEchoCanceler aec = null;
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        boolean routed = routeHeadset(am);
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
            ByteArrayOutputStream onBuf = new ByteArrayOutputStream();

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
                    pkt[HEADER + i] = Media.ulaw(s);
                }
                String tgt = talkTarget;
                int ttl = (mesh && tgt == null) ? MESH_TTL : 0;
                pkt[2] = (byte) channel;
                pkt[3] = (byte) ((tgt != null ? 1 : 0) | (ttl << 4));
                pkt[12] = (byte) (seq >> 8);
                pkt[13] = (byte) seq;
                seq = (seq + 1) & 0xffff;
                byte[] out = Arrays.copyOf(pkt, HEADER + n);

                WebSink w = web;
                if (w != null && tgt == null) w.webAudio(out, HEADER, n);
                if (onlineAudio && tgt == null) {
                    onBuf.write(out, HEADER, n);
                    if (onBuf.size() >= 1600) {
                        JSONObject ox = new JSONObject();
                        ox.put("b64", android.util.Base64.encodeToString(onBuf.toByteArray(), android.util.Base64.NO_WRAP));
                        emit("otx", ox);
                        onBuf.reset();
                    }
                }

                int sent = 0;
                if (tgt != null) {
                    Peer p = peers.get(tgt);
                    if (p != null) { sendBin(p, out, out.length); sent++; }
                } else {
                    for (Peer p : peers.values()) if (p.ch == channel || mesh) { sendBin(p, out, out.length); if (p.ch == channel) sent++; }
                    if (peers.isEmpty()) {
                        for (InetAddress b : bcast) udpBin(out, out.length, b);
                        NearbyLink nl = nearby;
                        if (nl != null) nl.sendAll(out);
                    }
                    for (Remote r : remotes.values()) if (r.ch == channel) sent++;
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
            if (routed && Build.VERSION.SDK_INT >= 31) {
                try { am.clearCommunicationDevice(); am.setMode(AudioManager.MODE_NORMAL); } catch (Exception ignored) { }
            }
            talking = false;
            beep(ToneGenerator.TONE_PROP_BEEP2, 120);
            emitTx(false);
        }
    }

    private void emitTx(boolean on) {
        try {
            JSONObject o = new JSONObject();
            o.put("on", on);
            emit("tx", o);
        } catch (Exception ignored) { }
    }

    // ================================================================ glasovne poruke (snimanje)

    public synchronized boolean startVoiceNote() {
        if (talking || noting) return false;
        noting = true;
        noteBuf = new ByteArrayOutputStream();
        noteStart = System.currentTimeMillis();
        new Thread(() -> {
            AudioRecord rec = null;
            try {
                int min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                rec = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 4096));
                if (rec.getState() != AudioRecord.STATE_INITIALIZED) { emitError("Mikrofon nije dostupan"); noting = false; return; }
                rec.startRecording();
                short[] pcm = new short[400];
                byte[] u = new byte[400];
                while (noting && running && System.currentTimeMillis() - noteStart < 60000) {
                    int n = rec.read(pcm, 0, pcm.length);
                    if (n <= 0) continue;
                    for (int i = 0; i < n; i++) {
                        int s = Math.round(pcm[i] * gain);
                        if (s > 32767) s = 32767;
                        if (s < -32768) s = -32768;
                        u[i] = Media.ulaw(s);
                    }
                    synchronized (this) { if (noteBuf != null) noteBuf.write(u, 0, n); }
                }
            } catch (Exception e) {
                emitError("Snimanje: " + e.getMessage());
            } finally {
                try { if (rec != null) { rec.stop(); rec.release(); } } catch (Exception ignored) { }
            }
        }, "note").start();
        return true;
    }

    /** Završi snimanje i pošalji. cancel=true baca snimak. */
    public JSONObject stopVoiceNote(boolean cancel, String to) {
        noting = false;
        try { Thread.sleep(120); } catch (InterruptedException ignored) { }
        byte[] data;
        synchronized (this) {
            data = noteBuf != null ? noteBuf.toByteArray() : new byte[0];
            noteBuf = null;
        }
        if (cancel || data.length < 2400) return new JSONObject();   // < 0.3 s
        try {
            JSONObject meta = new JSONObject();
            meta.put("dur", Math.round(data.length / 800.0) / 10.0);
            JSONObject r = sendBlob("voice", data, meta, to, myId, name);
            r.put("dur", meta.optDouble("dur"));
            r.put("b64", android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP));
            return r;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public boolean isNoting() { return noting; }

    // ================================================================ walkie-talkie RX + historija

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
        long speaker = 0, lastPkt = 0, startedAt = 0;
        boolean spPriv = false;
        ByteArrayOutputStream rec = new ByteArrayOutputStream();

        while (running) {
            try {
                byte[] b = audioQ.poll(150, TimeUnit.MILLISECONDS);
                long now = System.currentTimeMillis();
                if (speaker != 0 && now - lastPkt > 500) {
                    endRx(track, speaker, rec, startedAt, spPriv);
                    rec.reset();
                    speaker = 0;
                }
                if (b == null) continue;

                long h = 0;
                for (int i = 0; i < 8; i++) h = (h << 8) | (b[4 + i] & 0xff);
                if (talking) continue;
                boolean priv = (b[3] & 1) == 1;
                if (!priv && b[2] != channel) continue;

                int n = Math.min(b.length - HEADER, pcm.length);
                WebSink w = web;
                if (w != null && !priv) w.webAudio(b, HEADER, n);

                if (speaker == 0) {
                    speaker = h;
                    startedAt = now;
                    spPriv = priv;
                    beep(ToneGenerator.TONE_PROP_BEEP, 60);
                    if (track != null) {
                        track.play();
                        track.write(silence, 0, silence.length);
                    }
                    String[] who = whoByHash(h);
                    JSONObject o = new JSONObject();
                    o.put("on", true);
                    o.put("id", who[0]);
                    o.put("name", who[1]);
                    o.put("priv", priv);
                    emit("rx", o);
                }
                if (h != speaker) continue;
                lastPkt = now;
                if (rec.size() < 16000 * 90) rec.write(b, HEADER, n);
                for (int i = 0; i < n; i++) pcm[i] = Media.ULAW_DEC[b[HEADER + i] & 0xff];
                if (track != null) track.write(pcm, 0, n);
            } catch (Exception e) {
                if (!running) break;
            }
        }
        try { if (track != null) track.release(); } catch (Exception ignored) { }
    }

    private String[] whoByHash(long h) {
        for (Peer p : peers.values()) if (p.hash == h) return new String[]{p.id, p.name};
        for (Remote r : remotes.values()) if ((long) r.id.hashCode() == h) return new String[]{r.id, r.name};
        String on = onlineNames.get(h);
        if (on != null) return new String[]{"", on};
        return new String[]{"", "?"};
    }

    private void endRx(AudioTrack track, long speaker, ByteArrayOutputStream rec, long startedAt, boolean priv) {
        try { if (track != null) { track.stop(); track.flush(); } } catch (Exception ignored) { }
        beep(ToneGenerator.TONE_PROP_BEEP2, 100);
        String[] who = whoByHash(speaker);
        try {
            JSONObject o = new JSONObject();
            o.put("on", false);
            o.put("id", who[0]);
            emit("rx", o);
        } catch (Exception ignored) { }
        // historija: zadnjih 10 primljenih
        if (rec.size() >= RATE / 2) {
            try {
                byte[] d = rec.toByteArray();
                File f = new File(Media.dir(ctx, "hist"), startedAt + ".wav");
                Media.writeWavFromUlaw(f, d, 0, d.length, RATE);
                JSONObject e = new JSONObject();
                e.put("name", who[1]);
                e.put("id", who[0]);
                e.put("ts", startedAt);
                e.put("dur", Math.round(d.length / 1600.0) / 10.0);
                e.put("url", Media.url(f));
                e.put("priv", priv);
                synchronized (history) {
                    history.addFirst(e);
                    while (history.size() > 10) {
                        JSONObject old = history.removeLast();
                        try { new File(old.optString("url").replace("file://", "")).delete(); } catch (Exception ignored) { }
                    }
                }
                saveHistory();
                emit("hist", historyJson());
            } catch (Exception ignored) { }
        }
    }

    public JSONObject historyJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = new JSONArray();
            synchronized (history) { for (JSONObject e : history) a.put(e); }
            o.put("list", a);
        } catch (Exception ignored) { }
        return o;
    }

    private void saveHistory() {
        prefs.edit().putString("hist", historyJson().optJSONArray("list").toString()).apply();
    }

    private void loadHistory() {
        try {
            JSONArray a = new JSONArray(prefs.getString("hist", "[]"));
            synchronized (history) {
                history.clear();
                for (int i = 0; i < a.length(); i++) {
                    JSONObject e = a.getJSONObject(i);
                    if (new File(e.optString("url").replace("file://", "")).exists()) history.addLast(e);
                }
            }
        } catch (Exception ignored) { }
    }

    // ================================================================ SOS

    public void startSos(String reason) {
        mySos = true;
        mySosReason = reason == null ? "" : reason;
        exec(this::broadcastSos);
        try {
            JSONObject o = new JSONObject();
            o.put("on", true);
            o.put("reason", mySosReason);
            emit("mySos", o);
        } catch (Exception ignored) { }
    }

    public void stopSos() {
        if (!mySos) return;
        mySos = false;
        exec(() -> {
            for (int i = 0; i < 3; i++) broadcastSosState(false);
            JSONObject o = new JSONObject();
            o.put("on", false);
            emit("mySos", o);
        });
    }

    public boolean isSos() { return mySos; }

    private void broadcastSos() throws Exception {
        if (mySos) broadcastSosState(true);
    }

    private void broadcastSosState(boolean on) throws Exception {
        JSONObject s = base("sos");
        s.put("on", on);
        s.put("reason", mySosReason);
        s.put("mid", newMid());
        s.put("ttl", mesh ? MESH_TTL : 0);
        if (floor != Integer.MIN_VALUE) s.put("fl", floor);
        s.put("ts", System.currentTimeMillis());
        flood(s, null);
        for (InetAddress a : bcast) udp(bytes(s), a);
    }

    public void ackSos(String originId) {
        exec(() -> {
            JSONObject a = base("sosack");
            a.put("for", originId);
            a.put("mid", newMid());
            a.put("ttl", mesh ? MESH_TTL : 0);
            flood(a, null);
        });
    }

    // ================================================================ zadaci

    private void loadTasks() {
        try {
            JSONArray a = new JSONArray(prefs.getString("tasks", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject t = a.getJSONObject(i);
                tasks.put(t.getString("tid"), t);
            }
        } catch (Exception ignored) { }
    }

    private void saveTasks() {
        JSONArray a = new JSONArray();
        for (JSONObject t : tasks.values()) a.put(t);
        prefs.edit().putString("tasks", a.toString()).apply();
    }

    public JSONObject tasksJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = new JSONArray();
            for (JSONObject t : tasks.values()) if (!t.optBoolean("del")) a.put(t);
            o.put("list", a);
        } catch (Exception ignored) { }
        return o;
    }

    private void emitTasks() { emit("tasks", tasksJson()); }

    /** LWW: novije (ts) pobjeđuje. */
    private boolean mergeTask(JSONObject t) {
        String id = t.optString("tid");
        if (id.isEmpty()) return false;
        JSONObject cur = tasks.get(id);
        long a = t.optLong("ts"), b = cur == null ? -1 : cur.optLong("ts");
        if (cur == null || a > b || (a == b && t.optString("byId").compareTo(cur.optString("byId")) > 0)) {
            tasks.put(id, t);
            return true;
        }
        return false;
    }

    /** Iz UI-ja: novi ili izmijenjen zadatak. */
    public void saveTask(JSONObject in) {
        exec(() -> {
            JSONObject t = new JSONObject(in.toString());
            if (t.optString("tid").isEmpty()) {
                t.put("tid", newMid());
                t.put("by", name);
                t.put("created", System.currentTimeMillis());
            }
            t.put("ts", System.currentTimeMillis());
            t.put("byId", myId);
            t.put("editBy", name);
            tasks.put(t.getString("tid"), t);
            saveTasks();
            emitTasks();
            JSONObject m = base("task");
            m.put("task", t);
            m.put("mid", newMid());
            m.put("ttl", mesh ? MESH_TTL : 0);
            flood(m, null);
        });
    }

    private void sendTasksTo(Peer p, boolean ask) throws Exception {
        JSONArray a = new JSONArray();
        for (JSONObject t : tasks.values()) a.put(t);
        if (a.length() == 0 && !ask) return;
        JSONObject m = base("tasks");
        m.put("list", a);
        m.put("ask", ask);
        sendTo(p, m);
    }
}
