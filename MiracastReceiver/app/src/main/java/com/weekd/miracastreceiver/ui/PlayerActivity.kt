package com.weekd.miracastreceiver.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.airplay.StreamStats
import com.weekd.miracastreceiver.miracast.RtpReceiver
import com.weekd.miracastreceiver.webrtc.WebRtcReceiver
import org.webrtc.SurfaceViewRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.URL

/** 投屏播放页面。 */
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MEDIA_URI = "media_uri"
        const val EXTRA_MEDIA_TITLE = "media_title"
        const val EXTRA_MEDIA_URIS = "media_uris"
        const val EXTRA_MEDIA_TITLES = "media_titles"
        const val EXTRA_START_INDEX = "start_index"
        const val EXTRA_IS_AIRPLAY_MIRROR = "is_airplay_mirror"
        const val EXTRA_IS_WEBRTC_MIRROR = "is_webrtc_mirror"

        const val EXTRA_SOURCE_TYPE = "SOURCE_TYPE"
        const val EXTRA_RTP_PORT = "RTP_PORT"
        const val EXTRA_SESSION_ID = "SESSION_ID"

        const val ACTION_PLAY = "com.weekd.miracastreceiver.ACTION_PLAY"
        const val ACTION_PAUSE = "com.weekd.miracastreceiver.ACTION_PAUSE"
        const val ACTION_STOP = "com.weekd.miracastreceiver.ACTION_STOP"
        const val ACTION_SEEK = "com.weekd.miracastreceiver.ACTION_SEEK"
        const val ACTION_SET_VOLUME = "com.weekd.miracastreceiver.ACTION_SET_VOLUME"
        const val ACTION_SET_PLAYLIST = "com.weekd.miracastreceiver.ACTION_SET_PLAYLIST"
        const val ACTION_SET_SPEED = "com.weekd.miracastreceiver.ACTION_SET_SPEED"
        const val ACTION_SET_QUALITY_URL = "com.weekd.miracastreceiver.ACTION_SET_QUALITY_URL"

        const val EXTRA_SEEK_POSITION = "seek_position"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_QUALITY_URI = "quality_uri"

        @Volatile
        var mirrorSurface: Surface? = null
            private set

        private const val IMAGE_SLIDE_INTERVAL_MS = 5_000L
        private const val QUALITY_AUTO = -1
        private const val SEEK_ACCEL_WINDOW_MS = 1_500L
        private const val SEEK_COMMIT_DELAY_MS = 500L
        private val SEEK_STEP_TABLE_MS = longArrayOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L)
        private const val ACTION_PLAYBACK_STOPPED = "com.weekd.miracastreceiver.ACTION_PLAYBACK_STOPPED"
    }

    private lateinit var playerView: PlayerView
    private lateinit var mirrorSurfaceView: SurfaceView
    private lateinit var imageView: ImageView
    private lateinit var tvStatus: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvPlaybackMeta: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvError: TextView
    private lateinit var tvStreamInfo: TextView
    private lateinit var bufferingIndicator: ProgressBar

    private var player: ExoPlayer? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var mediaUri: String? = null
    private var mediaTitle: String? = null
    private var playlist: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var cachedVideoMediaItems: List<MediaItem> = emptyList()
    private var cachedVideoIndexByPlaylistIndex: IntArray = IntArray(0)
    private var cachedPlaylistSignature: List<String> = emptyList()
    private var currentIndex: Int = 0
    private var slideJob: Job? = null
    private var progressUpdateJob: Job? = null
    private var qualityHeight: Int = QUALITY_AUTO
    private var isMiracastSession = false
    private var isAirPlayMirrorSession = false
    private var isWebRtcSession = false
    private var webrtcRendererInitialized = false
    private var webrtcSurfaceView: SurfaceViewRenderer? = null
    private var webrtcMetaJob: Job? = null
    private var mirrorAspectJob: Job? = null
    private var currentSpeed = 1f
    private var mediaLoadStartedAtMs = 0L
    private var mediaReadyAtMs = 0L
    private var lastDecoderName = ""
    private var lastDecoderInitDurationMs = -1L
    private var droppedVideoFrames = 0L

    private val streamInfoTracker = StreamInfoTracker()
    private var streamInfoJob: Job? = null
    private var isStreamInfoVisible = false
    private var bandwidthEstimateBps = 0L

    private var pendingSeekDeltaMs = 0L
    private var seekAccelerationStep = 0
    private var lastSeekWasForward = true
    private var lastSeekPressAt = 0L
    private var seekCommitJob: Job? = null
    private var isControllerVisible = false
    private var isDialogShowing = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_PLAY -> if (isCurrentImage()) startImageSlideShow() else player?.play()
                ACTION_PAUSE -> if (isCurrentImage()) stopImageSlideShow() else player?.pause()
                ACTION_STOP -> {
                    stopPlayback()
                    finish()
                }
                ACTION_SEEK -> {
                    val currentPlayer = player
                    if (currentPlayer?.isCurrentMediaItemSeekable == true) {
                        val position = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                        currentPlayer.seekTo(position)
                        reportPlaybackPosition()
                        updateCompactPlaybackMeta()
                    }
                }
                ACTION_SET_VOLUME -> {
                    val volume = intent.getIntExtra(EXTRA_VOLUME, 50)
                    player?.volume = volume / 100f
                    reportPlaybackPosition()
                }
                ACTION_SET_PLAYLIST -> handleIntent(intent)
                ACTION_SET_SPEED -> {
                    val speed = intent.getFloatExtra(EXTRA_SPEED, 1f).coerceIn(0.25f, 4f)
                    player?.setPlaybackSpeed(speed)
                    currentSpeed = speed
                    tvStatus.text = "播放速度：${speed}x"
                    reportPlaybackPosition()
                    updateCompactPlaybackMeta()
                }
                ACTION_SET_QUALITY_URL -> {
                    val uri = intent.getStringExtra(EXTRA_QUALITY_URI)
                    if (!uri.isNullOrBlank()) playMedia(uri)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                stopPlayback()
                finish()
            }
        })
        initViews()
        handleIntent(intent)
        registerControlReceiver()
        Timber.i("PlayerActivity created")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun initViews() {
        playerView = findViewById(R.id.player_view)
        mirrorSurfaceView = findViewById(R.id.airplay_mirror_surface)
        webrtcSurfaceView = findViewById(R.id.webrtc_surface)
        imageView = findViewById(R.id.image_view)
        tvStatus = findViewById(R.id.tv_status)
        tvTitle = findViewById(R.id.tv_title)
        tvPlaybackMeta = findViewById(R.id.tv_playback_meta)
        progressBar = findViewById(R.id.progress_bar)
        tvError = findViewById(R.id.tv_error)
        tvStreamInfo = findViewById(R.id.tv_stream_info)
        bufferingIndicator = findViewById(R.id.buffering_indicator)
    }

    private fun ensurePlayer() {
        if (player == null) initPlayer()
    }

    private fun initPlayer(lowLatency: Boolean = false) {
        player?.release()
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters().clearVideoSizeConstraints())
        }
        val loadControl = DefaultLoadControl.Builder()
            .apply {
                if (lowLatency) setBufferDurationsMs(1_000, 8_000, 500, 1_000)
                else setBufferDurationsMs(2_000, 20_000, 450, 1_000)
                setPrioritizeTimeOverSizeThresholds(true)
            }
            .build()

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector!!)
            .setLoadControl(loadControl)
            .build()
            .apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        updateBufferingState(playbackState == Player.STATE_BUFFERING)
                        when (playbackState) {
                            Player.STATE_IDLE -> Timber.d("Player state: IDLE")
                            Player.STATE_BUFFERING -> {
                                Timber.d("Player state: BUFFERING")
                                tvStatus.text = "正在缓冲..."
                            }
                            Player.STATE_READY -> {
                                Timber.d("Player state: READY")
                                if (mediaLoadStartedAtMs > 0L) {
                                    mediaReadyAtMs = SystemClock.elapsedRealtime()
                                    Timber.i("DLNA/media playback READY in ${mediaReadyAtMs - mediaLoadStartedAtMs}ms uri=$mediaUri")
                                }
                                tvError.visibility = View.GONE
                                tvStatus.text = getString(R.string.playing)
                                adaptOrientationToVideo()
                                updateCompactPlaybackMeta()
                                reportPlaybackPosition()
                                startProgressUpdates()
                            }
                            Player.STATE_ENDED -> {
                                Timber.d("Player state: ENDED")
                                mediaLoadStartedAtMs = 0L
                                mediaReadyAtMs = 0L
                                tvStatus.text = "播放完成"
                                updateCompactPlaybackMeta()
                                reportPlaybackPosition()
                                playNextOrFinish()
                            }
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        val now = SystemClock.elapsedRealtime()
                        if (mediaLoadStartedAtMs > 0L) {
                            val totalMs = now - mediaLoadStartedAtMs
                            val readyToFrameMs = if (mediaReadyAtMs > 0L) now - mediaReadyAtMs else -1L
                            Timber.i(
                                "DLNA/media first frame in ${totalMs}ms " +
                                    "(ready-to-frame=${readyToFrameMs}ms decoder=$lastDecoderName " +
                                    "decoder-init=${lastDecoderInitDurationMs}ms) uri=$mediaUri"
                            )
                        }
                        mediaLoadStartedAtMs = 0L
                        mediaReadyAtMs = 0L
                        updateCompactPlaybackMeta()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        tvStatus.text = when {
                            isPlaying -> getString(R.string.playing)
                            playbackState == Player.STATE_READY -> "已暂停"
                            else -> return
                        }
                        reportPlaybackPosition()
                        updateCompactPlaybackMeta()
                    }

                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        reportPlaybackPosition()
                        updateCompactPlaybackMeta()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Timber.e(error, "Player error")
                        mediaLoadStartedAtMs = 0L
                        mediaReadyAtMs = 0L
                        updateBufferingState(false)
                        tvStatus.text = "播放错误"
                        tvError.text = "播放错误: ${error.message ?: "未知错误"}"
                        tvError.visibility = View.VISIBLE
                    }
                })
                addAnalyticsListener(object : AnalyticsListener {
                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long
                    ) {
                        bandwidthEstimateBps = bitrateEstimate
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        lastDecoderName = decoderName
                        lastDecoderInitDurationMs = initializationDurationMs
                        Timber.d("Video decoder initialized name=$decoderName duration=${initializationDurationMs}ms")
                    }

                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        droppedVideoFrames += droppedFrames.toLong()
                    }
                })
            }

        playerView.player = player
        playerView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            findViewById<View?>(R.id.status_bar)?.visibility = visibility
            isControllerVisible = visibility == View.VISIBLE
            if (isControllerVisible) updateCompactPlaybackMeta()
        })
        playerView.setShowNextButton(true)
        playerView.setShowPreviousButton(true)
        setupControllerActions()
    }

    private fun setupControllerActions() {
        playerView.findViewById<View?>(R.id.btn_more)?.setOnClickListener { showMoreMenu() }
        playerView.findViewById<View?>(R.id.exo_ffwd)?.setOnClickListener { handleSeekPress(forward = true) }
        playerView.findViewById<View?>(R.id.exo_rew)?.setOnClickListener { handleSeekPress(forward = false) }
    }

    private fun showMoreMenu() {
        val labels = arrayOf(
            if (isStreamInfoVisible) "关闭视频信息" else "视频信息",
            "画质",
            "播放速度（${formatSpeedLabel(currentSpeed)}）",
            "字幕",
            "屏幕方向"
        )
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("更多")
            .setItems(labels) { _, which ->
                tvStatus.post {
                    when (which) {
                        0 -> toggleStreamInfo()
                        1 -> showQualityDialog()
                        2 -> showSpeedDialog()
                        3 -> Toast.makeText(this, "字幕切换将随媒体字幕轨自动支持", Toast.LENGTH_SHORT).show()
                        4 -> toggleOrientation()
                    }
                }
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun formatSpeedLabel(speed: Float): String = if (speed == 1f) "1.0x" else "${speed}x"

    private fun handleSeekPress(forward: Boolean) {
        val currentPlayer = player ?: return
        if (isCurrentImage() || !currentPlayer.isCurrentMediaItemSeekable) return
        val now = SystemClock.elapsedRealtime()
        val withinAccelWindow = now - lastSeekPressAt <= SEEK_ACCEL_WINDOW_MS
        seekAccelerationStep = if (withinAccelWindow && forward == lastSeekWasForward) {
            (seekAccelerationStep + 1).coerceAtMost(SEEK_STEP_TABLE_MS.lastIndex)
        } else {
            pendingSeekDeltaMs = 0L
            0
        }
        lastSeekWasForward = forward
        lastSeekPressAt = now
        val step = SEEK_STEP_TABLE_MS[seekAccelerationStep]
        pendingSeekDeltaMs += if (forward) step else -step
        previewPendingSeek()
        seekCommitJob?.cancel()
        seekCommitJob = lifecycleScope.launch {
            delay(SEEK_COMMIT_DELAY_MS)
            commitPendingSeek()
        }
    }

    private fun previewPendingSeek() {
        val currentPlayer = player ?: return
        if (!currentPlayer.isCurrentMediaItemSeekable) return
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (currentPlayer.currentPosition + pendingSeekDeltaMs).coerceIn(0L, duration)
        val sign = if (pendingSeekDeltaMs >= 0) "+" else "-"
        val deltaSeconds = kotlin.math.abs(pendingSeekDeltaMs) / 1000
        tvStatus.text = "${sign}${deltaSeconds}s  ${formatTimeMs(target)}"
        playerView.showController()
    }

    private fun commitPendingSeek() {
        val currentPlayer = player ?: return
        if (!currentPlayer.isCurrentMediaItemSeekable || pendingSeekDeltaMs == 0L) return
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (currentPlayer.currentPosition + pendingSeekDeltaMs).coerceIn(0L, duration)
        currentPlayer.seekTo(target)
        pendingSeekDeltaMs = 0L
        seekAccelerationStep = 0
        reportPlaybackPosition()
        updateCompactPlaybackMeta()
    }

    private fun formatTimeMs(ms: Long): String {
        val totalSeconds = ms.coerceAtLeast(0L) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
        else String.format("%d:%02d", minutes, seconds)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && !isDialogShowing) {
            if (event.keyCode == KeyEvent.KEYCODE_INFO || event.keyCode == KeyEvent.KEYCODE_MENU) {
                toggleStreamInfo()
                return true
            }
            if (event.keyCode == KeyEvent.KEYCODE_BACK && isStreamInfoVisible) {
                hideStreamInfo()
                return true
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && !isCurrentImage() && !isDialogShowing) {
            val isForwardKey = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            val isRewindKey = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
            if (isForwardKey || isRewindKey) {
                val isHardwareSeekKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND || event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                val inSeekMode = SystemClock.elapsedRealtime() - lastSeekPressAt <= SEEK_ACCEL_WINDOW_MS
                if (isHardwareSeekKey || inSeekMode || !isControllerVisible) {
                    handleSeekPress(forward = isForwardKey)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra(EXTRA_SOURCE_TYPE) == "MIRACAST") {
            startMiracastPlayback(intent.getIntExtra(EXTRA_RTP_PORT, 0), intent.getStringExtra(EXTRA_SESSION_ID))
            return
        }
        if (intent?.getBooleanExtra(EXTRA_IS_WEBRTC_MIRROR, false) == true) {
            startWebRtcPlayback()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_IS_AIRPLAY_MIRROR, false) == true) {
            startAirPlayMirrorPlayback()
            return
        }
        val list = intent?.getStringArrayListExtra(EXTRA_MEDIA_URIS)
            ?: intent?.getStringExtra(EXTRA_MEDIA_URI)?.let { arrayListOf(it) }
            ?: arrayListOf()
        val titles = intent?.getStringArrayListExtra(EXTRA_MEDIA_TITLES)
            ?: intent?.getStringExtra(EXTRA_MEDIA_TITLE)?.let { arrayListOf(it) }
            ?: arrayListOf()
        val nextPlaylist = list.filter { it.isNotBlank() }
        playlist = nextPlaylist
        playlistTitles = titles
        rebuildMediaItemCacheIfNeeded(nextPlaylist)
        currentIndex = intent?.getIntExtra(EXTRA_START_INDEX, 0)?.coerceIn(0, (playlist.size - 1).coerceAtLeast(0)) ?: 0
        if (playlist.isNotEmpty()) playCurrent()
    }

    private fun rebuildMediaItemCacheIfNeeded(nextPlaylist: List<String>) {
        if (cachedPlaylistSignature == nextPlaylist && cachedVideoIndexByPlaylistIndex.size == nextPlaylist.size) return
        val mapping = IntArray(nextPlaylist.size) { -1 }
        val items = ArrayList<MediaItem>(nextPlaylist.size)
        nextPlaylist.forEachIndexed { index, itemUri ->
            if (!isImageUri(itemUri)) {
                mapping[index] = items.size
                items += createMediaItem(itemUri)
            }
        }
        cachedPlaylistSignature = nextPlaylist.toList()
        cachedVideoIndexByPlaylistIndex = mapping
        cachedVideoMediaItems = items
        Timber.d("Prepared ${items.size} cached MediaItems for playlist size=${nextPlaylist.size}")
    }

    private fun playCurrent() {
        mediaUri = playlist.getOrNull(currentIndex)
        mediaTitle = playlistTitles.getOrNull(currentIndex) ?: "DLNA 投屏 ${currentIndex + 1}/${playlist.size}"
        tvTitle.text = mediaTitle
        updateCompactPlaybackMeta()
        val uri = mediaUri ?: return
        if (isImageUri(uri)) showImage(uri) else playMedia(uri)
    }

    private fun startAirPlayMirrorPlayback() {
        Timber.i("Starting AirPlay mirror playback")
        isAirPlayMirrorSession = true
        isMiracastSession = false
        streamInfoTracker.reset()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopImageSlideShow()
        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE
        imageView.visibility = View.GONE
        tvTitle.text = "iPhone 屏幕镜像"
        tvStatus.text = "正在接收 iPhone 屏幕..."
        tvPlaybackMeta.text = "AirPlay"
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        publishMirrorSurface()
    }

    private fun publishMirrorSurface() {
        val waitingText = if (isMiracastSession) "等待 Windows 画面..." else "等待 AirPlay 显示画面..."
        val activeText = if (isMiracastSession) "正在接收 Windows 屏幕..." else "正在接收 iPhone 屏幕..."
        fun publish(holder: SurfaceHolder) {
            mirrorSurface = holder.surface.takeIf { it.isValid }
            Timber.i("Mirror surface ${if (mirrorSurface == null) "not ready" else "ready"}")
            tvStatus.text = if (mirrorSurface == null) waitingText else activeText
        }
        mirrorSurfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = publish(holder)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = publish(holder)
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.i("Mirror surface destroyed")
                mirrorSurface = null
            }
        })
        mirrorSurfaceView.post { publish(mirrorSurfaceView.holder) }
        startMirrorAspectFitUpdates()
    }

    private fun startMirrorAspectFitUpdates() {
        mirrorAspectJob?.cancel()
        mirrorAspectJob = lifecycleScope.launch {
            var lastW = 0
            var lastH = 0
            var metaTicks = 0
            while (isActive) {
                val videoW = StreamStats.videoWidth
                val videoH = StreamStats.videoHeight
                if (videoW > 0 && videoH > 0 && (videoW != lastW || videoH != lastH)) {
                    lastW = videoW
                    lastH = videoH
                    fitMirrorSurface(videoW, videoH)
                }
                if (++metaTicks >= 3) {
                    metaTicks = 0
                    updateCompactPlaybackMeta()
                }
                delay(300)
            }
        }
    }

    private fun fitMirrorSurface(videoW: Int, videoH: Int) {
        val parent = mirrorSurfaceView.parent as? View ?: return
        val parentW = parent.width
        val parentH = parent.height
        if (parentW <= 0 || parentH <= 0) return
        val videoAspect = videoW.toFloat() / videoH.toFloat()
        val parentAspect = parentW.toFloat() / parentH.toFloat()
        val (targetW, targetH) = if (videoAspect > parentAspect) {
            parentW to (parentW / videoAspect).toInt()
        } else {
            (parentH * videoAspect).toInt() to parentH
        }
        mirrorSurfaceView.layoutParams = mirrorSurfaceView.layoutParams.apply {
            width = targetW.coerceAtLeast(1)
            height = targetH.coerceAtLeast(1)
        }
        Timber.i("AirPlay mirror aspect-fit: video=${videoW}x$videoH view=${targetW}x$targetH parent=${parentW}x$parentH")
    }

    private fun startMiracastPlayback(rtpPort: Int, sessionId: String?) {
        Timber.i("Starting Miracast display: rtpPort=$rtpPort session=$sessionId")
        isMiracastSession = true
        isAirPlayMirrorSession = false
        streamInfoTracker.reset()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopImageSlideShow()
        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        imageView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.VISIBLE
        tvTitle.text = "Windows 无线显示器"
        tvStatus.text = "正在接收 Windows 屏幕..."
        tvPlaybackMeta.text = "Miracast"
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        publishMirrorSurface()
    }

    private fun startWebRtcPlayback() {
        Timber.i("Starting WebRTC mirror playback")
        isWebRtcSession = true
        isMiracastSession = false
        isAirPlayMirrorSession = false
        streamInfoTracker.reset()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        stopImageSlideShow()
        player?.clearVideoSurface()
        player?.pause()
        playerView.player = null
        playerView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.GONE
        imageView.visibility = View.GONE
        tvTitle.text = "WebRTC 屏幕镜像"
        tvStatus.text = "等待 WebRTC 画面..."
        tvPlaybackMeta.text = "WebRTC"
        tvError.visibility = View.GONE
        updateBufferingState(false)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        val renderer = webrtcSurfaceView ?: return
        if (!webrtcRendererInitialized) {
            try {
                renderer.init(WebRtcReceiver.eglBaseContext, null)
                webrtcRendererInitialized = true
            } catch (e: Exception) {
                Timber.e(e, "WebRTC renderer init failed")
                tvStatus.text = "WebRTC 渲染初始化失败"
                return
            }
        }
        renderer.visibility = View.VISIBLE
        WebRtcReceiver.attachRenderer(renderer)
        webrtcMetaJob?.cancel()
        webrtcMetaJob = lifecycleScope.launch {
            while (isActive && isWebRtcSession) {
                delay(1000)
                updateCompactPlaybackMeta()
            }
        }
    }

    private fun stopWebRtcPlayback() {
        if (!isWebRtcSession) return
        isWebRtcSession = false
        webrtcMetaJob?.cancel()
        webrtcMetaJob = null
        WebRtcReceiver.detachRenderer()
        webrtcSurfaceView?.visibility = View.GONE
    }

    private fun playMedia(uri: String) {
        Timber.i("Playing media: $uri")
        ensurePlayer()
        isMiracastSession = false
        isAirPlayMirrorSession = false
        streamInfoTracker.reset()
        stopImageSlideShow()
        mirrorSurfaceView.visibility = View.GONE
        imageView.visibility = View.GONE
        playerView.visibility = View.VISIBLE
        if (playerView.player !== player) playerView.player = player
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        try {
            tvError.visibility = View.GONE
            updateBufferingState(true)
            mediaLoadStartedAtMs = SystemClock.elapsedRealtime()
            mediaReadyAtMs = 0L
            lastDecoderName = ""
            lastDecoderInitDurationMs = -1L
            droppedVideoFrames = 0L
            if (playlist.size > 1 && cachedVideoMediaItems.isNotEmpty()) {
                val videoIndex = cachedVideoIndexByPlaylistIndex.getOrNull(currentIndex)?.takeIf { it >= 0 } ?: 0
                player?.setMediaItems(cachedVideoMediaItems, videoIndex, 0L)
            } else {
                player?.setMediaItem(createMediaItem(uri))
            }
            player?.playWhenReady = true
            player?.prepare()
        } catch (e: Exception) {
            Timber.e(e, "Error playing media")
            mediaLoadStartedAtMs = 0L
            mediaReadyAtMs = 0L
            tvStatus.text = "播放错误"
            tvError.text = "播放错误: ${e.message ?: "未知错误"}"
            tvError.visibility = View.VISIBLE
            updateBufferingState(false)
        }
    }

    private fun createMediaItem(uri: String): MediaItem {
        val isHls = uri.contains(".m3u8") || uri.contains("/playlist/m3u8")
        return if (isHls) {
            MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.APPLICATION_M3U8).build()
        } else MediaItem.fromUri(uri)
    }

    private fun showImage(uri: String) {
        Timber.i("Showing image: $uri")
        player?.pause()
        playerView.visibility = View.GONE
        mirrorSurfaceView.visibility = View.GONE
        imageView.visibility = View.VISIBLE
        tvError.visibility = View.GONE
        tvStatus.text = "正在显示图片"
        tvPlaybackMeta.text = buildList {
            add("DLNA")
            add("图片")
            if (playlist.size > 1) add("${currentIndex + 1}/${playlist.size}")
        }.joinToString("  •  ")
        updateBufferingState(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    if (uri.startsWith("http://") || uri.startsWith("https://")) {
                        URL(uri).openStream().use { BitmapFactory.decodeStream(it) }
                    } else {
                        contentResolver.openInputStream(Uri.parse(uri))?.use { BitmapFactory.decodeStream(it) }
                    }
                }
                updateBufferingState(false)
                if (bitmap != null) {
                    imageView.setImageBitmap(bitmap)
                    tvPlaybackMeta.text = "DLNA  •  图片  •  ${bitmap.width}×${bitmap.height}"
                    adaptOrientationToImage(bitmap.width, bitmap.height)
                    startImageSlideShow()
                } else throw IllegalArgumentException("无法加载图片")
            } catch (e: Exception) {
                Timber.e(e, "Error showing image")
                updateBufferingState(false)
                tvStatus.text = "图片加载错误"
                tvError.text = "图片加载错误: ${e.message ?: "未知错误"}"
                tvError.visibility = View.VISIBLE
            }
        }
    }

    private fun startImageSlideShow() {
        if (playlist.count { isImageUri(it) } <= 1) return
        slideJob?.cancel()
        slideJob = lifecycleScope.launch {
            while (isActive && isCurrentImage()) {
                delay(IMAGE_SLIDE_INTERVAL_MS)
                playNextOrFinish(loopImages = true)
            }
        }
    }

    private fun stopImageSlideShow() {
        slideJob?.cancel()
        slideJob = null
        if (isCurrentImage()) tvStatus.text = "图片轮播已暂停"
    }

    private fun playNextOrFinish(loopImages: Boolean = false) {
        if (playlist.isEmpty()) return
        if (currentIndex < playlist.lastIndex) {
            currentIndex++
            playCurrent()
        } else if (loopImages) {
            currentIndex = 0
            playCurrent()
        } else {
            updateBufferingState(false)
            reportPlaybackStopped()
        }
    }

    private fun isCurrentImage(): Boolean = mediaUri?.let { isImageUri(it) } == true

    private fun isImageUri(uri: String): Boolean {
        val clean = uri.substringBefore('?').lowercase()
        return clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".gif") || clean.endsWith(".webp") || clean.endsWith(".bmp") ||
            clean.startsWith("content://") && clean.contains("image")
    }

    private fun updateBufferingState(isBuffering: Boolean) {
        progressBar.visibility = if (isBuffering) View.VISIBLE else View.GONE
        bufferingIndicator.visibility = if (isBuffering) View.VISIBLE else View.GONE
    }

    private fun updateCompactPlaybackMeta() {
        when {
            isAirPlayMirrorSession -> {
                val parts = mutableListOf("AirPlay")
                if (StreamStats.videoWidth > 0 && StreamStats.videoHeight > 0) {
                    parts += "${StreamStats.videoWidth}×${StreamStats.videoHeight}"
                }
                StreamStats.videoCodec?.takeIf { it.isNotBlank() }?.let { parts += StreamInfoTracker.formatCodec(it) }
                tvPlaybackMeta.text = parts.joinToString("  •  ")
            }
            isMiracastSession -> {
                val parts = mutableListOf("Miracast")
                if (StreamStats.videoWidth > 0 && StreamStats.videoHeight > 0) {
                    parts += "${StreamStats.videoWidth}×${StreamStats.videoHeight}"
                }
                parts += "H.264"
                tvPlaybackMeta.text = parts.joinToString("  •  ")
            }
            isWebRtcSession -> {
                val parts = mutableListOf("WebRTC")
                if (WebRtcReceiver.statsWidth > 0 && WebRtcReceiver.statsHeight > 0) {
                    parts += "${WebRtcReceiver.statsWidth}×${WebRtcReceiver.statsHeight}"
                }
                if (WebRtcReceiver.statsFrameCount > 0) {
                    parts += "${WebRtcReceiver.statsFrameCount} 帧"
                }
                tvPlaybackMeta.text = parts.joinToString("  •  ")
            }
            isCurrentImage() -> Unit
            else -> {
                val currentPlayer = player
                val format = currentPlayer?.videoFormat
                val videoSize = currentPlayer?.videoSize
                val parts = mutableListOf("DLNA")
                if (currentPlayer?.isCurrentMediaItemLive == true) parts += "LIVE"
                if (playlist.size > 1) parts += "${currentIndex + 1}/${playlist.size}"
                if ((videoSize?.width ?: 0) > 0 && (videoSize?.height ?: 0) > 0) {
                    parts += "${videoSize!!.width}×${videoSize.height}"
                }
                format?.sampleMimeType?.let { parts += StreamInfoTracker.formatCodec(it) }
                val hdr = StreamInfoTracker.formatHdr(format)
                if (hdr != "SDR" && hdr != "SDR / 未标记" && hdr != "—") parts += hdr
                format?.frameRate?.takeIf { it > 0f }?.let { parts += StreamInfoTracker.formatFps(it) }
                val bitrateBps = listOfNotNull(format?.bitrate, format?.averageBitrate, format?.peakBitrate)
                    .firstOrNull { it != Format.NO_VALUE }?.toLong() ?: 0L
                if (bitrateBps > 0) parts += StreamInfoTracker.formatBitrate(bitrateBps)
                if (bandwidthEstimateBps > 0) parts += "↓${StreamInfoTracker.formatSpeed(bandwidthEstimateBps / 8)}"
                val duration = currentPlayer?.duration?.takeIf { it > 0 } ?: 0L
                if (duration > 0) {
                    parts += "${formatTimeMs(currentPlayer?.currentPosition ?: 0L)} / ${formatTimeMs(duration)}"
                }
                tvPlaybackMeta.text = parts.joinToString("  •  ")
            }
        }
    }

    private fun toggleStreamInfo() {
        if (isStreamInfoVisible) hideStreamInfo() else showStreamInfo()
    }

    private fun showStreamInfo() {
        isStreamInfoVisible = true
        streamInfoTracker.reset()
        tvStreamInfo.text = buildStreamInfoText()
        tvStreamInfo.visibility = View.VISIBLE
        streamInfoJob?.cancel()
        streamInfoJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                tvStreamInfo.text = buildStreamInfoText()
            }
        }
    }

    private fun hideStreamInfo() {
        isStreamInfoVisible = false
        streamInfoJob?.cancel()
        streamInfoJob = null
        tvStreamInfo.visibility = View.GONE
    }

    private fun buildStreamInfoText(): String = when {
        isAirPlayMirrorSession -> buildAirPlayStreamInfo()
        isMiracastSession -> buildMiracastStreamInfo()
        isWebRtcSession -> buildWebRtcStreamInfo()
        else -> buildExoPlayerStreamInfo()
    }

    private fun buildExoPlayerStreamInfo(): String {
        val currentPlayer = player
        val format = currentPlayer?.videoFormat
        val audioFormat = currentPlayer?.audioFormat
        val videoSize = currentPlayer?.videoSize
        val bitrateBps = listOfNotNull(format?.bitrate, format?.averageBitrate, format?.peakBitrate)
            .firstOrNull { it != Format.NO_VALUE }?.toLong() ?: 0L
        val bufferedMs = ((currentPlayer?.bufferedPosition ?: 0L) - (currentPlayer?.currentPosition ?: 0L)).coerceAtLeast(0L)
        val duration = currentPlayer?.duration?.takeIf { it > 0 } ?: 0L
        val isLive = currentPlayer?.isCurrentMediaItemLive == true
        val isSeekable = currentPlayer?.isCurrentMediaItemSeekable == true
        val positionText = when {
            isLive && isSeekable && duration > 0 -> "直播 / DVR  ${formatTimeMs(currentPlayer?.currentPosition ?: 0L)} / ${formatTimeMs(duration)}"
            isLive -> "直播"
            duration > 0 -> "${formatTimeMs(currentPlayer?.currentPosition ?: 0L)} / ${formatTimeMs(duration)}"
            else -> "时长未知"
        }
        val extras = listOf(
            "来源" to "DLNA / Media",
            "HDR" to StreamInfoTracker.formatHdr(format),
            "色彩空间" to StreamInfoTracker.formatColorSpace(format),
            "色深" to StreamInfoTracker.formatBitDepth(format),
            "色彩范围" to StreamInfoTracker.formatColorRange(format),
            "音频编码" to StreamInfoTracker.formatCodec(audioFormat?.sampleMimeType),
            "音频声道" to StreamInfoTracker.formatAudioChannels(audioFormat),
            "采样率" to StreamInfoTracker.formatSampleRate(audioFormat),
            "解码器" to lastDecoderName.ifBlank { "—" },
            "解码初始化" to if (lastDecoderInitDurationMs >= 0) "${lastDecoderInitDurationMs} ms" else "—",
            "缓冲时长" to formatTimeMs(bufferedMs),
            "缓冲比例" to StreamInfoTracker.formatBufferPercent(currentPlayer?.bufferedPercentage ?: -1),
            "掉帧" to droppedVideoFrames.toString(),
            "直播" to if (isLive) "是" else "否",
            "可拖动" to if (isSeekable) "是" else "否",
            "进度" to positionText,
            "播放速度" to String.format("%.2fx", currentPlayer?.playbackParameters?.speed ?: currentSpeed)
        )
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(videoSize?.width ?: 0, videoSize?.height ?: 0),
            codec = StreamInfoTracker.formatCodec(format?.sampleMimeType),
            fps = StreamInfoTracker.formatFps(format?.frameRate ?: 0f),
            bitrate = StreamInfoTracker.formatBitrate(bitrateBps),
            speed = StreamInfoTracker.formatSpeed(bandwidthEstimateBps / 8),
            extraRows = extras
        )
    }

    private fun buildAirPlayStreamInfo(): String {
        val videoBytes = StreamStats.videoBytesTotal
        val totalBytes = videoBytes + StreamStats.audioBytesTotal
        val sample = streamInfoTracker.sample(totalBytes, StreamStats.videoFramesTotal)
        val videoShare = if (totalBytes > 0) videoBytes.toDouble() / totalBytes else 1.0
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(StreamStats.videoWidth, StreamStats.videoHeight),
            codec = StreamInfoTracker.formatCodec(StreamStats.videoCodec),
            fps = StreamInfoTracker.formatFps(sample.fps.toFloat()),
            bitrate = StreamInfoTracker.formatBitrate((sample.bitrateBps * videoShare).toLong()),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec),
            extraRows = listOf("来源" to "AirPlay")
        )
    }

    private fun buildMiracastStreamInfo(): String {
        val sample = streamInfoTracker.sample(RtpReceiver.active?.bytesReceived ?: 0L, 0L)
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(StreamStats.videoWidth, StreamStats.videoHeight),
            codec = StreamInfoTracker.formatCodec("video/avc"),
            fps = StreamInfoTracker.formatFps(0f),
            bitrate = StreamInfoTracker.formatBitrate(sample.bitrateBps),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec),
            extraRows = listOf("来源" to "Miracast")
        )
    }

    private fun buildWebRtcStreamInfo(): String {
        val sample = streamInfoTracker.sample(0L, WebRtcReceiver.statsFrameCount)
        return streamInfoLines(
            resolution = StreamInfoTracker.formatResolution(WebRtcReceiver.statsWidth, WebRtcReceiver.statsHeight),
            codec = "VP8 / H.264 (WebRTC)",
            fps = StreamInfoTracker.formatFps(sample.fps.toFloat()),
            bitrate = StreamInfoTracker.formatBitrate(sample.bitrateBps),
            speed = StreamInfoTracker.formatSpeed(sample.bytesPerSec),
            extraRows = listOf("来源" to "WebRTC")
        )
    }

    private fun streamInfoLines(
        resolution: String,
        codec: String,
        fps: String,
        bitrate: String,
        speed: String,
        extraRows: List<Pair<String, String>> = emptyList()
    ): String {
        val (displayW, displayH) = currentDisplaySize()
        val rows = mutableListOf(
            "源分辨率" to resolution,
            "显示分辨率" to StreamInfoTracker.formatResolution(displayW, displayH),
            "视频编码" to codec,
            "帧率" to fps,
            "码率" to bitrate,
            "网速" to speed
        )
        rows += extraRows
        return rows.joinToString("\n", prefix = "视频信息\n") { (label, value) ->
            StreamInfoTracker.padLabel(label) + value
        }
    }

    private fun currentDisplaySize(): Pair<Int, Int> {
        val renderView = when {
            isWebRtcSession -> webrtcSurfaceView
            isAirPlayMirrorSession || isMiracastSession -> mirrorSurfaceView
            else -> playerView.videoSurfaceView
        }
        val width = renderView?.width ?: 0
        val height = renderView?.height ?: 0
        if (width > 0 && height > 0) return width to height
        val metrics = resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun showQualityDialog() {
        val labels = arrayOf("自动", "流畅 480p", "高清 720p", "超清 1080p", "原画")
        val heights = intArrayOf(QUALITY_AUTO, 480, 720, 1080, Int.MAX_VALUE)
        val checked = heights.indexOf(qualityHeight).takeIf { it >= 0 } ?: 0
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("选择画质")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                qualityHeight = heights[which]
                applyQuality(qualityHeight)
                dialog.dismiss()
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun applyQuality(height: Int) {
        val builder = trackSelector?.buildUponParameters() ?: return
        when (height) {
            QUALITY_AUTO -> builder.clearVideoSizeConstraints()
            Int.MAX_VALUE -> builder.setMaxVideoSize(Int.MAX_VALUE, Int.MAX_VALUE)
            else -> builder.setMaxVideoSize(Int.MAX_VALUE, height)
        }
        trackSelector?.setParameters(builder)
        tvStatus.text = if (height == QUALITY_AUTO) "画质：自动" else "画质：${height}p"
        updateCompactPlaybackMeta()
    }

    private fun showSpeedDialog() {
        val labels = arrayOf("0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "2.0x")
        val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        isDialogShowing = true
        AlertDialog.Builder(this)
            .setTitle("播放速度")
            .setItems(labels) { _, which ->
                player?.setPlaybackSpeed(speeds[which])
                currentSpeed = speeds[which]
                tvStatus.text = "播放速度：${labels[which]}"
                reportPlaybackPosition()
                updateCompactPlaybackMeta()
            }
            .setOnDismissListener { isDialogShowing = false }
            .show()
    }

    private fun adaptOrientationToVideo() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun adaptOrientationToImage(width: Int, height: Int) {
        requestedOrientation = if (width >= height) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        } else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun registerControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_PAUSE)
            addAction(ACTION_STOP)
            addAction(ACTION_SEEK)
            addAction(ACTION_SET_VOLUME)
            addAction(ACTION_SET_PLAYLIST)
            addAction(ACTION_SET_SPEED)
            addAction(ACTION_SET_QUALITY_URL)
        }
        ContextCompat.registerReceiver(this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStart() {
        super.onStart()
        if (isCurrentImage()) startImageSlideShow() else {
            player?.play()
            if (player != null) startProgressUpdates()
        }
        if (isStreamInfoVisible) showStreamInfo()
    }

    override fun onStop() {
        super.onStop()
        if (isCurrentImage()) stopImageSlideShow() else player?.pause()
        stopProgressUpdates()
        streamInfoJob?.cancel()
        streamInfoJob = null
        reportPlaybackPosition()
    }

    private fun stopPlayback() {
        stopImageSlideShow()
        stopProgressUpdates()
        stopWebRtcPlayback()
        mirrorAspectJob?.cancel()
        mirrorAspectJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        player?.stop()
        reportPlaybackStopped()
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressUpdateJob = lifecycleScope.launch {
            while (isActive) {
                delay(1000)
                reportPlaybackPosition()
                updateCompactPlaybackMeta()
            }
        }
    }

    private fun stopProgressUpdates() {
        progressUpdateJob?.cancel()
        progressUpdateJob = null
    }

    private fun reportPlaybackPosition() {
        val currentPlayer = player ?: return
        val position = currentPlayer.currentPosition
        val duration = currentPlayer.duration.takeIf { it > 0 } ?: 0L
        val intent = Intent("com.weekd.miracastreceiver.ACTION_UPDATE_POSITION").apply {
            putExtra("position", position)
            putExtra("duration", duration)
            putExtra("is_playing", currentPlayer.isPlaying)
            putExtra("is_live", currentPlayer.isCurrentMediaItemLive)
            putExtra("is_seekable", currentPlayer.isCurrentMediaItemSeekable)
            putExtra("volume", (currentPlayer.volume * 100).toInt().coerceIn(0, 100))
            putExtra("speed", currentPlayer.playbackParameters.speed)
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }

    private fun reportPlaybackStopped() {
        val intent = Intent(ACTION_PLAYBACK_STOPPED).apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(controlReceiver)
        } catch (e: Exception) {
            Timber.e(e, "Error unregistering receiver")
        }
        stopImageSlideShow()
        stopWebRtcPlayback()
        webrtcMetaJob?.cancel()
        webrtcMetaJob = null
        mirrorAspectJob?.cancel()
        mirrorAspectJob = null
        streamInfoJob?.cancel()
        streamInfoJob = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        mirrorSurface = null
        webrtcSurfaceView?.let { renderer ->
            if (webrtcRendererInitialized) {
                runCatching { renderer.release() }
                webrtcRendererInitialized = false
            }
        }
        webrtcSurfaceView = null
        player?.release()
        player = null
        trackSelector = null
        cachedVideoMediaItems = emptyList()
        cachedVideoIndexByPlaylistIndex = IntArray(0)
        cachedPlaylistSignature = emptyList()
        reportPlaybackStopped()
        Timber.i("PlayerActivity destroyed")
    }
}
