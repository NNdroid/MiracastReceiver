package com.weekd.miracastreceiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Display
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.airplay.AirPlayReceiver
import com.weekd.miracastreceiver.airplay.AirPlayState
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.discovery.MdnsAdvertiser
import com.weekd.miracastreceiver.dlna.DlnaMediaRenderer
import com.weekd.miracastreceiver.dlna.SsdpServer
import com.weekd.miracastreceiver.dlna.UpnpHttpServer
import com.weekd.miracastreceiver.miracast.WfdRootHelper
import com.weekd.miracastreceiver.miracast.WfdServer
import com.weekd.miracastreceiver.miracast.WifiDirectManager
import com.weekd.miracastreceiver.ui.PlayerActivity
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.NetworkUtils
import com.weekd.miracastreceiver.web.RuntimeState
import com.weekd.miracastreceiver.web.WebUiServer
import timber.log.Timber

/** Always-on Android TV casting receiver core. */
class CastReceiverService : Service() {

    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"

        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cast_receiver_service"
        private const val CHANNEL_NAME = "投屏接收服务"
        private const val ACTION_UPDATE_POSITION = "com.weekd.miracastreceiver.ACTION_UPDATE_POSITION"
        private const val ACTION_PLAYBACK_STOPPED = "com.weekd.miracastreceiver.ACTION_PLAYBACK_STOPPED"

