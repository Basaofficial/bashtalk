package com.basa.bashtalk;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mali HTTP server (port 8080) za iPhone i druge uređaje bez aplikacije:
 * otvore http://IP:8080 u pregledniku i dobiju chat, slike i slušanje glasa.
 */
public class WebServer implements Engine.WebSink {

    static final int PORT = 8080;

    private final Context ctx;
    private final Engine engine;
    private ServerSocket ss;
    private volatile boolean on;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    private final Map<String, String> names = new ConcurrentHashMap<>();
    private final Map<String, Long> seen = new ConcurrentHashMap<>();

    private final List<JSONObject> events = new ArrayList<>();
    private long evSeq;
    private JSONObject lastPeers;

    private final List<byte[]> frames = new ArrayList<>();
    private long frSeq;

    public WebServer(Context ctx, Engine engine) {
        this.ctx = ctx.getApplicationContext();
        this.engine = engine;
    }

    public boolean isOn() { return on; }

    public void start() {
        if (on) return;
        on = true;
        new Thread(() -> {
            try {
                ss = new ServerSocket(PORT);
                while (on) {
                    final Socket s = ss.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (Exception ignored) {
            } finally {
                on = false;
            }
        }, "web").start();
        engine.web = this;
    }

    public void stop() {
        on = false;
        if (engine.web == this) engine.web = null;
        try { if (ss != null) ss.close(); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ WebSink

    @Override
    public void webEvent(String type, JSONObject data) {
        try {
            JSONObject e = new JSONObject();
            e.put("type", type);
            e.put("d", fixUrls(data));
            synchronized (events) {
                if ("peers".equals(type)) { lastPeers = e; return; }
                e.put("seq", ++evSeq);
                events.add(e);
                while (events.size() > 300) events.remove(0);
                events.notifyAll();
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void webAudio(byte[] b, int off, int len) {
        if (webClients().isEmpty()) return;
        byte[] f = new byte[len];
        System.arraycopy(b, off, f, 0, len);
        synchronized (frames) {
            frames.add(f);
            frSeq++;
            while (frames.size() > 150) frames.remove(0);
            frames.notifyAll();
        }
    }

    @Override
    public Map<String, String> webClients() {
        Map<String, String> m = new HashMap<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Long> e : seen.entrySet()) {
            if (now - e.getValue() < 20000) m.put(e.getKey(), names.get(e.getKey()));
        }
        return m;
    }

    private JSONObject fixUrls(JSONObject d) throws Exception {
        if (d == null || !d.has("file")) return d;
        JSONObject c = new JSONObject(d.toString());
        c.put("url", "/media/" + d.optString("file"));
        return c;
    }

    // ------------------------------------------------------------ HTTP

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            String line = readLine(in);
            if (line == null) { s.close(); return; }
            String[] parts = line.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";
            int clen = 0;
            String h;
            while ((h = readLine(in)) != null && !h.isEmpty()) {
                int c = h.indexOf(':');
                if (c > 0 && h.substring(0, c).trim().equalsIgnoreCase("content-length")) {
                    clen = Integer.parseInt(h.substring(c + 1).trim());
                }
            }
            byte[] body = new byte[Math.min(clen, 8 * 1024 * 1024)];
            int got = 0;
            while (got < body.length) {
                int n = in.read(body, got, body.length - got);
                if (n < 0) break;
                got += n;
            }
            String path = target;
            Map<String, String> q = new HashMap<>();
            int qi = target.indexOf('?');
            if (qi >= 0) {
                path = target.substring(0, qi);
                for (String kv : target.substring(qi + 1).split("&")) {
                    int e = kv.indexOf('=');
                    if (e > 0) q.put(kv.substring(0, e), URLDecoder.decode(kv.substring(e + 1), "UTF-8"));
                }
            }
            route(s.getOutputStream(), method, path, q, body);
        } catch (Exception ignored) {
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') b.write(c);
            if (b.size() > 8192) break;
        }
        if (c < 0 && b.size() == 0) return null;
        return b.toString("UTF-8");
    }

    private void touch(String id, String name) {
        if (id == null || id.isEmpty()) return;
        if (name != null && !name.isEmpty()) names.put(id, name.length() > 24 ? name.substring(0, 24) : name);
        names.putIfAbsent(id, "iPhone");
        seen.put(id, System.currentTimeMillis());
    }

    private void route(OutputStream out, String method, String path, Map<String, String> q, byte[] body) throws Exception {
        switch (path) {
            case "/":
            case "/index.html": {
                InputStream a = ctx.getAssets().open("web.html");
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = a.read(buf)) > 0) b.write(buf, 0, n);
                a.close();
                send(out, 200, "text/html; charset=utf-8", b.toByteArray(), null);
                return;
            }
            case "/api/poll": {
                touch(q.get("id"), q.get("name"));
                long since = parseLong(q.get("since"), -1);
                JSONObject r = new JSONObject();
                JSONArray arr = new JSONArray();
                synchronized (events) {
                    if (since < 0) {
                        since = evSeq;
                    } else {
                        long until = System.currentTimeMillis() + 12000;
                        while (evSeq <= since && System.currentTimeMillis() < until) events.wait(1000);
                    }
                    for (JSONObject e : events) if (e.optLong("seq") > since) arr.put(e);
                    r.put("next", evSeq);
                    if (lastPeers != null) r.put("peers", lastPeers.optJSONObject("d"));
                }
                r.put("events", arr);
                r.put("ch", engine.channel);
                r.put("host", engine.name);
                r.put("sos", new JSONArray(engine.sosMap.values()));
                send(out, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8), null);
                return;
            }
            case "/api/audio": {
                touch(q.get("id"), null);
                long since = parseLong(q.get("since"), -1);
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                long next;
                synchronized (frames) {
                    if (since >= 0) {
                        long until = System.currentTimeMillis() + 1000;
                        while (frSeq <= since && System.currentTimeMillis() < until) frames.wait(200);
                        long first = frSeq - frames.size() + 1;
                        for (int i = 0; i < frames.size(); i++) {
                            if (first + i > since) b.write(frames.get(i));
                        }
                    }
                    next = frSeq;
                }
                Map<String, String> hx = new HashMap<>();
                hx.put("X-Next", String.valueOf(next));
                send(out, 200, "application/octet-stream", b.toByteArray(), hx);
                return;
            }
            case "/api/send": {
                JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String id = o.optString("id");
                touch(id, o.optString("name"));
                String text = o.optString("text").trim();
                if (!text.isEmpty()) {
                    String mid = engine.sendChatAs(id, names.get(id), text.length() > 1000 ? text.substring(0, 1000) : text, o.optString("to", ""));
                    send(out, 200, "application/json", ("{\"mid\":\"" + mid + "\"}").getBytes(StandardCharsets.UTF_8), null);
                } else {
                    send(out, 400, "text/plain", new byte[0], null);
                }
                return;
            }
            case "/api/img": {
                JSONObject o = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String id = o.optString("id");
                touch(id, o.optString("name"));
                String data = o.optString("data");
                int c = data.indexOf(',');
                byte[] jpg = Base64.decode(c >= 0 ? data.substring(c + 1) : data, Base64.DEFAULT);
                JSONObject meta = new JSONObject();
                meta.put("w", o.optInt("w"));
                meta.put("h", o.optInt("h"));
                JSONObject r = engine.sendBlob("img", jpg, meta, "", id, names.get(id));
                r.put("url", "/media/" + r.optString("file"));
                send(out, 200, "application/json", r.toString().getBytes(StandardCharsets.UTF_8), null);
                return;
            }
        }
        if (path.startsWith("/media/")) {
            String fn = path.substring(7).replaceAll("[^A-Za-z0-9._-]", "");
            File f = new File(Media.dir(ctx, "media"), fn);
            if (!f.exists()) { send(out, 404, "text/plain", new byte[0], null); return; }
            byte[] b = new byte[(int) f.length()];
            try (FileInputStream fi = new FileInputStream(f)) {
                int got = 0;
                while (got < b.length) {
                    int n = fi.read(b, got, b.length - got);
                    if (n < 0) break;
                    got += n;
                }
            }
            send(out, 200, fn.endsWith(".wav") ? "audio/wav" : "image/jpeg", b, null);
            return;
        }
        send(out, 404, "text/plain", "404".getBytes(StandardCharsets.UTF_8), null);
    }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(s); } catch (Exception e) { return def; }
    }

    private static void send(OutputStream out, int code, String type, byte[] body, Map<String, String> extra) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " ERR").append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Access-Control-Expose-Headers: X-Next\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet())
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
