package com.basa.bashtalk

import android.content.Context
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbClientSessionScope
import androidx.core.uwb.UwbComplexChannel
import androidx.core.uwb.UwbControllerSessionScope
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * UWB (Ultra Wideband) mjerenje udaljenosti i smjera do drugih telefona.
 * Parametri sesije se razmjenjuju preko postojeće veze (Nearby / Wi-Fi).
 * Manji ID je "controller", veći "controlee".
 */
class UwbLink(ctx: Context, private val myId: String, private val sink: Sink) {

    interface Sink {
        fun uwbSend(peerId: String, json: String)
        fun onRange(peerId: String, dist: Float, azimuth: Float, elevation: Float)
        fun onUwbState(state: String, msg: String)
    }

    private val app = ctx.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var mgr: UwbManager? = null
    private val sessions = ConcurrentHashMap<String, Job>()
    private val starting = ConcurrentHashMap<String, Long>()
    private val controllers = ConcurrentHashMap<String, UwbControllerSessionScope>()
    private val ctrlParams = ConcurrentHashMap<String, Pair<Int, ByteArray>>()

    @Volatile var available = false
        private set

    companion object {
        @JvmStatic
        fun hasHardware(c: Context): Boolean =
            c.packageManager.hasSystemFeature("android.hardware.uwb")

        private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun start() {
        if (!hasHardware(app)) {
            sink.onUwbState("unsupported", "Telefon nema UWB")
            return
        }
        try {
            mgr = UwbManager.createInstance(app)
            available = true
            sink.onUwbState("on", "")
        } catch (t: Throwable) {
            available = false
            sink.onUwbState("error", t.message ?: "UWB nije dostupan")
        }
    }

    fun stop() {
        sessions.values.forEach { it.cancel() }
        sessions.clear()
        controllers.clear()
        starting.clear()
        scope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    fun shutdown() {
        stop()
        scope.cancel()
    }

    fun isActive(peerId: String) = sessions.containsKey(peerId)

    /** Pokreni mjerenje s peerom (samo ako sam ja controller = manji ID). */
    fun tryStart(peerId: String) {
        val m = mgr ?: return
        if (!available || sessions.containsKey(peerId) || myId >= peerId) return
        val now = System.currentTimeMillis()
        val s = starting[peerId]
        if (s != null && now - s < 10000) return
        starting[peerId] = now
        scope.launch {
            try {
                val c = m.controllerSessionScope()
                controllers[peerId] = c
                val sid = Random.nextInt(1, Int.MAX_VALUE)
                val key = Random.nextBytes(8)
                ctrlParams[peerId] = Pair(sid, key)
                val o = JSONObject()
                o.put("t", "uwbInit")
                o.put("to", peerId)
                o.put("addr", hex(c.localAddress.address))
                o.put("uch", c.uwbComplexChannel.channel)
                o.put("pre", c.uwbComplexChannel.preambleIndex)
                o.put("sid", sid)
                o.put("key", hex(key))
                sink.uwbSend(peerId, o.toString())
            } catch (t: Throwable) {
                sink.onUwbState("error", "UWB: " + (t.message ?: "greška") + " – uključi UWB u postavkama veza")
            }
        }
    }

    /** Ja sam controlee: dobio sam parametre od controllera. */
    fun onInit(peerId: String, json: String) {
        val m = mgr ?: return
        if (!available) return
        sessions.remove(peerId)?.cancel()
        scope.launch {
            try {
                val o = JSONObject(json)
                val c = m.controleeSessionScope()
                val r = JSONObject()
                r.put("t", "uwbAddr")
                r.put("to", peerId)
                r.put("addr", hex(c.localAddress.address))
                sink.uwbSend(peerId, r.toString())
                val params = params(
                    o.getInt("sid"), unhex(o.getString("key")),
                    UwbComplexChannel(o.getInt("uch"), o.getInt("pre")),
                    unhex(o.getString("addr"))
                )
                run(peerId, c, params)
            } catch (t: Throwable) {
                sink.onUwbState("error", "UWB: " + (t.message ?: "greška"))
            }
        }
    }

    /** Ja sam controller: controlee mi je poslao svoju adresu. */
    fun onAddr(peerId: String, json: String) {
        val c = controllers.remove(peerId) ?: return
        val p = ctrlParams.remove(peerId) ?: return
        try {
            val o = JSONObject(json)
            val params = params(p.first, p.second, c.uwbComplexChannel, unhex(o.getString("addr")))
            run(peerId, c, params)
        } catch (t: Throwable) {
            sink.onUwbState("error", "UWB: " + (t.message ?: "greška"))
        }
    }

    fun stopPeer(peerId: String) {
        sessions.remove(peerId)?.cancel()
        controllers.remove(peerId)
        starting.remove(peerId)
    }

    private fun params(sid: Int, key: ByteArray, chan: UwbComplexChannel, peer: ByteArray) =
        RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_UNICAST_DS_TWR,
            sessionId = sid,
            subSessionId = 0,
            sessionKeyInfo = key,
            subSessionKeyInfo = null,
            complexChannel = chan,
            peerDevices = listOf(UwbDevice(UwbAddress(peer))),
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT
        )

    private fun run(peerId: String, s: UwbClientSessionScope, p: RangingParameters) {
        sessions.remove(peerId)?.cancel()
        sessions[peerId] = scope.launch {
            try {
                s.prepareSession(p).collect { r ->
                    when (r) {
                        is RangingResult.RangingResultPosition -> {
                            val pos = r.position
                            sink.onRange(
                                peerId,
                                pos.distance?.value ?: -1f,
                                pos.azimuth?.value ?: Float.NaN,
                                pos.elevation?.value ?: Float.NaN
                            )
                        }
                        is RangingResult.RangingResultPeerDisconnected -> {
                            sink.onRange(peerId, -1f, Float.NaN, Float.NaN)
                        }
                        else -> {}
                    }
                }
            } catch (t: Throwable) {
                // sesija pala – controller će probati ponovo
            } finally {
                sessions.remove(peerId)
                starting.remove(peerId)
            }
        }
    }
}
