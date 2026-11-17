package com.basa.bashtalk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocketFactory;

/**
 * Online veza u pozadini (i kad je aplikacija zatvorena): MQTT preko TLS-a,
 * kompatibilno s web/online stranicom (ista šifrovanja i teme).
 * Jedna stalna veza s keepalive-om – štedi bateriju kao push.
 */
public class OnlineLink {

    public interface Sink {
        void onOnlineMsg(JSONObject m, boolean dm);
        void onOnlineAudio(String id, String name, byte[] ulaw);
        void onOnlineState(boolean on);
    }

    static final String[][] BROKERS = {{"broker.emqx.io", "8883"}, {"broker.hivemq.com", "8883"}};
    static final String BASE = "bashtalk/v1/";
    static final int KEEPALIVE = 120;

    private final Context ctx;
    private final Sink sink;
    private final SharedPreferences prefs;
    private final SecureRandom rnd = new SecureRandom();

    final String myId;              // online id = "a" + lokalni id
    volatile String myName = "";
    volatile String net = "";
    private volatile String room = "", pass = "", rid = "";
    private volatile byte[] roomKey;
    private PrivateKey ecPriv;
    String ecPubB64 = "", ecPrivB64 = "";
    private final Map<String, byte[]> dmKeys = new ConcurrentHashMap<>();