        private const val NETWORK_RETRY_INTERVAL_MS = 5_000L
        private const val NETWORK_MAX_WAIT_MS = 5 * 60_000L
        private const val RECONFIGURE_DELAY_MS = 350L
    }

    private lateinit var airPlayReceiver: AirPlayReceiver
    private lateinit var customMdnsAdvertiser: MdnsAdvertiser
    private lateinit var dlnaRenderer: DlnaMediaRenderer
    private lateinit var ssdpServer: SsdpServer
    private lateinit var upnpHttpServer: UpnpHttpServer
    private lateinit var wfdServer: WfdServer
    private lateinit var wifiDirectManager: WifiDirectManager
    private var webUiServer: WebUiServer? = null

    private lateinit var deviceUuid: String
    private lateinit var connectionCode: String
    private var airPlayPlayerStarted = false

    private var initialized = false
    private var servicesStarted = false
    private var playerReceiverRegistered = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkWaitRunnable: Runnable? = null
    private var reconfigureRunnable: Runnable? = null
    private var networkWaitElapsedMs = 0L
    private var lastKnownIp: String? = null

    private val activePlaybackSources = linkedSetOf<String>()
    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkStateCheck()
        override fun onLost(network: Network) = scheduleNetworkStateCheck()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            scheduleNetworkStateCheck()
    }

    private val playerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_UPDATE_POSITION -> {
                    val position = intent.getLongExtra("position", 0L)
                    val duration = intent.getLongExtra("duration", 0L)
                    val isPlaying = intent.getBooleanExtra("is_playing", false)
                    val title = intent.getStringExtra("title").orEmpty()
                    val uri = intent.getStringExtra("uri").orEmpty()
                    val source = intent.getStringExtra("source").orEmpty()

                    if (::dlnaRenderer.isInitialized) {
                        dlnaRenderer.updatePosition(position, duration)
                        if (isPlaying) dlnaRenderer.setPlaying() else dlnaRenderer.setPaused()
                    }
                    RuntimeState.playbackPositionMs = position
                    RuntimeState.playbackDurationMs = duration
                    RuntimeState.playbackState = if (isPlaying) "PLAYING" else "PAUSED"
                    if (title.isNotBlank()) RuntimeState.playbackTitle = title
                    if (uri.isNotBlank()) RuntimeState.playbackUri = uri
                    if (source.isNotBlank()) RuntimeState.playbackSource = source
                    if (source == "DLNA" && (duration > 0L || uri.isNotBlank())) {
                        setPlaybackSourceActive("dlna", true)
                    }
                }
                ACTION_PLAYBACK_STOPPED -> {
                    if (::dlnaRenderer.isInitialized) {
                        dlnaRenderer.updatePosition(0L, 0L)
                        dlnaRenderer.setStopped()
                    }
                    RuntimeState.resetPlayback()
                    setPlaybackSourceActive("dlna", false)
                    Timber.i("Playback stopped locally, renderer reset to STOPPED")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("CastReceiverService created")
        createNotificationChannel()
        RuntimeState.serviceRunning = true
        RuntimeState.serviceStartedAtMs = System.currentTimeMillis()
        RuntimeState.lastError = ""
        registerPlayerStateReceiver()
        registerNetworkCallback()
    }

    private fun registerPlayerStateReceiver() {
        if (playerReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_UPDATE_POSITION)
            addAction(ACTION_PLAYBACK_STOPPED)
        }
        ContextCompat.registerReceiver(
            this,
            playerStateReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        playerReceiverRegistered = true
    }

    private fun registerNetworkCallback() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                connectivityManager.registerDefaultNetworkCallback(networkCallback)
            } else {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
            }
        }.onFailure { Timber.w(it, "Unable to register network callback") }
    }

    private fun initReceivers() {
        if (initialized) return
        initialized = true

        val settings = AppSettings.snapshot(this)
        val deviceInfoProvider = DeviceInfoProvider(this)
        deviceUuid = AppSettings.getOrCreateDeviceUuid(this)
        connectionCode = AppSettings.getOrCreateConnectionCode(this)
        customMdnsAdvertiser = MdnsAdvertiser(this)

        val mirrorResolution = getBestDisplayResolution(settings.mirrorMaxHeight)
        val deviceName = deviceInfoProvider.getDeviceName()
        Timber.i("Receiver config: name=$deviceName mirror=${mirrorResolution.first}x${mirrorResolution.second}")

        airPlayReceiver = AirPlayReceiver(
            context = this,
            displayName = deviceName,
            mirrorWidth = mirrorResolution.first,
            mirrorHeight = mirrorResolution.second,
            audioEnabled = settings.airPlayAudioEnabled,
            videoSurfaceProvider = { PlayerActivity.mirrorSurface },
            onStateChanged = { state ->
                RuntimeState.airPlayState = state.name
                Timber.i("AirPlay state: $state")
                if (state == AirPlayState.CONNECTED) {
                    setPlaybackSourceActive("airplay", true)
                    if (!airPlayPlayerStarted && AppSettings.isAutoLaunchPlayer(this)) {
                        airPlayPlayerStarted = true
                        RuntimeState.playbackSource = "AirPlay"
                        RuntimeState.playbackTitle = "iPhone 屏幕镜像"
                        startActivity(Intent(this, PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, "iPhone 屏幕镜像")
                            putExtra(PlayerActivity.EXTRA_IS_AIRPLAY_MIRROR, true)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                    }
                } else {
                    setPlaybackSourceActive("airplay", false)
                    if (airPlayPlayerStarted) {
                        airPlayPlayerStarted = false
                        sendPlayerBroadcast(PlayerActivity.ACTION_STOP)
                    }
                }
            },
            onSenderNameChanged = { sender ->
                RuntimeState.airPlaySender = sender.orEmpty()
                Timber.i("AirPlay sender: $sender")
            }
        )

        initWfdServer(deviceName)
        initDlnaServices(deviceInfoProvider, settings.upnpPort)
    }

    private fun getBestDisplayResolution(maxHeightSetting: Int): Pair<Int, Int> {
        var width = resources.displayMetrics.widthPixels
        var height = resources.displayMetrics.heightPixels

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val display = (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
            val mode = display?.supportedModes
                ?.maxByOrNull { it.physicalWidth * it.physicalHeight }
                ?: display?.mode
            if (mode != null) {
                width = mode.physicalWidth
                height = mode.physicalHeight
            }
        }

        var longSide = maxOf(width, height).coerceIn(1280, 3840)
        var shortSide = minOf(width, height).coerceIn(720, 2160)
        if (maxHeightSetting > 0 && shortSide > maxHeightSetting) {
            val ratio = maxHeightSetting.toDouble() / shortSide.toDouble()
            shortSide = maxHeightSetting
            longSide = (longSide * ratio).toInt().coerceAtLeast(1280)
        }
        return longSide to shortSide
    }

    private fun initWfdServer(deviceName: String) {
        wfdServer = WfdServer(this, 7236).apply {
            onConnectionRequested = { clientName, clientAddress ->
                RuntimeState.miracastState = "CONNECTING"
                RuntimeState.miracastClient = clientName.ifBlank { clientAddress }
                Timber.i("Miracast connection requested: $clientName from $clientAddress")
            }
            onConnectionEstablished = { sessionId ->
                RuntimeState.miracastState = "CONNECTED"
                Timber.i("Miracast session established: $sessionId")
            }
            onStreamStarted = { rtpPort ->
                RuntimeState.miracastState = "STREAMING"
                RuntimeState.miracastRtpPort = rtpPort
                RuntimeState.playbackSource = "Miracast"
                setPlaybackSourceActive("miracast", true)
                Timber.i("Miracast stream started on RTP port: $rtpPort")
            }
            onStreamStopped = {
                RuntimeState.miracastState = "IDLE"
                RuntimeState.miracastRtpPort = 0
                setPlaybackSourceActive("miracast", false)
                Timber.i("Miracast stream stopped, closing player")
                sendPlayerBroadcast(PlayerActivity.ACTION_STOP)
            }
        }

        wifiDirectManager = WifiDirectManager(this, deviceName).apply {
            onGroupCreated = { group ->
                Timber.i("Wi-Fi Direct group created for Miracast: ${group.networkName}")
            }
            onDeviceConnected = { device ->
                RuntimeState.miracastClient = device.deviceName.orEmpty()
                Timber.i("Miracast device connected: ${device.deviceName}")
            }
            onDeviceDisconnected = {
                RuntimeState.miracastState = "IDLE"
                RuntimeState.miracastClient = ""
            }
        }
    }

    private fun initDlnaServices(deviceInfoProvider: DeviceInfoProvider, port: Int) {
        val localIp = NetworkUtils.getLocalIpAddress() ?: "127.0.0.1"
        dlnaRenderer = DlnaMediaRenderer()

        dlnaRenderer.onSetUri = { uri, metadata ->
            val title = extractTitle(metadata)
            RuntimeState.playbackUri = uri
            RuntimeState.playbackTitle = title
            RuntimeState.playbackSource = "DLNA"
            Timber.i("DLNA SetURI: $uri")

            if (AppSettings.isAutoLaunchPlayer(this)) {
                val state = dlnaRenderer.getState()
                startActivity(Intent(this, PlayerActivity::class.java).apply {
                    if (state.playlist.size > 1) {
                        putStringArrayListExtra(PlayerActivity.EXTRA_MEDIA_URIS, ArrayList(state.playlist))
                        putExtra(PlayerActivity.EXTRA_START_INDEX, state.currentIndex)
                    } else {
                        putExtra(PlayerActivity.EXTRA_MEDIA_URI, uri)
                    }
                    putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, title)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
        }

        dlnaRenderer.onPlay = {
            setPlaybackSourceActive("dlna", true)
            sendPlayerBroadcast(PlayerActivity.ACTION_PLAY)
        }
        dlnaRenderer.onPause = { sendPlayerBroadcast(PlayerActivity.ACTION_PAUSE) }
        dlnaRenderer.onStop = {
            setPlaybackSourceActive("dlna", false)
            sendPlayerBroadcast(PlayerActivity.ACTION_STOP)
        }
        dlnaRenderer.onSeek = { position ->
            sendBroadcast(Intent(PlayerActivity.ACTION_SEEK).apply {
                putExtra(PlayerActivity.EXTRA_SEEK_POSITION, position)
                setPackage(packageName)
            })
        }
        dlnaRenderer.onVolumeChanged = { volume ->
            RuntimeState.playbackVolume = volume
            sendBroadcast(Intent(PlayerActivity.ACTION_SET_VOLUME).apply {
                putExtra(PlayerActivity.EXTRA_VOLUME, volume)
                setPackage(packageName)
            })
        }
        dlnaRenderer.onSpeedChanged = { speed ->
            RuntimeState.playbackSpeed = speed
            sendBroadcast(Intent(PlayerActivity.ACTION_SET_SPEED).apply {
                putExtra(PlayerActivity.EXTRA_SPEED, speed)
                setPackage(packageName)
            })
        }
        dlnaRenderer.onQualityUriChanged = { uri ->
            sendBroadcast(Intent(PlayerActivity.ACTION_SET_QUALITY_URL).apply {
                putExtra(PlayerActivity.EXTRA_QUALITY_URI, uri)
                setPackage(packageName)
            })
        }

        upnpHttpServer = UpnpHttpServer(
            context = this,
            renderer = dlnaRenderer,
            deviceUuid = deviceUuid,
            deviceName = deviceInfoProvider.getDeviceName(),
            manufacturer = Build.MANUFACTURER,
            modelName = Build.MODEL,
            localIp = localIp,
            port = port
        )
        ssdpServer = SsdpServer(
            context = this,
            deviceUuid = deviceUuid,
            localIp = localIp,
            httpPort = port
        )
    }

    private fun extractTitle(metadata: String): String {
        val titlePattern = Regex("<dc:title>(.*?)</dc:title>", RegexOption.IGNORE_CASE)
        return titlePattern.find(metadata)?.groupValues?.getOrNull(1) ?: "DLNA 投屏"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false
        Timber.i("CastReceiverService started (fromBoot=$fromBoot)")
        updateForegroundType()
        startWhenNetworkReady()
        return START_STICKY
    }

    private fun updateForegroundType() {
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (activePlaybackSources.isNotEmpty()) {
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, createNotification(), type)
    }

    private fun setPlaybackSourceActive(source: String, active: Boolean) {
        val changed = if (active) activePlaybackSources.add(source) else activePlaybackSources.remove(source)
        if (changed) updateForegroundType()
    }

    private fun startWhenNetworkReady() {
        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null

        val ip = NetworkUtils.getLocalIpAddress()
        if (ip != null) {
            networkWaitElapsedMs = 0L
            lastKnownIp = ip
            RuntimeState.networkIp = ip
            initReceivers()
            startAllServices()
            return
        }

        if (networkWaitElapsedMs >= NETWORK_MAX_WAIT_MS) {
            Timber.w("Network still not ready after ${NETWORK_MAX_WAIT_MS / 1000}s; WebUI/protocol startup deferred")
            networkWaitElapsedMs = 0L
        }

        val runnable = Runnable {
            networkWaitElapsedMs += NETWORK_RETRY_INTERVAL_MS
            startWhenNetworkReady()
        }
        networkWaitRunnable = runnable
        mainHandler.postDelayed(runnable, NETWORK_RETRY_INTERVAL_MS)
    }

    private fun startAllServices() {
        if (servicesStarted) return
        val settings = AppSettings.snapshot(this)
        val failures = mutableListOf<String>()

        if (settings.airPlayEnabled) {
            runCatching { airPlayReceiver.start() }
                .onFailure { failures += "AirPlay: ${it.message}"; Timber.e(it, "AirPlay start failed") }
        }

        if (settings.customMdnsEnabled) {
            runCatching {
                val deviceInfoProvider = DeviceInfoProvider(this)
                customMdnsAdvertiser.startAdvertising(
                    serviceName = deviceInfoProvider.getDeviceName(),
                    port = settings.upnpPort,
                    deviceInfo = deviceInfoProvider.getDeviceInfo() + ("code" to connectionCode)
                )
            }.onFailure { failures += "mDNS: ${it.message}"; Timber.e(it, "custom mDNS start failed") }
        }

        if (settings.dlnaEnabled) {
            runCatching { upnpHttpServer.start() }
                .onFailure { failures += "UPnP: ${it.message}"; Timber.e(it, "UPnP start failed") }
            runCatching { ssdpServer.start() }
                .onFailure { failures += "SSDP: ${it.message}"; Timber.e(it, "SSDP start failed") }
        }

        if (settings.miracastEnabled) {
            runCatching { wifiDirectManager.start() }
                .onFailure { failures += "Wi-Fi Direct: ${it.message}"; Timber.e(it, "Wi-Fi Direct start failed") }
            runCatching {
                if (!WfdRootHelper.advertiseSink(this)) {
                    failures += "WFD IE injection failed"
                    Timber.w("Miracast WFD IE injection failed; Windows discovery may not work")
                }
            }
            runCatching { wfdServer.start() }
                .onFailure { failures += "WFD RTSP: ${it.message}"; Timber.e(it, "WFD server start failed") }
        }

        if (settings.webUiEnabled) {
            runCatching {
                webUiServer = WebUiServer(
                    context = this,
                    port = settings.webUiPort,
                    onReconfigureRequested = { reason -> requestReconfigure(reason) },
                    onRestartReceiverRequested = { requestReconfigure("WebUI receiver restart") }
                ).also { it.start() }
            }.onFailure { failures += "WebUI: ${it.message}"; Timber.e(it, "WebUI start failed") }
        }

        servicesStarted = true
        RuntimeState.lastError = failures.joinToString("; ")
        Timber.i(
            "Receiver services started: AirPlay=${settings.airPlayEnabled}, DLNA=${settings.dlnaEnabled}, " +
                "Miracast=${settings.miracastEnabled}, WebUI=${settings.webUiEnabled}"
        )
    }

    private fun requestReconfigure(reason: String) {
        mainHandler.post {
            reconfigureRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable {
                Timber.i("Reconfiguring receiver: $reason")
                stopAllServices()
                initialized = false
                networkWaitElapsedMs = 0L
                startWhenNetworkReady()
            }
            reconfigureRunnable = runnable
            mainHandler.postDelayed(runnable, RECONFIGURE_DELAY_MS)
        }
    }

    private fun stopAllServices() {
        servicesStarted = false
        runCatching { webUiServer?.stop() }
        webUiServer = null
        if (initialized) {
            runCatching { customMdnsAdvertiser.stopAdvertising() }
            runCatching { airPlayReceiver.stop() }
            runCatching { ssdpServer.stop() }
            runCatching { upnpHttpServer.stop() }
            shutdownMiracast()
        }
        airPlayPlayerStarted = false
        RuntimeState.airPlayState = "IDLE"
        RuntimeState.airPlaySender = ""
        RuntimeState.miracastState = "IDLE"
        RuntimeState.miracastClient = ""
        RuntimeState.miracastRtpPort = 0
        activePlaybackSources.clear()
        updateForegroundType()
    }

    private fun scheduleNetworkStateCheck() {
        mainHandler.postDelayed({
            val ip = NetworkUtils.getLocalIpAddress()
            RuntimeState.networkIp = ip.orEmpty()
            val previous = lastKnownIp
            if (ip != previous) {
                lastKnownIp = ip
                Timber.i("Network address changed: $previous -> $ip")
                if (servicesStarted && ip != null) requestReconfigure("network address changed")
                else if (!servicesStarted && ip != null) startWhenNetworkReady()
            }
        }, 900L)
    }

    private fun sendPlayerBroadcast(action: String) {
        sendBroadcast(Intent(action).apply { setPackage(packageName) })
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        Timber.i("Task removed; cast receiver remains active in background")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Timber.i("CastReceiverService destroyed")
        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        reconfigureRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null
        reconfigureRunnable = null

        stopAllServices()

        if (playerReceiverRegistered) {
            runCatching { unregisterReceiver(playerStateReceiver) }
            playerReceiverRegistered = false
        }
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }

        RuntimeState.serviceRunning = false
        RuntimeState.serviceStartedAtMs = 0L
        super.onDestroy()
    }

    private fun shutdownMiracast() {
        if (::wfdServer.isInitialized) runCatching { wfdServer.stop() }
        runCatching { WfdRootHelper.stopAdvertising(this) }
        if (::wifiDirectManager.isInitialized) runCatching { wifiDirectManager.stop() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持 Android TV 投屏接收服务运行"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val text = if (activePlaybackSources.isEmpty()) {
            "后台等待 AirPlay / DLNA / Miracast 连接"
        } else {
            "正在投屏：${activePlaybackSources.joinToString(" / ")}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
}
