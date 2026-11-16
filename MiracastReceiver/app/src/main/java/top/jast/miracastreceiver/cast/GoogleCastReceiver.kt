package top.jast.miracastreceiver.cast

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import top.jast.miracastreceiver.BuildConfig
import top.jast.miracastreceiver.ui.PlayerActivity
import top.jast.miracastreceiver.ui.UrlPlaybackActivity
import top.jast.miracastreceiver.utils.NetworkUtils
import top.jast.miracastreceiver.web.RuntimeState
import kotlinx.coroutines.*
import timber.log.Timber
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * Google Cast support with two complementary entry points:
 *  - Official Google Cast Connect (GMS `CastReceiverContext`) — the [companion object] API used by
 *    [MiracastApp], [RuntimeState] and [GoogleCastLoadActivity] (`initialize`/`handleIntent`/
 *    `syncPlayback`).
 *  - LAN-only experimental Cast V2 receiver — the instance API used by `CastReceiverService`
 *    (`start`/`stop`/`updateDisplayName`). Lifecycle methods run on the main thread.
 */
class GoogleCastReceiver(
    private val context: Context,
    private var name: String,
    private val deviceId: String,
    val session: CastSession
) {
    private var scope: CoroutineScope? = null
    private var server: SSLServerSocket? = null
    private var advertiser: CastMdnsAdvertiser? = null
    private var startup: Job? = null
    private var acceptJob: Job? = null
    private var address: String? = null
    private var boundAt = 0L
    private val clients = ConcurrentHashMap<String, SSLSocket>()

    fun start() {
        if (scope != null) return
        val activeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = activeScope
        activeScope.launch {
            while (isActive) {
                val ip = NetworkUtils.getLocalIpAddress(context)?.takeUnless { it == "127.0.0.1" }
                if (ip != address || (server == null && startup?.isActive != true) ||
                    (server != null && System.currentTimeMillis() - boundAt > 24 * 60 * 60_000L)) {
                    closeTransport()
                    address = ip
                    if (ip != null) bind(activeScope, ip)
                } else if (server != null && advertiser == null) advertise()
                delay(5_000)
            }
        }
    }

    private fun bind(activeScope: CoroutineScope, ip: String) {
        startup = activeScope.launch(Dispatchers.IO) {
            var socket: SSLServerSocket? = null
            var installed = false
            try {
                val identity = CastIdentity()
                socket = identity.sslContext.serverSocketFactory.createServerSocket() as SSLServerSocket
                socket.reuseAddress = true
                socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
                socket.bind(InetSocketAddress(ip, 8009))
                val bound = socket
                withContext(Dispatchers.Main.immediate) {
                    ensureActive()
                    server = bound
                    installed = true
                    boundAt = System.currentTimeMillis()
                    advertise()
                    Timber.i("Google Cast experimental receiver listening on %s:8009", ip)
                    acceptJob = activeScope.launch(Dispatchers.IO) {
                        try {
                            while (isActive) {
                                val client = bound.accept() as SSLSocket
                                if (clients.size >= 8) { client.close(); continue }
                                val id = UUID.randomUUID().toString()
                                clients[id] = client
                                launch { serve(id, client, identity) }
                            }
                        } catch (e: Exception) {
                            if (isActive) {
                                Timber.w(e, "Google Cast listener stopped")
                                withContext(Dispatchers.Main.immediate) { closeTransport() }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) Timber.w(e, "Google Cast unavailable; retrying in 5s")
            } finally {
                if (!installed) runCatching { socket?.close() }
            }
        }
    }

    private suspend fun serve(id: String, socket: SSLSocket, identity: CastIdentity) = coroutineScope {
        var ticker: Job? = null
        try {
            Timber.i("Cast client connected: %s", socket.inetAddress.hostAddress)
            socket.soTimeout = 30_000
            socket.startHandshake()
            Timber.i("Cast TLS established: %s", socket.inetAddress.hostAddress)
            val output = socket.outputStream
            fun send(message: CastMessage) = synchronized(output) { CastWire.write(output, message) }
            ticker = launch(Dispatchers.IO) {
                try {
                    while (isActive) {
                        delay(1_000)
                        synchronized(output) { session.updates(id).forEach { send(it) } }
                    }
                } catch (e: Exception) { runCatching { socket.close() } }
            }
            while (isActive) {
                val message = CastWire.read(socket.inputStream)
                if (message.namespace == CastWire.AUTH) {
                    send(CastMessage(message.destination, message.source, CastWire.AUTH,
                        binary = identity.authenticate(requireNotNull(message.binary))))
                    Timber.i("Cast device-auth response sent")
                } else {
                    try {
                        val payload = runCatching {
                            com.google.gson.JsonParser.parseString(message.text).asJsonObject
                        }.getOrNull()
                        val type = payload?.string("type") ?: ""
                        if (type !in listOf("GET_STATUS", "PING", "PONG")) {
                            // Log protocol shape, never sender URLs, credentials or metadata.
                            Timber.i("Cast request: %s %s, mediaSessionId=%s, contentType=%s",
                                message.namespace, type, payload?.get("mediaSessionId"),
                                payload?.getAsJsonObject("media")?.string("contentType"))
                        }
                        synchronized(output) {
                            // Serialize queued lifecycle notifications before a new
                            // request/reply so an old exit cannot arrive after relaunch.
                            session.drainNotifications(id).forEach { send(it) }
                            session.handle(id, message).forEach {
                                val response = runCatching { com.google.gson.JsonParser.parseString(it.text).asJsonObject }.getOrNull()
                                if (response?.string("type") in listOf("INVALID_REQUEST", "LOAD_FAILED", "LAUNCH_ERROR")) {
                                    Timber.w("Cast request rejected: %s (%s)", response?.string("type"), response?.string("reason"))
                                }
                                send(it)
                            }
                        }
                    } catch (e: IllegalArgumentException) {
                        Timber.d("Invalid Cast request: %s", e.message)
                        val requestId = runCatching {
                            com.google.gson.JsonParser.parseString(message.text).asJsonObject.get("requestId")
                        }.getOrNull()
                        val error = obj("type" to "INVALID_REQUEST", "reason" to "INVALID_PARAMETER")
                        requestId?.let { error.add("requestId", it) }
                        send(CastMessage(message.destination, message.source, message.namespace, error.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            Timber.i("Cast connection closed: %s (%s)", e.javaClass.simpleName, e.message)
        } finally {
            ticker?.cancel()
            runCatching { socket.close() }
            clients.remove(id)
            session.disconnect(id)
        }
    }

    private fun advertise() {
        if (advertiser != null) return
        val ip = address ?: return
        lateinit var registration: CastMdnsAdvertiser
        registration = CastMdnsAdvertiser(context, deviceId, name, ip) {
            scope?.launch {
                if (advertiser === registration) { registration.stop(); advertiser = null }
            }
        }
        advertiser = registration
        registration.start()
    }

    fun updateDisplayName(value: String) {
        name = value
        advertiser?.stop()
        advertiser = null
        if (server != null) advertise()
    }

    private fun closeTransport() {
        startup?.cancel(); startup = null
        advertiser?.stop(); advertiser = null
        runCatching { server?.close() }; server = null
        clients.values.forEach { runCatching { it.close() } }
        acceptJob?.cancel(); acceptJob = null
        clients.clear()
        session.reset()
    }

    fun stop() {
        closeTransport()
        scope?.cancel(); scope = null
        address = null
    }

    /** Official Google Cast Connect (GMS) integration — kept from the fork's deep customization. */
    companion object {
        @Volatile
        var initialized: Boolean = false
            private set

        val configured: Boolean
            get() = BuildConfig.GOOGLE_CAST_APP_ID.isNotBlank()

        private var application: Application? = null
        private var mediaSession: MediaSessionCompat? = null

        fun initialize(app: Application) {
            if (!configured) {
                Timber.i("Google Cast Connect disabled: GOOGLE_CAST_APP_ID is not configured")
                return
            }
            if (initialized) return

            runCatching {
                application = app
                CastReceiverContext.initInstance(app)
                val castContext = CastReceiverContext.getInstance()

                val session = MediaSessionCompat(app, "MiracastReceiver-GoogleCast").apply {
                    setFlags(
                        MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                            MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
                    )
                    setCallback(object : MediaSessionCompat.Callback() {
                        override fun onPlay() = sendControl(PlayerActivity.ACTION_PLAY)
                        override fun onPause() = sendControl(PlayerActivity.ACTION_PAUSE)
                        override fun onStop() = sendControl(PlayerActivity.ACTION_STOP)
                        override fun onSeekTo(pos: Long) {
                            sendControl(
                                PlayerActivity.ACTION_SEEK,
                                Bundle().apply { putLong(PlayerActivity.EXTRA_SEEK_POSITION, pos.coerceAtLeast(0L)) }
                            )
                        }
                    })
                    isActive = true
                }
                mediaSession = session
                castContext.mediaManager.setSessionCompatToken(session.sessionToken)
                castContext.mediaManager.setMediaLoadCommandCallback(CastLoadCallback())

                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        runCatching { CastReceiverContext.getInstance().start() }
                            .onFailure { Timber.w(it, "Unable to start CastReceiverContext") }
                    }

                    override fun onStop(owner: LifecycleOwner) {
                        runCatching { CastReceiverContext.getInstance().stop() }
                            .onFailure { Timber.w(it, "Unable to stop CastReceiverContext") }
                    }
                })

                initialized = true
                syncPlayback(RuntimeState.playbackSnapshot())
                Timber.i("Google Cast Connect initialized appId=${BuildConfig.GOOGLE_CAST_APP_ID}")
            }.onFailure {
                initialized = false
                Timber.w(it, "Google Cast Connect unavailable on this device")
            }
        }

        fun handleIntent(intent: Intent?): Boolean {
            if (!initialized || intent == null) return false
            return runCatching {
                CastReceiverContext.getInstance().mediaManager.onNewIntent(intent)
            }.onFailure {
                Timber.w(it, "Google Cast intent handling failed action=${intent.action}")
            }.getOrDefault(false)
        }

        fun syncPlayback(snapshot: RuntimeState.PlaybackSnapshot) {
            val session = mediaSession ?: return
            val state = when (snapshot.state.uppercase()) {
                "PLAYING", "READY" -> PlaybackStateCompat.STATE_PLAYING
                "PAUSED" -> PlaybackStateCompat.STATE_PAUSED
                "BUFFERING", "RETRYING" -> PlaybackStateCompat.STATE_BUFFERING
                "ENDED" -> PlaybackStateCompat.STATE_STOPPED
                "ERROR" -> PlaybackStateCompat.STATE_ERROR
                else -> PlaybackStateCompat.STATE_NONE
            }

            val actions = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SEEK_TO

            session.setPlaybackState(
                PlaybackStateCompat.Builder()
                    .setActions(actions)
                    .setState(
                        state,
                        snapshot.positionMs.coerceAtLeast(0L),
                        snapshot.speed,
                        android.os.SystemClock.elapsedRealtime()
                    )
                    .build()
            )

            session.setMetadata(
                MediaMetadataCompat.Builder()
                    .putString(MediaMetadataCompat.METADATA_KEY_TITLE, snapshot.title.ifBlank { "Google Cast" })
                    .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_URI, snapshot.uri)
                    .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, snapshot.durationMs.coerceAtLeast(0L))
                    .build()
            )

            if (initialized) {
                runCatching { CastReceiverContext.getInstance().mediaManager.broadcastMediaStatus() }
            }
        }

        private fun sendControl(action: String, extras: Bundle? = null) {
            val app = application ?: return
            val intent = Intent(action).apply {
                setPackage(app.packageName)
                extras?.let { putExtras(it) }
            }
            app.sendBroadcast(intent)
        }

        private class CastLoadCallback : MediaLoadCommandCallback() {
            override fun onLoad(
                senderId: String?,
                loadRequestData: MediaLoadRequestData
            ): Task<MediaLoadRequestData> {
                val app = application
                    ?: return Tasks.forException(IllegalStateException("Application is not initialized"))
                val mediaInfo = loadRequestData.mediaInfo
                val candidate = mediaInfo?.contentUrl?.takeIf { it.isNotBlank() }
                    ?: mediaInfo?.contentId?.takeIf { it.isNotBlank() }
                    ?: return Tasks.forException(IllegalArgumentException("Cast load request has no content URL"))

                val uri = runCatching { Uri.parse(candidate) }.getOrNull()
                if (uri == null || (uri.scheme != "http" && uri.scheme != "https")) {
                    return Tasks.forException(IllegalArgumentException("Unsupported Cast content URL: $candidate"))
                }

                return runCatching {
                    val manager = CastReceiverContext.getInstance().mediaManager
                    manager.setDataFromLoad(loadRequestData)

                    app.startActivity(
                        Intent(app, UrlPlaybackActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            putExtra(UrlPlaybackActivity.EXTRA_URL, candidate)
                            putExtra(UrlPlaybackActivity.EXTRA_TITLE, "Google Cast")
                        }
                    )

                    RuntimeState.updatePlayback {
                        it.copy(
                            state = "BUFFERING",
                            title = "Google Cast",
                            uri = candidate,
                            source = "GOOGLE_CAST",
                            error = "",
                            retryAttempt = 0
                        )
                    }
                    manager.broadcastMediaStatus()
                    Timber.i("Google Cast LOAD sender=$senderId url=$candidate")
                    Tasks.forResult(loadRequestData)
                }.getOrElse { error ->
                    Timber.w(error, "Google Cast LOAD failed")
                    val exception = error as? Exception ?: RuntimeException(error)
                    Tasks.forException(exception)
                }
            }
        }
    }
}
