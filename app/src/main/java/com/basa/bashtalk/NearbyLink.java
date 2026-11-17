package com.basa.bashtalk;

import android.content.Context;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Automatsko povezivanje s uređajima u blizini (Google Nearby Connections):
 * Bluetooth, BLE i Wi-Fi, bez hotspota i bez interneta.
 * Svaki uređaj istovremeno oglašava i traži, pa nastaje mreža svi-sa-svima.
 */
public class NearbyLink {

    public interface Sink {
        void onBytes(String ep, byte[] b);
        void onConnected(String ep, String peerId);
        void onDisconnected(String ep);
        void onState(String state, String msg);
    }

    static final String SERVICE_ID = "com.basa.bashtalk.v2";
    static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    private final ConnectionsClient cc;
    private final String myId;
    private final Sink sink;
    private volatile String endpointName;
    private volatile boolean on, lowPower, discovering;
    private long burstAt;

    private final ConcurrentHashMap<String, String> epToId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> foundAt = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> requested = new ConcurrentHashMap<>();
    private final Set<String> connected = ConcurrentHashMap.newKeySet();

    public NearbyLink(Context ctx, String myId, Sink sink) {
        this.cc = Nearby.getConnectionsClient(ctx.getApplicationContext());
        this.myId = myId;
        this.sink = sink;
    }

    private static String idOf(String endpointName) {
        if (endpointName == null) return "";
        int i = endpointName.indexOf('|');
        return i > 0 ? endpointName.substring(0, i) : endpointName;
    }

    public boolean isOn() { return on; }

    public boolean isConnected(String ep) { return ep != null && connected.contains(ep); }

    public int connectedCount() { return connected.size(); }

    public void start(String name) {
        stop();
        on = true;
        String n = name == null ? "" : name;
        if (n.length() > 20) n = n.substring(0, 20);
        endpointName = myId + "|" + n;

        cc.startAdvertising(endpointName, SERVICE_ID, lifecycle,
                        new AdvertisingOptions.Builder().setStrategy(STRATEGY).build())
                .addOnSuccessListener(v -> sink.onState("on", ""))
                .addOnFailureListener(e -> sink.onState("error", "Oglašavanje: " + e.getMessage()));

        startDisc();
    }

    private void startDisc() {
        if (discovering) return;
        discovering = true;
        cc.startDiscovery(SERVICE_ID, discovery,
                        new DiscoveryOptions.Builder().setStrategy(STRATEGY).build())
                .addOnFailureListener(e -> { discovering = false; sink.onState("error", "Traženje: " + e.getMessage()); });
    }

    private void stopDisc() {
        if (!discovering) return;
        discovering = false;
        try { cc.stopDiscovery(); } catch (Exception ignored) { }
    }

    /** Štednja baterije: kad je ekran ugašen, traži nove uređaje samo povremeno (20 s svake 2 min). */
    public void setLowPower(boolean lp) {
        lowPower = lp;
        if (!on) return;
        if (lp) { stopDisc(); burstAt = System.currentTimeMillis(); } else startDisc();
    }

    public void stop() {
        if (!on) return;
        on = false;
        try { cc.stopAdvertising(); } catch (Exception ignored) { }
        stopDisc();
        try { cc.stopAllEndpoints(); } catch (Exception ignored) { }
        for (String ep : connected) sink.onDisconnected(ep);
        connected.clear();
        epToId.clear();
        foundAt.clear();
        requested.clear();
        sink.onState("off", "");
    }

    /** Poziva se svakih ~0.5 s: spaja se s pronađenim uređajima. */
    public void tick() {
        if (!on) return;
        long now = System.currentTimeMillis();
        if (lowPower) {
            long t = now - burstAt;
            if (!discovering && t > 120000) { burstAt = now; startDisc(); }
            else if (discovering && t > 20000) stopDisc();
        }
        for (String ep : foundAt.keySet()) {
            if (connected.contains(ep)) continue;
            Long r = requested.get(ep);
            if (r != null && now - r < 12000) continue;
            String other = epToId.get(ep);
            if (other == null) continue;
            long seen = foundAt.get(ep) == null ? now : foundAt.get(ep);
            // manji ID šalje zahtjev odmah, drugi tek ako se ništa ne desi (izbjegava sudar)
            boolean mine = myId.compareTo(other) < 0;
            if (mine || now - seen > 5000) request(ep);
        }
    }

    private void request(String ep) {
        requested.put(ep, System.currentTimeMillis());
        cc.requestConnection(endpointName, ep, lifecycle)
                .addOnFailureListener(e -> {
                    // ako je već spojen ili zahtjev u toku – ništa; inače probaj kasnije
                    requested.put(ep, System.currentTimeMillis() - 9000);
                });
    }

    public void send(String ep, byte[] b) {
        if (!connected.contains(ep)) return;
        try { cc.sendPayload(ep, Payload.fromBytes(b)); } catch (Exception ignored) { }
    }

    public void sendAll(byte[] b) {
        if (connected.isEmpty()) return;
        try { cc.sendPayload(new ArrayList<>(connected), Payload.fromBytes(b)); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------ callbacks

    private final EndpointDiscoveryCallback discovery = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String ep, DiscoveredEndpointInfo info) {
            if (!SERVICE_ID.equals(info.getServiceId())) return;
            String id = idOf(info.getEndpointName());
            if (id.isEmpty() || id.equals(myId)) return;
            epToId.put(ep, id);
            foundAt.put(ep, System.currentTimeMillis());
        }

        @Override
        public void onEndpointLost(String ep) {
            foundAt.remove(ep);
            requested.remove(ep);
        }
    };

    private final ConnectionLifecycleCallback lifecycle = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String ep, ConnectionInfo info) {
            String id = idOf(info.getEndpointName());
            if (id.equals(myId)) { cc.rejectConnection(ep); return; }
            epToId.put(ep, id);
            cc.acceptConnection(ep, payloads);
        }

        @Override
        public void onConnectionResult(String ep, ConnectionResolution res) {
            if (res.getStatus().isSuccess()) {
                connected.add(ep);
                requested.remove(ep);
                sink.onConnected(ep, epToId.get(ep));
            } else {
                requested.put(ep, System.currentTimeMillis() - 9000);
            }
        }

        @Override
        public void onDisconnected(String ep) {
            connected.remove(ep);
            requested.remove(ep);
            sink.onDisconnected(ep);
        }
    };

    private final PayloadCallback payloads = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String ep, Payload p) {
            if (p.getType() != Payload.Type.BYTES) return;
            byte[] b = p.asBytes();
            if (b != null) sink.onBytes(ep, b);
        }

        @Override
        public void onPayloadTransferUpdate(String ep, PayloadTransferUpdate u) { }
    };
}