    private volatile boolean running, connected;
    private volatile Socket sock;
    private volatile OutputStream out;
    private int brokerIdx;
    private long lastPresence;
    private final Map<String, Boolean> seen = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<String, Boolean>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 800; }
    });

    public OnlineLink(Context c, String localId, Sink sink) {
        this.ctx = c.getApplicationContext();
        this.sink = sink;
        this.myId = "a" + localId;
        this.prefs = ctx.getSharedPreferences("bashtalk", Context.MODE_PRIVATE);
        initKeys();
    }

    public boolean isConnected() { return connected; }

    // ------------------------------------------------------------ ključevi

    private void initKeys() {
        try {
            String pk = prefs.getString("ecPriv", null), pub = prefs.getString("ecPub", null);
            if (pk != null && pub != null) {
                ecPriv = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(Base64.decode(pk, Base64.DEFAULT)));
                ecPrivB64 = pk;
                ecPubB64 = pub;
                return;
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            ecPriv = kp.getPrivate();
            ECPublicKey p = (ECPublicKey) kp.getPublic();
            byte[] raw = new byte[65];
            raw[0] = 4;
            copy32(p.getW().getAffineX(), raw, 1);
            copy32(p.getW().getAffineY(), raw, 33);
            ecPrivB64 = Base64.encodeToString(ecPriv.getEncoded(), Base64.NO_WRAP);
            ecPubB64 = Base64.encodeToString(raw, Base64.NO_WRAP);
            prefs.edit().putString("ecPriv", ecPrivB64).putString("ecPub", ecPubB64).apply();
        } catch (Exception ignored) { }
    }

    private static void copy32(BigInteger v, byte[] dst, int off) {
        byte[] b = v.toByteArray();
        int n = Math.min(32, b.length);
        System.arraycopy(b, b.length - n, dst, off + 32 - n, n);
    }

    private byte[] dmKey(String pubB64) throws Exception {
        byte[] k = dmKeys.get(pubB64);
        if (k != null) return k;
        byte[] raw = Base64.decode(pubB64, Base64.DEFAULT);
        BigInteger x = new BigInteger(1, Arrays.copyOfRange(raw, 1, 33));
        BigInteger y = new BigInteger(1, Arrays.copyOfRange(raw, 33, 65));
        java.security.spec.ECParameterSpec params = ((java.security.interfaces.ECPrivateKey) ecPriv).getParams();
        PublicKey pub = KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), params));
        KeyAgreement ka = KeyAgreement.getInstance("ECDH");
        ka.init(ecPriv);
        ka.doPhase(pub, true);
        byte[] secret = ka.generateSecret();
        k = Arrays.copyOf(secret, 32);
        dmKeys.put(pubB64, k);
        return k;
    }

    private static String sha256hex(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** PBKDF2-HMAC-SHA256 (ručno, radi i na starijim Androidima). */
    private static byte[] pbkdf2(byte[] password, byte[] salt, int iters, int len) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(password, "HmacSHA256"));
        byte[] out = new byte[len];
        int blocks = (len + 31) / 32;
        for (int i = 1; i <= blocks; i++) {
            mac.update(salt);
            mac.update(new byte[]{(byte) (i >>> 24), (byte) (i >>> 16), (byte) (i >>> 8), (byte) i});
            byte[] u = mac.doFinal(), t = u.clone();
            for (int j = 1; j < iters; j++) {
                u = mac.doFinal(u);
                for (int k = 0; k < t.length; k++) t[k] ^= u[k];
            }
            System.arraycopy(t, 0, out, (i - 1) * 32, Math.min(32, len - (i - 1) * 32));
        }
        return out;
    }

    private byte[] seal(byte[] key, byte[] plain) throws Exception {
        byte[] iv = new byte[12];
        rnd.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] ct = c.doFinal(plain);
        byte[] r = new byte[12 + ct.length];
        System.arraycopy(iv, 0, r, 0, 12);
        System.arraycopy(ct, 0, r, 12, ct.length);
        return r;
    }

    private static byte[] open(byte[] key, byte[] data) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, data, 0, 12));
        return c.doFinal(data, 12, data.length - 12);
    }

    // ------------------------------------------------------------ soba

    /** Postavi sobu (grupa ili kanal-N); ako se promijenila – ponovo spoji. */
    public void setRoom(String r, String p) {
        r = r == null ? "" : r.toLowerCase();
        p = p == null ? "" : p;
        if (r.equals(room) && p.equals(pass) && roomKey != null == !p.isEmpty()) return;
        final String fr = r, fp = p;
        new Thread(() -> {
            try {
                rid = sha256hex("bt1:" + fr + ":" + fp).substring(0, 24);
                roomKey = fp.isEmpty() ? null : pbkdf2(fp.getBytes(StandardCharsets.UTF_8),
                        ("bashtalk:" + fr).getBytes(StandardCharsets.UTF_8), 100000, 32);
                room = fr;
                pass = fp;
                Socket s = sock;
                if (s != null) try { s.close(); } catch (Exception ignored) { }   // ponovo spoji s novim temama
            } catch (Exception ignored) { }
        }, "online-room").start();
    }

    private String T(String k) { return BASE + "r/" + rid + "/" + k; }

    // ------------------------------------------------------------ veza

    public void start() {
        if (running) return;
        running = true;
        new Thread(this::loop, "online").start();
    }

    public void stop() {
        running = false;
        try { if (connected) presence(false); } catch (Exception ignored) { }
        try { if (out != null) { out.write(new byte[]{(byte) 0xE0, 0}); out.flush(); } } catch (Exception ignored) { }
        try { if (sock != null) sock.close(); } catch (Exception ignored) { }
    }

    private boolean hasInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            NetworkCapabilities nc = n == null ? null : cm.getNetworkCapabilities(n);
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    private void loop() {
        long backoff = 3000;
        while (running) {
            try {
                if (rid.isEmpty() || !hasInternet()) { Thread.sleep(15000); continue; }
                String[] b = BROKERS[brokerIdx % BROKERS.length];
                Socket s = SSLSocketFactory.getDefault().createSocket();
                s.connect(new InetSocketAddress(b[0], Integer.parseInt(b[1])), 15000);
                s.setSoTimeout(KEEPALIVE * 1000 / 2);
                sock = s;
                out = s.getOutputStream();
                DataInputStream in = new DataInputStream(s.getInputStream());
                sendConnect();
                int t = in.readUnsignedByte();
                int len = readLen(in);
                byte[] ack = new byte[len];
                in.readFully(ack);
                if ((t >> 4) != 2 || ack.length < 2 || ack[1] != 0) throw new Exception("CONNACK " + (ack.length > 1 ? ack[1] : -1));
                subscribe(new String[]{T("m"), T("a"), T("p"), BASE + "u/" + myId});
                connected = true;
                backoff = 3000;
                sink.onOnlineState(true);
                presence(true);
                long lastPing = System.currentTimeMillis();
                while (running && !s.isClosed()) {
                    try {
                        int h = in.readUnsignedByte();
                        int l = readLen(in);
                        byte[] body = new byte[l];
                        in.readFully(body);
                        if ((h >> 4) == 3) onPublish(h, body);
                    } catch (SocketTimeoutException te) {
                        // tišina – pošalji ping
                    }
                    long now = System.currentTimeMillis();
                    if (now - lastPing > KEEPALIVE * 1000L / 2) {
                        write(new byte[]{(byte) 0xC0, 0});
                        lastPing = now;
                    }
                    if (now - lastPresence > 90000) presence(true);
                }
            } catch (Exception e) {
                brokerIdx++;
            } finally {
                if (connected) { connected = false; sink.onOnlineState(false); }
                try { if (sock != null) sock.close(); } catch (Exception ignored) { }
            }
            try { Thread.sleep(backoff); } catch (InterruptedException ignored) { }
            backoff = Math.min(60000, backoff * 2);
        }
    }

    private static int readLen(DataInputStream in) throws Exception {
        int mult = 1, v = 0, b;
        do {
            b = in.readUnsignedByte();
            v += (b & 127) * mult;
            mult *= 128;
        } while ((b & 128) != 0 && mult < 128 * 128 * 128 * 128);
        return v;
    }

    private static void writeLen(ByteArrayOutputStream o, int len) {
        do {
            int d = len % 128;
            len /= 128;
            if (len > 0) d |= 128;
            o.write(d);
        } while (len > 0);
    }

    private static void str(ByteArrayOutputStream o, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        o.write(b.length >> 8);
        o.write(b.length & 255);
        o.write(b, 0, b.length);
    }

    private synchronized void write(byte[] b) throws Exception {
        OutputStream o = out;
        if (o == null) throw new Exception("nema veze");
        o.write(b);
        o.flush();
    }

    private void packet(int type, byte[] body) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(type);
        writeLen(o, body.length);
        o.write(body, 0, body.length);
        write(o.toByteArray());
    }

    private void sendConnect() throws Exception {
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        str(v, "MQTT");
        v.write(4);                 // MQTT 3.1.1
        v.write(0x02);              // clean session
        v.write(KEEPALIVE >> 8);
        v.write(KEEPALIVE & 255);
        str(v, "btn_" + myId + "_" + Integer.toHexString(rnd.nextInt(0xffff)));
        packet(0x10, v.toByteArray());
    }

    private void subscribe(String[] topics) throws Exception {
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        v.write(0);
        v.write(1);
        for (String t : topics) { str(v, t); v.write(0); }
        packet(0x82, v.toByteArray());
    }

    private void publish(String topic, byte[] payload) throws Exception {
        ByteArrayOutputStream v = new ByteArrayOutputStream();
        str(v, topic);
        v.write(payload, 0, payload.length);
        packet(0x30, v.toByteArray());
    }

    // ------------------------------------------------------------ slanje

    private byte[] room(byte[] plain) throws Exception {
        byte[] k = roomKey;
        return k == null ? plain : seal(k, plain);
    }

    private void presence(boolean on) throws Exception {
        lastPresence = System.currentTimeMillis();
        JSONObject p = new JSONObject();
        p.put("id", myId);
        p.put("name", myName);
        p.put("on", on);
        p.put("pub", ecPubB64);
        p.put("ts", System.currentTimeMillis());
        p.put("web", false);
        p.put("net", net);
        publish(T("p"), room(p.toString().getBytes(StandardCharsets.UTF_8)));
    }

    /** Poruka u sobu (npr. most offline → online). */
    public void sendRoomMsg(JSONObject m) {
        if (!connected) return;
        new Thread(() -> {
            try {
                if (!m.has("net")) m.put("net", net);
                seen.put(m.optString("mid"), true);
                publish(T("m"), room(m.toString().getBytes(StandardCharsets.UTF_8)));
            } catch (Exception ignored) { }
        }).start();
    }

    /** Walkie glas preko interneta (µ-law 16 kHz). */
    public void sendAudio(byte[] pcm) {
        if (!connected) return;
        try {
            byte[] idb = myId.getBytes(StandardCharsets.UTF_8);
            byte[] nb = myName.getBytes(StandardCharsets.UTF_8);
            if (nb.length > 40) nb = Arrays.copyOf(nb, 40);
            byte[] u = new byte[2 + idb.length + nb.length + pcm.length];
            u[0] = (byte) idb.length;
            System.arraycopy(idb, 0, u, 1, idb.length);
            u[1 + idb.length] = (byte) nb.length;
            System.arraycopy(nb, 0, u, 2 + idb.length, nb.length);
            System.arraycopy(pcm, 0, u, 2 + idb.length + nb.length, pcm.length);
            publish(T("a"), room(u));
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ prijem

    private void onPublish(int h, byte[] body) {
        try {
            int tl = ((body[0] & 255) << 8) | (body[1] & 255);
            String topic = new String(body, 2, tl, StandardCharsets.UTF_8);
            int off = 2 + tl;
            if (((h >> 1) & 3) > 0) off += 2;          // packet id za QoS>0
            byte[] payload = Arrays.copyOfRange(body, off, body.length);

            if (topic.equals(BASE + "u/" + myId)) {
                JSONObject o = new JSONObject(new String(payload, StandardCharsets.UTF_8));
                byte[] k = dmKey(o.getString("pub"));
                JSONObject m = new JSONObject(new String(open(k, Base64.decode(o.getString("e"), Base64.DEFAULT)), StandardCharsets.UTF_8));
                m.put("dm", true);
                if (seen.put(m.optString("mid"), true) == null) sink.onOnlineMsg(m, true);
                return;
            }
            byte[] k = roomKey;
            byte[] plain = k == null ? payload : open(k, payload);
            if (topic.endsWith("/a")) {
                int il = plain[0] & 255;
                String id = new String(plain, 1, il, StandardCharsets.UTF_8);
                if (id.equals(myId)) return;
                int nl = plain[1 + il] & 255;
                String name = new String(plain, 2 + il, nl, StandardCharsets.UTF_8);
                sink.onOnlineAudio(id, name, Arrays.copyOfRange(plain, 2 + il + nl, plain.length));
            } else if (topic.endsWith("/m")) {
                JSONObject m = new JSONObject(new String(plain, StandardCharsets.UTF_8));
                if (myId.equals(m.optString("id"))) return;
                if (seen.put(m.optString("mid"), true) == null) sink.onOnlineMsg(m, false);
            }
        } catch (Exception ignored) { }
    }
}
