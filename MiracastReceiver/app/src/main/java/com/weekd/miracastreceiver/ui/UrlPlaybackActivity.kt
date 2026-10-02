package com.weekd.miracastreceiver.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.web.RuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import timber.log.Timber

/** Media3 player used for authenticated WebUI HTTP/HTTPS URL pushes. */
class UrlPlaybackActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url_playback_url"
        const val EXTRA_TITLE = "url_playback_title"
        const val EXTRA_HEADERS_JSON = "url_playback_headers_json"
        private const val MAX_RETRY_ATTEMPTS = 5
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L)
    }

    private lateinit var playerView: PlayerView
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvError: TextView
    private lateinit var bufferingIndicator: ProgressBar

    private var player: ExoPlayer? = null
    private var currentUrl = ""
    private var currentTitle = ""
    private var requestHeaders: Map<String, String> = emptyMap()
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var progressJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var waitingForNetwork = false

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlayerActivity.ACTION_PLAY -> player?.play()
                PlayerActivity.ACTION_PAUSE -> player?.pause()
                PlayerActivity.ACTION_STOP -> {
                    player?.stop()
                    finish()
                }
                PlayerActivity.ACTION_SEEK -> player?.seekTo(
                    intent.getLongExtra(PlayerActivity.EXTRA_SEEK_POSITION, 0L).coerceAtLeast(0L)
                )
                PlayerActivity.ACTION_SET_VOLUME -> {
                    val volume = intent.getIntExtra(PlayerActivity.EXTRA_VOLUME, 100).coerceIn(0, 100)
                    player?.volume = volume / 100f
                    RuntimeState.playbackVolume = volume
                }
                PlayerActivity.ACTION_SET_SPEED -> {
                    val speed = intent.getFloatExtra(PlayerActivity.EXTRA_SPEED, 1f).coerceIn(0.25f, 4f)
                    player?.setPlaybackSpeed(speed)
                    RuntimeState.playbackSpeed = speed
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        playerView = findViewById(R.id.player_view)
        tvTitle = findViewById(R.id.tv_title)
        tvStatus = findViewById(R.id.tv_status)
        tvError = findViewById(R.id.tv_error)
        bufferingIndicator = findViewById(R.id.buffering_indicator)
        findViewById<View>(R.id.airplay_mirror_surface).visibility = View.GONE
        findViewById<View>(R.id.image_view).visibility = View.GONE
        playerView.visibility = View.VISIBLE

        registerControls()
        registerNetworkRecovery()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)?.trim().orEmpty()
        if (url.isEmpty()) {
            showFatalError("缺少媒体 URL")
            return
        }
        currentUrl = url
        currentTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() } ?: url
        requestHeaders = parseHeaders(intent.getStringExtra(EXTRA_HEADERS_JSON))
        retryAttempt = 0
        retryJob?.cancel()
        waitingForNetwork = false
        startMedia()
    }

    private fun startMedia() {
        releasePlayer()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(15_000)
            .setUserAgent(requestHeaders["User-Agent"] ?: "MiracastReceiver/${Build.VERSION.RELEASE}")
            .setDefaultRequestProperties(requestHeaders.filterKeys { !it.equals("User-Agent", true) })
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
            .also { exo ->
                exo.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_BUFFERING -> {
                                bufferingIndicator.visibility = View.VISIBLE
                                tvStatus.text = if (retryAttempt > 0) "重试后正在缓冲…" else "正在缓冲…"
                                updateSnapshot("BUFFERING")
                            }
                            Player.STATE_READY -> {
                                retryAttempt = 0
                                waitingForNetwork = false
                                bufferingIndicator.visibility = View.GONE
                                tvError.visibility = View.GONE
                                tvStatus.text = "正在播放"
                                updateSnapshot(if (exo.isPlaying) "PLAYING" else "READY")
                            }
                            Player.STATE_ENDED -> {
                                bufferingIndicator.visibility = View.GONE
                                tvStatus.text = "播放完成"
                                updateSnapshot("ENDED")
                            }
                            Player.STATE_IDLE -> updateSnapshot("IDLE")
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (exo.playbackState == Player.STATE_READY) {
                            tvStatus.text = if (isPlaying) "正在播放" else "已暂停"
                            updateSnapshot(if (isPlaying) "PLAYING" else "PAUSED")
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Timber.w(error, "URL playback error for $currentUrl")
                        bufferingIndicator.visibility = View.GONE
                        val retryable = isRetryable(error)
                        RuntimeState.playbackError = error.message.orEmpty()
                        if (retryable && retryAttempt < MAX_RETRY_ATTEMPTS) {
                            scheduleRetry(error)
                        } else {
                            showFatalError(error.message ?: "播放失败")
                        }
                    }
                })
                playerView.player = exo
                exo.setMediaItem(createMediaItem(currentUrl))
                exo.prepare()
                exo.playWhenReady = true
            }

        tvTitle.text = currentTitle
        tvError.visibility = View.GONE
        RuntimeState.updatePlayback {
            it.copy(
                state = "BUFFERING",
                title = currentTitle,
                uri = currentUrl,
                source = "WEB_URL",
                error = "",
                retryAttempt = 0
            )
        }
        startProgressUpdates()
    }

    private fun createMediaItem(url: String): MediaItem {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        val builder = MediaItem.Builder().setUri(url)
        when {
            clean.endsWith(".m3u8") -> builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            clean.endsWith(".mpd") -> builder.setMimeType(MimeTypes.APPLICATION_MPD)
        }
        return builder.build()
    }

    private fun scheduleRetry(error: PlaybackException) {
        retryJob?.cancel()
        val index = retryAttempt.coerceIn(0, RETRY_DELAYS_MS.lastIndex)
        val delayMs = RETRY_DELAYS_MS[index]
        retryAttempt++
        waitingForNetwork = true
        RuntimeState.updatePlayback {
            it.copy(state = "RETRYING", error = error.message.orEmpty(), retryAttempt = retryAttempt)
        }
        tvStatus.text = "网络异常，${delayMs / 1000}s 后重试 ($retryAttempt/$MAX_RETRY_ATTEMPTS)"
        retryJob = lifecycleScope.launch {
            delay(delayMs)
            retryNow()
        }
    }

    private fun retryNow() {
        retryJob?.cancel()
        retryJob = null
        val exo = player ?: return
        waitingForNetwork = false
        tvStatus.text = "正在重新连接…"
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun isRetryable(error: PlaybackException): Boolean {
        var cause: Throwable? = error
        while (cause != null) {
            if (cause is HttpDataSource.InvalidResponseCodeException) {
                val code = cause.responseCode
                return code == 408 || code == 429 || code >= 500
            }
            cause = cause.cause
        }
        return error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ||
            error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED
    }

    private fun registerNetworkRecovery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (waitingForNetwork || RuntimeState.playbackState == "RETRYING") {
                    runOnUiThread { retryNow() }
                }
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { networkCallback = callback }
            .onFailure { Timber.w(it, "Unable to register URL playback network callback") }
    }

    private fun registerControls() {
        val filter = IntentFilter().apply {
            addAction(PlayerActivity.ACTION_PLAY)
            addAction(PlayerActivity.ACTION_PAUSE)
            addAction(PlayerActivity.ACTION_STOP)
            addAction(PlayerActivity.ACTION_SEEK)
            addAction(PlayerActivity.ACTION_SET_VOLUME)
            addAction(PlayerActivity.ACTION_SET_SPEED)
        }
        ContextCompat.registerReceiver(this, controlReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = lifecycleScope.launch {
            while (isActive) {
                delay(1_000)
                updateSnapshot()
            }
        }
    }

    private fun updateSnapshot(forcedState: String? = null) {
        val exo = player
        RuntimeState.updatePlayback { old ->
            old.copy(
                state = forcedState ?: old.state,
                title = currentTitle,
                uri = currentUrl,
                positionMs = exo?.currentPosition?.coerceAtLeast(0L) ?: old.positionMs,
                durationMs = exo?.duration?.takeIf { it > 0 } ?: 0L,
                speed = exo?.playbackParameters?.speed ?: old.speed,
                volume = ((exo?.volume ?: 1f) * 100).toInt().coerceIn(0, 100),
                source = "WEB_URL",
                retryAttempt = retryAttempt
            )
        }
    }

    private fun showFatalError(message: String) {
        tvStatus.text = "播放错误"
        tvError.text = "播放错误: $message"
        tvError.visibility = View.VISIBLE
        bufferingIndicator.visibility = View.GONE
        RuntimeState.updatePlayback { it.copy(state = "ERROR", error = message, retryAttempt = retryAttempt) }
    }

    private fun parseHeaders(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.optString(it) }
        }.getOrElse { emptyMap() }
    }

    private fun releasePlayer() {
        progressJob?.cancel()
        progressJob = null
        playerView.player = null
        player?.release()
        player = null
    }

    override fun onDestroy() {
        retryJob?.cancel()
        retryJob = null
        releasePlayer()
        networkCallback?.let { callback ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
            }
        }
        networkCallback = null
        runCatching { unregisterReceiver(controlReceiver) }
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (RuntimeState.playbackSource == "WEB_URL") RuntimeState.resetPlayback()
        super.onDestroy()
    }
}
