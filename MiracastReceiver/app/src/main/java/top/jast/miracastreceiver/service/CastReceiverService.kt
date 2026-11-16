package top.jast.miracastreceiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import top.jast.miracastreceiver.R
import top.jast.miracastreceiver.airplay.AirPlayCapabilities
import top.jast.miracastreceiver.airplay.AirPlayReceiver
import top.jast.miracastreceiver.airplay.AirPlayState
import top.jast.miracastreceiver.airplay.fitMirrorResolution
import top.jast.miracastreceiver.airplay.orientMirrorResolution
import top.jast.miracastreceiver.cast.CastPlayerBridge
import top.jast.miracastreceiver.cast.GoogleCastReceiver
import top.jast.miracastreceiver.discovery.DeviceInfoProvider
import top.jast.miracastreceiver.discovery.MdnsAdvertiser
import top.jast.miracastreceiver.dlna.DlnaMediaRenderer
import top.jast.miracastreceiver.dlna.SsdpServer
import top.jast.miracastreceiver.dlna.UpnpHttpServer
import top.jast.miracastreceiver.miracast.WfdRootHelper
import top.jast.miracastreceiver.miracast.WfdServer
import top.jast.miracastreceiver.miracast.WifiDirectManager
import top.jast.miracastreceiver.ui.PlayerActivity
import top.jast.miracastreceiver.util.AppLanguage
import top.jast.miracastreceiver.util.AppSettings
import android.content.SharedPreferences
import top.jast.miracastreceiver.utils.NetworkUtils
import top.jast.miracastreceiver.webrtc.WebRtcReceiver
import top.jast.miracastreceiver.web.RuntimeState
import top.jast.miracastreceiver.web.WebUiServer
import timber.log.Timber

/** Always-on Android TV casting receiver core. */
class CastReceiverService : Service() {

    companion object {
        const val EXTRA_FROM_BOOT = "from_boot"

        private const val ACTION_STOP_CAST = "top.jast.miracastreceiver.STOP_CAST"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "cast_receiver_service"
        private const val ACTION_UPDATE_POSITION = "top.jast.miracastreceiver.ACTION_UPDATE_POSITION"
        private const val ACTION_PLAYBACK_STOPPED = "top.jast.miracastreceiver.ACTION_PLAYBACK_STOPPED"

        private const val NETWORK_RETRY_INTERVAL_MS = 5_000L
        private const val NETWORK_MAX_WAIT_MS = 5 * 60_000L
        private const val RECONFIGURE_DELAY_MS = 350L
        // Serialize root commands and cleanup without blocking the service/UI thread.
        private val wfdRootExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    }

    private lateinit var airPlayReceiver: AirPlayReceiver
    private lateinit var customMdnsAdvertiser: MdnsAdvertiser
    private lateinit var dlnaRenderer: DlnaMediaRenderer
    private lateinit var castPlayerBridge: CastPlayerBridge
    private lateinit var googleCastReceiver: GoogleCastReceiver
    private lateinit var castPlusReceiver: top.jast.miracastreceiver.castplus.CastPlusReceiver
    private lateinit var ssdpServer: SsdpServer
    private lateinit var upnpHttpServer: UpnpHttpServer
    private lateinit var wfdServer: WfdServer
    private lateinit var wifiDirectManager: WifiDirectManager
    private var webUiServer: WebUiServer? = null

    private lateinit var deviceUuid: String
    private lateinit var connectionCode: String
    private var airPlayPlayerStarted = false
    private var webrtcPlayerStarted = false

    private var initialized = false
    private var servicesStarted = false
    private var playerReceiverRegistered = false
    private var destroying = false
    /** 上游新增：停止流程标志，避免 stop 过程中回调又刷新状态。 */
    @Volatile private var stopping = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var networkWaitRunnable: Runnable? = null
    private var reconfigureRunnable: Runnable? = null
    private var networkWaitElapsedMs = 0L
    private var lastKnownIp: String? = null
    private var rootAdvertisingActive = false
    private var rootAdvertisingAttempted = false
    private var airPlayLandscapeResolution = 1920 to 1080

