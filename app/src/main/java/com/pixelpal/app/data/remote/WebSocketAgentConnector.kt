package com.pixelpal.app.data.remote

import com.pixelpal.app.domain.model.AgentCheckResult
import com.pixelpal.app.domain.model.AgentState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Streaming WebSocket connector for live agent updates.
 *
 * ## Ownership model (this replaced a single mutable slot — a real defect)
 *
 * The old design kept one `activeWebSocket` field. Two problems followed:
 *  - `checkOnce()` closed it in a `finally` block, so a one-shot poll **killed
 *    a concurrent live stream**;
 *  - two agents (or two URLs) could not stream at once — the second overwrote
 *    the first, and `sendMessage` went to whichever won.
 *
 * Now every stream owns its own socket keyed by URL, with a reader refcount.
 * The socket closes only when the LAST reader of that URL goes away, and
 * `checkOnce` closes strictly its own connection. This is what makes
 * multi-agent streaming safe.
 */
@Singleton
class WebSocketAgentConnector @Inject constructor(
    sharedClient: OkHttpClient
) {

    private val client: OkHttpClient = sharedClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived sockets
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /** One live socket per URL, plus how many collectors are reading it. */
    private class Connection {
        @Volatile var socket: WebSocket? = null
        val readers = AtomicInteger(0)
    }

    private val connections = ConcurrentHashMap<String, Connection>()

    /**
     * Connects to [url] and streams updates as a Flow.
     *
     * Concurrent collectors of the same URL share one socket (that is the
     * point — never open duplicate connections to the same agent). The socket
     * is closed when the last collector cancels.
     */
    fun connectAndStream(url: String): Flow<AgentCheckResult> = callbackFlow {
        val request = Request.Builder().url(url).build()
        val connection = connections.computeIfAbsent(url) { Connection() }
        connection.readers.incrementAndGet()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Timber.d("WebSocket connected: $url")
                trySend(AgentCheckResult(AgentState.ONLINE, "Connected"))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val envelope = json.decodeFromString<WsAgentMessage>(text)
                    trySend(
                        AgentCheckResult(
                            state = AgentState.fromId(envelope.status),
                            message = envelope.message,
                            currentTask = envelope.currentTask,
                            progress = envelope.progress
                        )
                    )
                } catch (e: Exception) {
                    Timber.d(e, "Unrecognized WebSocket message: $text")
                    trySend(AgentCheckResult(AgentState.ONLINE, text))
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("WebSocket closing: $code / $reason")
                trySend(AgentCheckResult(AgentState.DISCONNECTED, reason))
                webSocket.close(1000, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "WebSocket error: $url")
                trySend(AgentCheckResult(AgentState.ERROR, t.localizedMessage ?: "WebSocket error"))
            }
        }

        val ws = client.newWebSocket(request, listener)
        connection.socket = ws

        awaitClose {
            ws.close(1000, "Client closed connection")
            // Only drop the slot when we were the last user of this URL.
            if (connection.readers.decrementAndGet() <= 0) {
                connections.remove(url, connection)
                connection.socket = null
            }
        }
    }

    /**
     * Sends a message to [url]'s live socket, if one exists.
     * Routing by URL is required now that several agents can stream at once.
     */
    fun sendMessage(message: String, url: String? = null): Boolean {
        val target = if (url != null) {
            connections[url]?.socket
        } else {
            connections.values.firstOrNull { it.socket != null }?.socket
        }
        return target?.send(message) ?: false
    }

    /** True while any socket opened by [connectAndStream] is alive. */
    val isConnected: Boolean get() = connections.values.any { it.socket != null }

    /**
     * Closes the socket(s). With [url] only that stream is closed (the safe
     * call for one-shot polls); without it everything is closed (sign-out or
     * provider switch).
     */
    fun disconnect(
        url: String? = null,
        code: Int = 1000,
        reason: String = "Client closed connection"
    ) {
        val targets = if (url != null) {
            listOfNotNull(connections[url])
        } else {
            connections.values.toList()
        }
        for (connection in targets) {
            try {
                connection.socket?.close(code, reason)
            } catch (_: Exception) {
                // closing a dead socket is not an error
            }
            connection.socket = null
            connection.readers.set(0)
        }
        if (url != null) connections.remove(url) else connections.clear()
    }

    /**
     * One-shot poll for ws:// / wss:// endpoints so periodic checks and
     * "Check now" work without holding a Flow open.
     *
     * Owns a PRIVATE connection: it never closes a stream another collector is
     * reading (regression guard for the old single-slot defect).
     */
    suspend fun checkOnce(url: String, timeoutMs: Long = 12_000L): AgentCheckResult {
        if (!GenericHttpAgentConnector.isWebSocketUrl(url)) {
            return AgentCheckResult(AgentState.ERROR, "WebSocket URL must start with ws:// or wss://")
        }
        val hadLiveReader = connections[url]?.let { it.readers.get() > 0 } == true
        return try {
            val live = withTimeoutOrNull(timeoutMs) {
                connectAndStream(url).first { r ->
                    !(r.state == AgentState.ONLINE && r.message == "Connected")
                }
            }
            live ?: AgentCheckResult(AgentState.ONLINE, "Connected (no status yet)")
        } catch (e: Exception) {
            Timber.e(e, "WebSocket check failed")
            AgentCheckResult(AgentState.ERROR, e.localizedMessage ?: "WebSocket error")
        } finally {
            // Only tear down if we were the sole user of this URL.
            if (!hadLiveReader) disconnect(url)
        }
    }

    @Serializable
    private data class WsAgentMessage(
        val status: String = "",
        val message: String? = null,
        val currentTask: String? = null,
        val progress: Int? = null
    )
}
