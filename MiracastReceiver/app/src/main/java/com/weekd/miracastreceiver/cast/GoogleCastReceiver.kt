package com.weekd.miracastreceiver.cast

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media.MediaMetadataCompat
import androidx.media.session.MediaSessionCompat
import androidx.media.session.PlaybackStateCompat
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.tv.CastReceiverContext
import com.google.android.gms.cast.tv.media.MediaLoadCommandCallback
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.weekd.miracastreceiver.BuildConfig
import com.weekd.miracastreceiver.ui.PlayerActivity
import com.weekd.miracastreceiver.ui.UrlPlaybackActivity
import com.weekd.miracastreceiver.web.RuntimeState
import timber.log.Timber

/**
 * Official Google Cast Connect integration.
 *
 * Cast Connect still requires a Cast App ID registered in the Google Cast SDK Developer Console
 * and an Android TV / Google TV device with the Google Cast receiver infrastructure. It does not
 * emulate a certified Chromecast on devices that do not ship Google's Cast implementation.
 */
object GoogleCastReceiver {
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
            val context = CastReceiverContext.getInstance()

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
            context.mediaManager.setSessionCompatToken(session.sessionToken)
            context.mediaManager.setMediaLoadCommandCallback(CastLoadCallback())

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
                Tasks.forException(error)
            }
        }
    }
}