    private val activePlaybackSources = linkedSetOf<String>()
    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = scheduleNetworkStateCheck()
        override fun onLost(network: Network) = scheduleNetworkStateCheck()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
            scheduleNetworkStateCheck()
    }

    // 上游新增：设备名/竖屏设置变化时刷新各协议的公告与分辨率
    private val nameListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == AppSettings.KEY_RECEIVER_NAME && initialized && !stopping) {
            val name = DeviceInfoProvider(this).getDeviceName()
            airPlayReceiver.updateDisplayName(name)
            upnpHttpServer.updateDisplayName(name)
            ssdpServer.refreshAdvertisement()
            googleCastReceiver.updateDisplayName(name)
            castPlusReceiver.updateDisplayName(name)
            wifiDirectManager.updateDeviceName(name)
            syncRootDeviceName()
        }
        if (key == AppSettings.KEY_AIRPLAY_PORTRAIT_DISPLAY && initialized && !stopping) {
            val (width, height) = orientMirrorResolution(airPlayLandscapeResolution.first,
                airPlayLandscapeResolution.second, AppSettings.isAirPlayPortraitDisplay(this))
            airPlayReceiver.updateDisplayResolution(width, height)
        }
    }

    private val playerStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                CastPlayerBridge.ACTION_STATE -> castPlayerBridge.onState(intent)
                "top.jast.miracastreceiver.DISCONNECT_SENDER" -> {
                    if (intent.getBooleanExtra("miracast", false)) {
                        wfdServer.disconnectSender()
                        wifiDirectManager.disconnectSender()
                    } else {
                        airPlayReceiver.disconnectSender()
                    }
                }
                ACTION_UPDATE_POSITION -> {
                    val current = RuntimeState.playbackSnapshot()
                    val position = intent.getLongExtra("position", current.positionMs)
                    val duration = intent.getLongExtra("duration", current.durationMs)
                    val isPlaying = intent.getBooleanExtra("is_playing", false)
                    val isLive = if (intent.hasExtra("is_live")) intent.getBooleanExtra("is_live", false) else current.isLive
                    val isSeekable = if (intent.hasExtra("is_seekable")) intent.getBooleanExtra("is_seekable", false) else current.isSeekable
                    val volume = if (intent.hasExtra("volume")) intent.getIntExtra("volume", current.volume).coerceIn(0, 100) else current.volume
                    val speed = if (intent.hasExtra("speed")) intent.getFloatExtra("speed", current.speed).coerceIn(0.25f, 4f) else current.speed
                    val title = intent.getStringExtra("title").orEmpty()
                    val uri = intent.getStringExtra("uri").orEmpty()
                    val source = intent.getStringExtra("source").orEmpty()

                    if (::dlnaRenderer.isInitialized) {
                        dlnaRenderer.updatePosition(position, duration)
                        if (isPlaying) dlnaRenderer.setPlaying() else dlnaRenderer.setPaused()
                    }
                    RuntimeState.updatePlayback { old ->
                        old.copy(
                            positionMs = position,
                            durationMs = duration,
                            isLive = isLive,
                            isSeekable = isSeekable,
                            speed = speed,
                            volume = volume,
                            state = if (isPlaying) "PLAYING" else "PAUSED",
                            title = title.ifBlank { old.title },
                            uri = uri.ifBlank { old.uri },
                            source = source.ifBlank { old.source }
                        )
                    }
                    val activeSource = source.ifBlank { RuntimeState.playbackSnapshot().source }
                    if (activeSource == "DLNA" && (duration > 0L || uri.isNotBlank() || position > 0L)) {
                        setPlaybackSourceActive("dlna", true)
                    }
                    // 上游新增：按会话 ID 同步 DLNA MediaRenderer 的播放进度
                    val sessionId = intent.getLongExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_DLNA_SESSION_ID, -1L)
                    if (::dlnaRenderer.isInitialized && sessionId != -1L) {
                        dlnaRenderer.onPlayerPosition(sessionId, position, duration, isPlaying)
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
                    // 上游新增：按会话释放 DLNA 媒体，并通知事件订阅者
                    val sessionId = intent.getLongExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_DLNA_SESSION_ID, -1L)
                    val releaseMedia = intent.getBooleanExtra(top.jast.miracastreceiver.ui.PlayerActivity.EXTRA_RELEASE_DLNA_MEDIA, false)
                    if (::dlnaRenderer.isInitialized && sessionId != -1L && dlnaRenderer.onPlayerStopped(sessionId, releaseMedia)) {
                        upnpHttpServer.notifyAvTransportChanged()
                    }
                }
            }
        }
    }

    /** 通知、DLNA 默认标题等文案跟随应用内选择的语言 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        ReceiverStatus.service = ReceiverStatus.Service.STARTING
        ReceiverStatus.miracast = ReceiverStatus.Miracast.CHECKING
        Timber.i("CastReceiverService created")
        createNotificationChannel()
        RuntimeState.serviceRunning = true
        RuntimeState.serviceStartedAtMs = System.currentTimeMillis()
        RuntimeState.lastError = ""
        AppSettings.observe(this, nameListener)
        registerPlayerStateReceiver()
        registerNetworkCallback()
        // startForeground 放在 onStartCommand 里：前台服务类型要看这次是不是开机拉起的
        // （Android 15 起 BOOT_COMPLETED 不允许启动 mediaPlayback 类型）。
    }

    private fun registerPlayerStateReceiver() {
        if (playerReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(CastPlayerBridge.ACTION_STATE)
            addAction("top.jast.miracastreceiver.DISCONNECT_SENDER")
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

    /**
     * 真正的初始化。开机启动时网络还没就绪，DLNA/SSDP 需要本机 IP 才能正确绑定，
     * 所以推迟到 [startWhenNetworkReady] 拿到 IP 之后再做，且只做一次。
     */
    private fun initReceivers() {
        if (initialized) return
        initialized = true

        val settings = AppSettings.snapshot(this)
        val deviceInfoProvider = DeviceInfoProvider(this)
        deviceUuid = AppSettings.getOrCreateDeviceUuid(this)
        connectionCode = AppSettings.getOrCreateConnectionCode(this)
        customMdnsAdvertiser = MdnsAdvertiser(this)

        // A 4K display alone is insufficient: macOS may choose HEVC even when the
        // receiver has only advertised AVC. Keep AVC-only hardware within 1080p.
        val displayResolution = getBestDisplayResolution(settings.mirrorMaxHeight)
        airPlayLandscapeResolution = if (AirPlayCapabilities.hardwareHevc4k) {
            displayResolution
        } else {
            fitMirrorResolution(displayResolution.first, displayResolution.second, 1920, 1080)
        }
        val mirrorResolution = orientMirrorResolution(airPlayLandscapeResolution.first,
            airPlayLandscapeResolution.second, AppSettings.isAirPlayPortraitDisplay(this))
        val deviceName = deviceInfoProvider.getDeviceName()
        Timber.i("Receiver config: name=$deviceName mirror=${mirrorResolution.first}x${mirrorResolution.second}")

        // 初始化 AirPlay 2 接收器（基于 PhairPlay 实现，支持 iOS 屏幕镜像）
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
                    RuntimeState.updatePlayback {
                        it.copy(
                            state = "MIRRORING",
                            source = "AirPlay",
                            title = "iPhone 屏幕镜像",
                            positionMs = 0L,
                            durationMs = 0L,
                            isLive = true,
                            isSeekable = false,
                            decoderName = "",
                            hardwareDecoder = false
                        )
                    }
                    setPlaybackSourceActive("airplay", true)
                    if (!airPlayPlayerStarted && AppSettings.isAutoLaunchPlayer(this)) {
                        airPlayPlayerStarted = true
                        startActivity(Intent(this, PlayerActivity::class.java).apply {
                            putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, "iPhone 屏幕镜像")
                            putExtra(PlayerActivity.EXTRA_IS_AIRPLAY_MIRROR, true)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        })
                    }
                } else {
                    setPlaybackSourceActive("airplay", false)
                    if (RuntimeState.playbackSource == "AirPlay" && activePlaybackSources.isEmpty()) {
                        RuntimeState.resetPlayback()
                    }
                    if (airPlayPlayerStarted) {
                        airPlayPlayerStarted = false
                        sendPlayerBroadcast(PlayerActivity.ACTION_STOP)
                    }
                }
            },
            onSenderNameChanged = { sender ->
                RuntimeState.airPlaySender = sender
                Timber.i("AirPlay sender: $sender")
            },
            // 上游新增：真正的会话结束回调，独立于 mDNS 注册状态
            onSessionEnded = {
                if (airPlayPlayerStarted) {
                    airPlayPlayerStarted = false
                    Timber.i("AirPlay session ended, closing player")
                    sendBroadcast(Intent(PlayerActivity.ACTION_STOP).apply {
                        setPackage(packageName)
                        putExtra(PlayerActivity.EXTRA_SOURCE_TYPE, "AIRPLAY")
                    })
                }
            }
        )

        initWfdServer(deviceName)
        initWebRtcReceiver(deviceName)
        initDlnaServices(deviceInfoProvider, settings.upnpPort)

        // 上游新增：Cast V2 与 Cast+ 接收器（companion 静态初始化在 MiracastApp）
        castPlayerBridge = CastPlayerBridge(this)
        googleCastReceiver = GoogleCastReceiver(this, deviceName, deviceUuid, castPlayerBridge.session)
        castPlusReceiver = top.jast.miracastreceiver.castplus.CastPlusReceiver(this) {
            sendBroadcast(Intent(PlayerActivity.ACTION_REPLACED).setPackage(packageName))
            airPlayPlayerStarted = false
            runCatching { airPlayReceiver.disconnectSender() }
            runCatching { wfdServer.disconnectSender() }
            dlnaRenderer.setStopped()
        }
    }

    private fun initWebRtcReceiver(deviceName: String) {
        WebRtcReceiver.apply {
            onSignalingStarted = { port ->
                RuntimeState.webrtcSignalingPort = port
                Timber.i("WebRTC signaling ready on port $port")
            }
            onSessionRequested = { client ->
                RuntimeState.webrtcState = "CONNECTING"
                RuntimeState.webrtcClient = client
                Timber.i("WebRTC session requested: $client")
            }
            onSessionEstablished = { sessionId ->
                RuntimeState.webrtcState = "CONNECTED"
                Timber.i("WebRTC session established: $sessionId")
            }
            onStreamStarted = {
                RuntimeState.webrtcState = "STREAMING"
                RuntimeState.updatePlayback {
                    it.copy(
                        state = "MIRRORING",
                        source = "WebRTC",
                        title = "WebRTC 屏幕镜像",
                        positionMs = 0L,
                        durationMs = 0L,
                        isLive = true,
                        isSeekable = false,
                        decoderName = "",
                        hardwareDecoder = false
                    )
                }
                setPlaybackSourceActive("webrtc", true)
                if (!webrtcPlayerStarted && AppSettings.isAutoLaunchPlayer(this@CastReceiverService)) {
                    webrtcPlayerStarted = true
                    startActivity(Intent(this@CastReceiverService, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_IS_WEBRTC_MIRROR, true)
                        putExtra(PlayerActivity.EXTRA_MEDIA_TITLE, "WebRTC 屏幕镜像")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    })
                }
            }
            onStreamStopped = {
                RuntimeState.webrtcState = "IDLE"
                RuntimeState.webrtcClient = ""
                setPlaybackSourceActive("webrtc", false)
                if (RuntimeState.playbackSource == "WebRTC" && activePlaybackSources.isEmpty()) {
                    RuntimeState.resetPlayback()
                }
                Timber.i("WebRTC stream stopped, closing player")
                sendPlayerBroadcast(PlayerActivity.ACTION_STOP)
                webrtcPlayerStarted = false
            }
        }
    }

    private fun getBestDisplayResolution(maxHeightSetting: Int): Pair<Int, Int> {
        // Display.getSupportedModes / getMode 是 API 23 起才有的，而 minSdk 是 21。
        // 这个方法在服务 onCreate 里调用，低版本上直接调会 NoSuchMethodError 闪退，
        // 所以 Android 5.x 直接用 displayMetrics（精度略低但够用）。
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
                RuntimeState.updatePlayback {
                    it.copy(
                        state = "MIRRORING",
                        source = "Miracast",
                        title = "Windows 无线显示器",
                        positionMs = 0L,
                        durationMs = 0L,
                        isLive = true,
                        isSeekable = false,
                        decoderName = "",
                        hardwareDecoder = false
                    )
                }
                setPlaybackSourceActive("miracast", true)
                Timber.i("Miracast stream started on RTP port: $rtpPort")
            }
            onStreamStopped = {
                RuntimeState.miracastState = "IDLE"
                RuntimeState.miracastRtpPort = 0
                setPlaybackSourceActive("miracast", false)
                if (RuntimeState.playbackSource == "Miracast" && activePlaybackSources.isEmpty()) {
                    RuntimeState.resetPlayback()
                }
                Timber.i("Miracast stream stopped, closing player")
                // 上游修复：带上 SOURCE_TYPE，避免迟到的停止广播误关新协议的播放页
                sendBroadcast(Intent(PlayerActivity.ACTION_STOP).apply {
                    setPackage(packageName)
                    putExtra(PlayerActivity.EXTRA_SOURCE_TYPE, "MIRACAST")
                })
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
            RuntimeState.updatePlayback {
                it.copy(
                    uri = uri,
                    title = title,
                    source = "DLNA",
                    positionMs = 0L,
                    durationMs = 0L,
                    isLive = false,
                    isSeekable = false,
                    decoderName = "",
                    hardwareDecoder = false
                )
            }
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
                    // 上游新增：会话 ID 让播放页回报的进度能写回正确的 DLNA 会话
                    putExtra(PlayerActivity.EXTRA_SOURCE_TYPE, "DLNA")
                    putExtra(PlayerActivity.EXTRA_DLNA_SESSION_ID, dlnaRenderer.getMediaSessionId())
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                })
            }
        }

        dlnaRenderer.onPlay = {
            RuntimeState.playbackState = "PLAYING"
            setPlaybackSourceActive("dlna", true)
            sendPlayerBroadcast(PlayerActivity.ACTION_PLAY)
        }
        dlnaRenderer.onPause = {
            RuntimeState.playbackState = "PAUSED"
            sendPlayerBroadcast(PlayerActivity.ACTION_PAUSE)
        }
        dlnaRenderer.onStop = {
            setPlaybackSourceActive("dlna", false)
            if (RuntimeState.playbackSource == "DLNA" && activePlaybackSources.isEmpty()) {
                RuntimeState.resetPlayback()
            }
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

        // 创建 SSDP 服务器
        ssdpServer = SsdpServer(
            context = this,
            deviceUuid = deviceUuid,
            localIp = localIp,
            httpPort = port
        )
    }

    private fun extractTitle(metadata: String): String {
        // 简单解析 DIDL-Lite metadata 中的 title
        val titlePattern = Regex("<dc:title>(.*?)</dc:title>", RegexOption.IGNORE_CASE)
        return titlePattern.find(metadata)?.groupValues?.getOrNull(1) ?: getString(R.string.title_dlna)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAST) {
            // Stop the service as well as the player: audio can outlive its Surface/Activity.
            airPlayPlayerStarted = false
            sendBroadcast(Intent(PlayerActivity.ACTION_STOP).apply { setPackage(packageName) })
            stopSelf()
            return START_NOT_STICKY
        }
        val fromBoot = intent?.getBooleanExtra(EXTRA_FROM_BOOT, false) ?: false
        Timber.i("CastReceiverService started (fromBoot=$fromBoot)")
        updateForegroundType()
        startWhenNetworkReady()
        return START_STICKY
    }

    private fun updateForegroundType() {
        if (destroying || stopping) return
        // 有播放源时叠加 mediaPlayback；开机自启尚无播放源 → connectedDevice，
        // 避开 Android 15 对 BOOT_COMPLETED 启动 mediaPlayback 类型的限制。
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

    /**
     * 等到拿得到局域网 IP 再启动各路接收器。
     *
     * 开机自启时 Wi-Fi 往往还在连，这时候启动 SSDP/mDNS 会绑到错误的地址，
     * 结果就是服务活着但手机搜不到设备。这里每 5 秒看一次，最多等 5 分钟。
     */
    private fun startWhenNetworkReady() {
        networkWaitRunnable?.let { mainHandler.removeCallbacks(it) }
        networkWaitRunnable = null

        // 服务已在运行时再次 onStartCommand（例如首次授予 Wi-Fi Direct 权限后界面重启服务）：
        // 只重试 Wi-Fi Direct，避免再建一套接收器导致端口冲突和事件通道失效。
        if (servicesStarted) {
            Timber.i("Cast services already running — only retrying Wi-Fi Direct")
            runCatching { wifiDirectManager.start() }
            updateCastPlus()
            return
        }

        val ip = NetworkUtils.getLocalIpAddress(this)
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

        Timber.i("Network not ready yet, retry in ${NETWORK_RETRY_INTERVAL_MS}ms")
        ReceiverStatus.service = ReceiverStatus.Service.WAITING_NETWORK
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
            // 上游新增：Surface 重建时恢复 AirPlay URL 视频与屏幕镜像的输出
            PlayerActivity.mirrorSurfaceListener = { airPlayReceiver.onVideoSurfaceChanged() }
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
            // WfdRootHelper.advertiseSink is deliberately not called here. At this point P2P has
            // not been initialized, so the only control socket present is the STA wlan0, which
            // cannot emit a P2P advertisement at all; it would also run a root subprocess on the
            // main thread. WifiDirectManager performs the injection itself, after initialize()
            // and on a worker thread, where p2p-dev-* actually exists.
            runCatching { wifiDirectManager.start() }
                .onFailure { failures += "Wi-Fi Direct: ${it.message}"; Timber.e(it, "Wi-Fi Direct start failed") }
            runCatching { wfdServer.start() }
                .onFailure { failures += "WFD RTSP: ${it.message}"; Timber.e(it, "WFD server start failed") }
            // 上游新增：30 秒内还没进入 ADVERTISING 就标记 SETUP_FAILED，避免状态卡在 CHECKING
            mainHandler.postDelayed({
                if (!stopping && ReceiverStatus.miracast == ReceiverStatus.Miracast.CHECKING) {
                    ReceiverStatus.miracast = ReceiverStatus.Miracast.SETUP_FAILED
                }
            }, 30_000L)
        }

        if (settings.webrtcEnabled) {
            runCatching {
                WebRtcReceiver.start(
                    this,
                    DeviceInfoProvider(this).getDeviceName(),
                    { RuntimeState.webrtcState },
                    settings.webrtcPort
                )
            }.onFailure { failures += "WebRTC: ${it.message}"; Timber.e(it, "WebRTC receiver start failed") }
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

        // 上游新增：Cast V2 实验性接收器与 Cast+
        runCatching { googleCastReceiver.start() }
            .onFailure { failures += "GoogleCast: ${it.message}"; Timber.e(it, "Google Cast start failed") }
        updateCastPlus()

        servicesStarted = true
        ReceiverStatus.service = ReceiverStatus.Service.RUNNING
        RuntimeState.lastError = failures.joinToString("; ")
        Timber.i(
            "Receiver services started: AirPlay=${settings.airPlayEnabled}, DLNA=${settings.dlnaEnabled}, " +
                "Miracast=${settings.miracastEnabled}, WebRTC=${settings.webrtcEnabled}, WebUI=${settings.webUiEnabled}"
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
            runCatching { WebRtcReceiver.stop() }
            runCatching { googleCastReceiver.stop() }
            runCatching { castPlusReceiver.stop() }
            runCatching { castPlayerBridge.stop() }
            PlayerActivity.mirrorSurfaceListener = null
            shutdownMiracast()
        }
        airPlayPlayerStarted = false
        webrtcPlayerStarted = false
        RuntimeState.airPlayState = "IDLE"
        RuntimeState.airPlaySender = ""
        RuntimeState.miracastState = "IDLE"
        RuntimeState.miracastClient = ""
        RuntimeState.miracastRtpPort = 0
        RuntimeState.webrtcState = "IDLE"
        RuntimeState.webrtcClient = ""
        RuntimeState.webrtcSignalingPort = 0
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
        destroying = true
        stopping = true
        AppSettings.unobserve(this, nameListener)
        ReceiverStatus.service = ReceiverStatus.Service.STOPPED
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
        runCatching { WebRtcReceiver.release() }
        PlayerActivity.mirrorSurfaceListener = null

        RuntimeState.serviceRunning = false
        RuntimeState.serviceStartedAtMs = 0L
        super.onDestroy()
    }

    private fun updateCastPlus() {
        if (AppSettings.isCastPlusEnabled(this)) castPlusReceiver.start()
        else castPlusReceiver.stop()
    }

    private fun syncRootDeviceName() {
        if (!rootAdvertisingActive || stopping) return
        val appContext = applicationContext
        wfdRootExecutor.execute {
            if (!stopping) {
                val name = DeviceInfoProvider(appContext).getDeviceName()
                if (!WfdRootHelper.setDeviceName(appContext, name)) {
                    Timber.w("WFD: could not sync P2P device name via root")
                }
            }
        }
    }

    private fun shutdownMiracast() {
        if (::wfdServer.isInitialized) runCatching { wfdServer.stop() }
        runCatching { WfdRootHelper.stopAdvertising(this) }
        if (::wifiDirectManager.isInitialized) runCatching { wifiDirectManager.stop() }
        // 上游修复：WFD IE 存在于 wpa_supplicant，进程退出后不会自己消失，需主动撤销
        if (rootAdvertisingAttempted) {
            rootAdvertisingAttempted = false
            val appContext = applicationContext
            wfdRootExecutor.execute { runCatching { WfdRootHelper.stopAdvertising(appContext) } }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                // 渠道已存在时再次创建只会更新名称和描述，切换系统语言后重启服务即可跟着变
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val text = if (activePlaybackSources.isEmpty()) {
            getString(R.string.notification_waiting)
        } else {
            "正在投屏：${activePlaybackSources.joinToString(" / ")}"
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, top.jast.miracastreceiver.ui.MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopCast = PendingIntent.getService(
            this, 1, Intent(this, CastReceiverService::class.java).setAction(ACTION_STOP_CAST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_stop_cast), stopCast)
            .build()
    }
}
