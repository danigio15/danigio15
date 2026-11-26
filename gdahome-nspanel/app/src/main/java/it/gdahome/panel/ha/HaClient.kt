package it.gdahome.panel.ha

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Client WebSocket per Home Assistant (API ufficiale /api/websocket).
 * Riceve gli stati in tempo reale e permette di chiamare i servizi.
 */
class HaClient(
    private val baseUrl: String,
    private val token: String,
    private val onStates: (Map<String, HaEntity>) -> Unit,
    private val onConnection: (connected: Boolean, message: String) -> Unit,
) {
    private val tag = "GDA/HA"
    private val http = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private val ids = AtomicInteger(10)
    private val states = ConcurrentHashMap<String, HaEntity>()
    @Volatile private var ws: WebSocket? = null
    @Volatile private var authenticated = false
    @Volatile private var stopped = false
    private var retryDelay = 2L

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true
        ws?.close(1000, "bye")
        ws = null
        scheduler.shutdownNow()
        http.dispatcher.executorService.shutdown()
    }

    private fun wsUrl(): String {
        val u = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        return "$u/api/websocket"
    }

    private fun connect() {
        if (stopped) return
        authenticated = false
        val req = try { Request.Builder().url(wsUrl()).build() } catch (e: Exception) {
            onConnection(false, "URL non valido: $baseUrl"); return
        }
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handle(webSocket, text)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(tag, "WS errore: ${t.message}")
                onConnection(false, t.message ?: "errore")
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onConnection(false, "chiuso")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (stopped) return
        val d = retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(60)
        try { scheduler.schedule({ connect() }, d, TimeUnit.SECONDS) } catch (_: Exception) {}
    }

    private fun handle(socket: WebSocket, text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        when (msg.optString("type")) {
            "auth_required" -> socket.send(JSONObject().put("type", "auth").put("access_token", token).toString())
            "auth_invalid" -> { onConnection(false, "Token non valido"); stopped = true; socket.close(1000, "auth") }
            "auth_ok" -> {
                authenticated = true
                retryDelay = 2
                onConnection(true, "connesso")
                socket.send(JSONObject().put("id", 1).put("type", "get_states").toString())
                socket.send(JSONObject().put("id", 2).put("type", "subscribe_events").put("event_type", "state_changed").toString())
            }
            "result" -> if (msg.optInt("id") == 1 && msg.optBoolean("success")) {
                val arr = msg.optJSONArray("result") ?: JSONArray()
                for (i in 0 until arr.length()) parseState(arr.getJSONObject(i))?.let { states[it.entityId] = it }
                onStates(HashMap(states))
            }
            "event" -> {
                val data = msg.optJSONObject("event")?.optJSONObject("data") ?: return
                val id = data.optString("entity_id")
                val ns = data.optJSONObject("new_state")
                if (ns == null) states.remove(id) else parseState(ns)?.let { states[id] = it }
                onStates(HashMap(states))
            }
        }
    }

    private fun parseState(o: JSONObject): HaEntity? {
        val id = o.optString("entity_id").ifEmpty { return null }
        return HaEntity(id, o.optString("state"), o.optJSONObject("attributes") ?: JSONObject())
    }

    fun callService(domain: String, service: String, entityId: String, data: JSONObject = JSONObject()): Boolean {
        val socket = ws ?: return false
        if (!authenticated) return false
        val msg = JSONObject()
            .put("id", ids.incrementAndGet())
            .put("type", "call_service")
            .put("domain", domain)
            .put("service", service)
            .put("service_data", data)
            .put("target", JSONObject().put("entity_id", entityId))
        return socket.send(msg.toString())
    }

    /** Azione "tap" di default per una entità. */
    fun tap(e: HaEntity): Boolean = when {
        e.domain == "scene" -> callService("scene", "turn_on", e.entityId)
        e.domain == "script" -> callService("script", "turn_on", e.entityId)
        e.domain == "button" || e.domain == "input_button" -> callService(e.domain, "press", e.entityId)
        e.domain == "lock" -> callService("lock", if (e.state == "locked") "unlock" else "lock", e.entityId)
        e.domain == "cover" -> callService("cover", "toggle", e.entityId)
        e.domain == "media_player" -> callService("media_player", "media_play_pause", e.entityId)
        e.toggleable -> callService(e.domain, "toggle", e.entityId)
        else -> false
    }
}
