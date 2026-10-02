package com.weekd.miracastreceiver.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
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
import androidx.media3.exoplayer.DefaultLoadControl
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

        // Fast-start profile for LAN/WebUI playback. Keep enough headroom for bursty HLS/DASH,
        // but do not make the viewer wait for the large VOD-oriented default startup buffer.
        private const val MIN_BUFFER_MS = 1_500
        private const val MAX_BUFFER_MS = 15_000
        private const val START_BUFFER_MS = 350
        private const val REBUFFER_MS = 900

        // Live HLS/DASH should start close to the live edge. These are deliberately conservative
        // enough for home Wi-Fi while avoiding multi-second latency inherited from stream defaults.
        private const val LIVE_TARGET_OFFSET_MS = 1_500L
        private const val LIVE_MIN_OFFSET_MS = 750L
        private const val LIVE_MAX_OFFSET_MS = 4_000L
        private const val LIVE_MIN_SPEED = 0.97f
        private const val LIVE_MAX_SPEED = 1.03f
    }

    private lateinit var playerView: PlayerView
    private lateinit var tvTitle: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvError: TextView
    private lateinit var bufferingIndicator: ProgressBar

    private var player: ExoPlayer? = null
    private var configuredHeaders: Map<String, String> = emptyMap()
    private var currentUrl = ""
    private var currentTitle = ""
    private var requestHeaders: Map<String, String> = emptyMap()
    private var retryAttempt = 0
    private var retryJob: Job? = null
    private var progressJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var waitingForNetwork = false
    private var loadStartedAtMs = 0L

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlayerActivity.ACTION_PLAY -> player?.play()
                PlayerActivity.ACTION_PAUSE -> player?.pause()
                PlayerActivity.ACTION_STOP -> { player?.stop(); finish() }
                PlayerActivity.ACTION_SEEK -> player?.seekTo(intent.getLongExtra(PlayerActivity.EXTRA_SEEK_POSITION, 0L).coerceAtLeast(0L))
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
            showFatalError(getString(R.string.url_player_invalid_request))
            return
        }
        currentUrl = url
        currentTitle = intent.getStringExtra(EXTRA_TITLE)?.takeIf { it.isNotBlank() }
            ?: getString(R.string.url_player_default_title)
        requestHeaders = parseHeaders(intent.getStringExtra(EXTRA_HEADERS_JSON))
        retryAttempt = 0
        retryJob?.cancel()
        waitingForNetwork = false
        startMedia()
    }

    private fun startMedia() {
        val exo = ensurePlayer()
        tvTitle.text = currentTitle
        tvError.visibility = View.GONE

        // Some senders repeat the same SetAVTransportURI/open-url while playback is already being
        // prepared. Treat that as an idempotent update instead of throwing away buffered data.
        val activeUrl = exo.currentMediaItem?.localConfiguration?.uri?.toString()
        if (activeUrl == currentUrl && exo.playbackState != Player.STATE_IDLE) {
            Timber.d("URL playback request already active; keeping buffer/decoder: $currentUrl")
            exo.playWhenReady = true
            RuntimeState.updatePlayback {
                it.copy(title = currentTitle, uri = currentUrl, source = "WEB_URL", error = "")
            }
            startProgressUpdates()
            return
        }

        bufferingIndicator.visibility = View.VISIBLE
        loadStartedAtMs = SystemClock.elapsedRealtime()

        // setMediaItem(resetPosition=true) replaces the timeline without stop()/clearMediaItems().
        // Keeping the ExoPlayer instance alive lets Media3 reuse its playback thread, renderers and
        // decoder resources when the next URL is compatible, which reduces channel-switch latency.
        exo.setMediaItem(createMediaItem(currentUrl), true)
        exo.playWhenReady = true
        exo.prepare()

        RuntimeState.updatePlayback {
            it.copy(state = "BUFFERING", title = currentTitle, uri = currentUrl, source = "WEB_URL", error = "", retryAttempt = 0)
        }
        startProgressUpdates()
    }

    private fun ensurePlayer(): ExoPlayer {
        val existing = player
        if (existing != null && configuredHeaders == requestHeaders) return existing

        releasePlayer()
        configuredHeaders = requestHeaders.toMap()

        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(12_000)
            .setUserAgent(requestHeaders["User-Agent"] ?: "MiracastReceiver/${Build.VERSION.RELEASE}")
            .setDefaultRequestProperties(requestHeaders.filterKeys { !it.equals("User-Agent", true) })
        val dataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLiveTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
            .setLiveMinOffsetMs(LIVE_MIN_OFFSET_MS)
            .setLiveMaxOffsetMs(LIVE_MAX_OFFSET_MS)
            .setLiveMinSpeed(LIVE_MIN_SPEED)
            .setLiveMaxSpeed(LIVE_MAX_SPEED)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val created = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()
        created.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        bufferingIndicator.visibility = View.VISIBLE
                        tvStatus.setText(R.string.buffering)
                        updateSnapshot("BUFFERING")
                    }
                    Player.STATE_READY -> {
                        retryAttempt = 0
                        waitingForNetwork = false
                        bufferingIndicator.visibility = View.GONE
                        tvError.visibility = View.GONE
                        tvStatus.setText(R.string.playing)
                        val startupMs = if (loadStartedAtMs > 0L) SystemClock.elapsedRealtime() - loadStartedAtMs else -1L
                        if (startupMs >= 0L) Timber.i("URL playback ready in ${startupMs}ms: $currentUrl")
                        loadStartedAtMs = 0L
                        updateSnapshot(if (created.isPlaying) "PLAYING" else "READY")
                    }
                    Player.STATE_ENDED -> {
                        bufferingIndicator.visibility = View.GONE
                        tvStatus.setText(R.string.playback_finished)
                        updateSnapshot("ENDED")
                    }
                    Player.STATE_IDLE -> updateSnapshot("IDLE")
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (created.playbackState == Player.STATE_READY) {
                    tvStatus.setText(if (isPlaying) R.string.playing else R.string.paused)
                    updateSnapshot(if (isPlaying) "PLAYING" else "PAUSED")
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.w(error, "URL playback error for $currentUrl")
                bufferingIndicator.visibility = View.GONE
                RuntimeState.playbackError = error.message.orEmpty()
                if (isRetryable(error) && retryAttempt < MAX_RETRY_ATTEMPTS) {
                    scheduleRetry(error)
                } else {
                    showFatalError(error.message ?: getString(R.string.error_unknown))
                }
            }
        })
        player = created
        playerView.player = created
        return created
    }

    private fun createMediaItem(url: String): MediaItem {
        val clean = url.substringBefore('?').substringBefore('#').lowercase()
        val builder = MediaItem.Builder().setUri(url)
        val adaptive = when {
            clean.endsWith(".m3u8") -> {
                builder.setMimeType(MimeTypes.APPLICATION_M3U8)
                true
            }
            clean.endsWith(".mpd") -> {
                builder.setMimeType(MimeTypes.APPLICATION_MPD)
                true
            }
            else -> false
        }
        if (adaptive) {
            builder.setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(LIVE_TARGET_OFFSET_MS)
                    .setMinOffsetMs(LIVE_MIN_OFFSET_MS)
                    .setMaxOffsetMs(LIVE_MAX_OFFSET_MS)
                    .setMinPlaybackSpeed(LIVE_MIN_SPEED)
                    .setMaxPlaybackSpeed(LIVE_MAX_SPEED)
                    .build()
            )
        }
        return builder.build()
    }

    private fun scheduleRetry(error: PlaybackException) {
        retryJob?.cancel()
        val index = retryAttempt.coerceIn(0, RETRY_DELAYS_MS.lastIndex)
        val delayMs = RETRY_DELAYS_MS[index]
        retryAttempt++
        waitingForNetwork = true
        RuntimeState.updatePlayback { it.copy(state = "RETRYING", error = error.message.orEmpty(), retryAttempt = retryAttempt) }
        tvStatus.text = getString(R.string.url_player_retrying, delayMs / 1000, retryAttempt, MAX_RETRY_ATTEMPTS)
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
        loadStartedAtMs = SystemClock.elapsedRealtime()
        tvStatus.setText(R.string.url_player_network_recovered)
        exo.playWhenReady = true
        exo.prepare()
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
                if (waitingForNetwork || RuntimeState.playbackState == "RETRYING") runOnUiThread { retryNow() }
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
        tvStatus.setText(R.string.error_playback)
        tvError.text = getString(R.string.playback_error_detail, message)
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
        configuredHeaders = emptyMap()
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
