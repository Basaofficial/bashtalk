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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mrežni + audio motor: pronalazak uređaja (UDP broadcast), chat s potvrdom
 * prijema i walkie-talkie (PCM 16 kHz preko UDP-a). Radi na bilo kojoj
 * lokalnoj Wi-Fi mreži ili hotspotu, bez interneta.
 */
public class Engine {

    public interface Listener {
        void onEvent(String type, JSONObject data);
    }

    static final int CTRL_PORT = 47100;
    static final int AUDIO_PORT = 47101;
    static final int RATE = 16000;
    static final int FRAME = 640;          // 20 ms, 16-bit mono
    static final int HEADER = 14;
    static final long PEER_TIMEOUT = 6000;

    static class Peer {
        String id, name;
        long hash;
        InetAddress addr;
        int ch;
        long lastSeen, joined;
        boolean talking;
        int rtt = -1;
    }

    static class Pending {
        JSONObject msg;
        Set<String> waiting;
        int tries;
        int total;
    }

    private final Context ctx;
    private final Listener listener;

    final String myId;
    final long myHash;
    volatile String name;
    volatile int channel = 1;
    volatile float gain = 1.5f;
    volatile boolean sounds = true;

    private DatagramSocket ctrlSock, audioSock;
    private volatile boolean running;
    private WifiManager.MulticastLock mlock;

    final ConcurrentHashMap<String, Peer> peers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Boolean> seenMsgs = Collections.synchronizedMap(
            new LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > 600;
                }
            });

    private volatile boolean talking;
    private volatile String talkTarget;   // null = svi na kanalu
    private Thread talkThread;
    private volatile List<InetAddress> bcast = new ArrayList<>();
    private ToneGenerator tone;

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
        try {
            tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (Exception ignored) { }

        try {
            ctrlSock = new DatagramSocket(null);
            ctrlSock.setReuseAddress(true);
            ctrlSock.setBroadcast(true);
            ctrlSock.bind(new InetSocketAddress(CTRL_PORT));

            audioSock = new DatagramSocket(null);
            audioSock.setReuseAddress(true);
            audioSock.setBroadcast(true);
            audioSock.bind(new InetSocketAddress(AUDIO_PORT));
            audioSock.setSoTimeout(150);
        } catch (Exception e) {
            emitError("Ne mogu otvoriti mrežni port: " + e.getMessage());
            running = false;
            return;
        }

        new Thread(this::ctrlLoop, "ctrl-rx").start();
        new Thread(this::audioLoop, "audio-rx").start();
        new Thread(this::tickLoop, "tick").start();
    }

    public void stop() {
        if (!running) return;
        try {
            JSONObject bye = base("bye");
            sendToAll(bye);
        } catch (Exception ignored) { }
        running = false;
        talking = false;
        try { ctrlSock.close(); } catch (Exception ignored) { }
        try { audioSock.close(); } catch (Exception ignored) { }
        try { if (mlock != null) mlock.release(); } catch (Exception ignored) { }
        try { if (tone != null) tone.release(); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ helpers

    private JSONObject base(String type) throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", type);
        o.put("id", myId);
        o.put("name", name);
        o.put("ch", channel);
        return o;
    }

    private void send(JSONObject o, InetAddress to) {
        try {
            byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
            ctrlSock.send(new DatagramPacket(b, b.length, to, CTRL_PORT));
        } catch (Exception ignored) { }
    }

    private void broadcast(JSONObject o) {
        for (InetAddress a : bcast) send(o, a);
    }

    private void sendToAll(JSONObject o) {
        broadcast(o);
        for (Peer p : peers.values()) send(o, p.addr);
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

    /** Sve IPv4 adrese ovog uređaja (Wi-Fi, hotspot...). */
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

    private boolean isMyAddress(InetAddress a) {
        try { return NetworkInterface.getByInetAddress(a) != null; } catch (Exception e) { return false; }
    }

    // ------------------------------------------------------------ control rx

    private void ctrlLoop() {
        byte[] buf = new byte[65000];
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ctrlSock.receive(p);
                String s = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8);
                JSONObject o = new JSONObject(s);
                String id = o.optString("id");
                if (id.isEmpty() || id.equals(myId)) continue;
                handleCtrl(o, p.getAddress());
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private Peer touch(JSONObject o, InetAddress from) {
        String id = o.optString("id");
        Peer p = peers.get(id);
        boolean isNew = p == null;
        if (isNew) {
            p = new Peer();
            p.id = id;
            p.hash = id.hashCode();
            p.joined = System.currentTimeMillis();
        }
        p.name = o.optString("name", "?");
        p.addr = from;
        p.ch = o.optInt("ch", 1);
        p.lastSeen = System.currentTimeMillis();
        if (isNew) {
            peers.put(id, p);
            try {
                JSONObject e = new JSONObject();
                e.put("id", id);
                e.put("name", p.name);
                e.put("ch", p.ch);
                e.put("ip", from.getHostAddress());
                emit("join", e);
            } catch (Exception ignored) { }
            beep(ToneGenerator.TONE_PROP_ACK, 150);
            // odmah se javi novom uređaju da i on nas vidi
            try { send(base("hello"), from); } catch (Exception ignored) { }
        }
        return p;
    }

    private void handleCtrl(JSONObject o, InetAddress from) throws Exception {
        String t = o.optString("t");
        switch (t) {
            case "hello": {
                Peer p = touch(o, from);
                p.talking = o.optBoolean("talk", false);
                break;
            }
            case "bye": {
                Peer p = peers.remove(o.optString("id"));
                if (p != null) emitLeave(p);
                break;
            }
            case "ping": {
                touch(o, from);
                JSONObject r = base("pong");
                r.put("ts", o.optLong("ts"));
                send(r, from);
                break;
            }
            case "pong": {
                Peer p = touch(o, from);
                p.rtt = (int) Math.max(1, System.currentTimeMillis() - o.optLong("ts"));
                break;
            }
            case "msg": {
                touch(o, from);
                String mid = o.optString("mid");
                JSONObject ack = base("ack");
                ack.put("mid", mid);
                send(ack, from);
                if (seenMsgs.containsKey(mid)) break;
                seenMsgs.put(mid, true);
                String to = o.optString("to", "");
                boolean priv = !to.isEmpty();
                if (priv && !to.equals(myId)) break;
                if (!priv && o.optInt("ch", 1) != channel) break;
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
                touch(o, from);
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
                if (n % 20 == 0) refreshBroadcast();            // 5 s
                if (n % 6 == 0) {                              // 1.5 s
                    JSONObject h = base("hello");
                    h.put("talk", talking);
                    sendToAll(h);
                }
                if (n % 12 == 3) {                             // 3 s
                    JSONObject ping = base("ping");
                    ping.put("ts", System.currentTimeMillis());
                    for (Peer p : peers.values()) send(ping, p.addr);
                }
                if (n % 2 == 0) retryPending();                // 0.5 s
                if (n % 4 == 0) {                              // 1 s
                    long now = System.currentTimeMillis();
                    Iterator<Peer> it = peers.values().iterator();
                    while (it.hasNext()) {
                        Peer p = it.next();
                        if (now - p.lastSeen > PEER_TIMEOUT) {
                            it.remove();
                            emitLeave(p);
                        }
                    }
                    emitPeers();
                }
                n++;
                Thread.sleep(250);
            } catch (Exception e) {
                if (!running) break;
            }
        }
    }

    private void emitPeers() throws Exception {
        JSONArray arr = new JSONArray();
        long now = System.currentTimeMillis();
        for (Peer p : peers.values()) {
            JSONObject o = new JSONObject();
            o.put("id", p.id);
            o.put("name", p.name);
            o.put("ch", p.ch);
            o.put("ip", p.addr.getHostAddress());
            o.put("age", now - p.lastSeen);
            o.put("rtt", p.rtt);
            o.put("talk", p.talking);
            o.put("since", now - p.joined);
            arr.put(o);
        }
        JSONObject d = new JSONObject();
        d.put("peers", arr);
        d.put("ips", localIps());
        emit("peers", d);
    }

    private void retryPending() {
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending pd = it.next().getValue();
            pd.tries++;
            if (pd.tries > 10 || pd.waiting.isEmpty()) {
                it.remove();
                continue;
            }
            for (String id : new ArrayList<>(pd.waiting)) {
                Peer p = peers.get(id);
                if (p == null) { pd.waiting.remove(id); continue; }
                send(pd.msg, p.addr);
            }
        }
    }

    // ------------------------------------------------------------ chat API

    /** @param to id primaoca ili "" za sve na kanalu */
    public String sendChat(String text, String to) {
        try {
            String mid = UUID.randomUUID().toString().substring(0, 12);
            JSONObject o = base("msg");
            o.put("mid", mid);
            o.put("text", text);
            o.put("ts", System.currentTimeMillis());
            if (to != null && !to.isEmpty()) o.put("to", to);
            seenMsgs.put(mid, true);

            Pending pd = new Pending();
            pd.msg = o;
            pd.waiting = ConcurrentHashMap.newKeySet();
            for (Peer p : peers.values()) {
                if (to != null && !to.isEmpty()) {
                    if (p.id.equals(to)) pd.waiting.add(p.id);
                } else if (p.ch == channel) {
                    pd.waiting.add(p.id);
                }
            }
            pd.total = pd.waiting.size();
            if (!pd.waiting.isEmpty()) pending.put(mid, pd);

            for (String id : pd.waiting) {
                Peer p = peers.get(id);
                if (p != null) send(o, p.addr);
            }
            if (to == null || to.isEmpty()) broadcast(o);   // i za one koje još ne vidimo
            return mid;
        } catch (Exception e) {
            return "";
        }
    }

    public void buzz(String peerId) {
        Peer p = peers.get(peerId);
        if (p == null) return;
        try {
            JSONObject o = base("buzz");
            o.put("to", peerId);
            for (int i = 0; i < 3; i++) send(o, p.addr);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ walkie-talkie TX

    public synchronized void startTalk(String target) {
        if (talking) return;
        talking = true;
        talkTarget = (target == null || target.isEmpty()) ? null : target;
        beep(ToneGenerator.TONE_PROP_BEEP, 80);
        talkThread = new Thread(this::talkLoop, "talk");
        talkThread.start();
    }

    public synchronized void stopTalk() {
        talking = false;
    }

    public boolean isTalking() { return talking; }

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
                talking = false;
                emitTx(false);
                return;
            }
            int sid = rec.getAudioSessionId();
            try { if (NoiseSuppressor.isAvailable()) { ns = NoiseSuppressor.create(sid); if (ns != null) ns.setEnabled(true); } } catch (Exception ignored) { }
            try { if (AutomaticGainControl.isAvailable()) { agc = AutomaticGainControl.create(sid); if (agc != null) agc.setEnabled(true); } } catch (Exception ignored) { }
            try { if (AcousticEchoCanceler.isAvailable()) { aec = AcousticEchoCanceler.create(sid); if (aec != null) aec.setEnabled(true); } } catch (Exception ignored) { }

            rec.startRecording();
            emitTx(true);

            byte[] pcm = new byte[FRAME];
            byte[] pkt = new byte[HEADER + FRAME];
            pkt[0] = 'B';
            pkt[1] = 'T';
            long h = myHash;
            for (int i = 0; i < 8; i++) pkt[4 + i] = (byte) (h >>> (56 - 8 * i));
            int seq = 0;
            int frames = 0;

            while (talking && running) {
                int n = rec.read(pcm, 0, FRAME);
                if (n <= 0) continue;

                // pojačanje + nivo
                float g = gain;
                long sum = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int s = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
                    s = Math.round(s * g);
                    if (s > 32767) s = 32767;
                    if (s < -32768) s = -32768;
                    pcm[i] = (byte) s;
                    pcm[i + 1] = (byte) (s >> 8);
                    sum += (long) s * s;
                }

                String tgt = talkTarget;
                pkt[2] = (byte) channel;
                pkt[3] = (byte) (tgt != null ? 1 : 0);
                pkt[12] = (byte) (seq >> 8);
                pkt[13] = (byte) seq;
                seq = (seq + 1) & 0xffff;
                System.arraycopy(pcm, 0, pkt, HEADER, n);
                int len = HEADER + n;

                int sent = 0;
                if (tgt != null) {
                    Peer p = peers.get(tgt);
                    if (p != null) { sendAudio(pkt, len, p.addr); sent++; }
                } else {
                    for (Peer p : peers.values()) {
                        if (p.ch == channel) { sendAudio(pkt, len, p.addr); sent++; }
                    }
                    if (sent == 0) for (InetAddress b : bcast) sendAudio(pkt, len, b);
                }

                if (++frames % 4 == 0) {
                    double rms = Math.sqrt(sum / (double) Math.max(1, n / 2)) / 32768.0;
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

    private void sendAudio(byte[] b, int len, InetAddress to) {
        try { audioSock.send(new DatagramPacket(b, len, to, AUDIO_PORT)); } catch (Exception ignored) { }
    }

    private void emitTx(boolean on) {
        try {
            JSONObject o = new JSONObject();
            o.put("on", on);
            emit("tx", o);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ walkie-talkie RX

    private void audioLoop() {
        byte[] buf = new byte[4096];
        AudioTrack track = null;
        long speaker = 0;
        long lastPkt = 0;
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

        byte[] silence = new byte[FRAME * 3];

        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    audioSock.receive(p);
                } catch (SocketTimeoutException te) {
                    if (speaker != 0 && System.currentTimeMillis() - lastPkt > 500) {
                        endRx(track, speaker);
                        speaker = 0;
                    }
                    continue;
                }
                int len = p.getLength();
                if (len <= HEADER || buf[0] != 'B' || buf[1] != 'T') continue;
                long h = 0;
                for (int i = 0; i < 8; i++) h = (h << 8) | (buf[4 + i] & 0xff);
                if (h == myHash) continue;
                if (talking) continue;                    // half-duplex kao pravi walkie-talkie
                boolean priv = buf[3] == 1;
                int ch = buf[2];
                if (!priv && ch != channel) continue;

                long now = System.currentTimeMillis();
                if (speaker != 0 && now - lastPkt > 500) {
                    endRx(track, speaker);
                    speaker = 0;
                }
                if (speaker == 0) {
                    speaker = h;
                    beep(ToneGenerator.TONE_PROP_BEEP, 60);
                    if (track != null) {
                        track.play();
                        track.write(silence, 0, silence.length);   // mali jitter buffer
                    }
                    Peer who = peerByHash(h);
                    JSONObject o = new JSONObject();
                    o.put("on", true);
                    o.put("id", who != null ? who.id : "");
                    o.put("name", who != null ? who.name : p.getAddress().getHostAddress());
                    o.put("priv", priv);
                    emit("rx", o);
                }
                if (h != speaker) continue;
                lastPkt = now;
                if (track != null) track.write(buf, HEADER, len - HEADER);
            } catch (Exception e) {
                if (!running) break;
            }
        }
        try { if (track != null) track.release(); } catch (Exception ignored) { }
    }

    private void endRx(AudioTrack track, long speaker) {
        try {
            if (track != null) {
                track.stop();
                track.flush();
            }
        } catch (Exception ignored) { }
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
