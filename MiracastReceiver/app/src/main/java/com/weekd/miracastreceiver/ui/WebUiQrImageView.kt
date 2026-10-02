package com.weekd.miracastreceiver.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.widget.AppCompatImageView
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.NetworkUtils
import com.weekd.miracastreceiver.utils.QrCodeUtils
import com.weekd.miracastreceiver.web.RuntimeState

/**
 * TV-side QR code that always points at the WebUI's effective runtime port.
 * The API token is carried in the URL fragment so it is never sent as an HTTP query parameter.
 */
class WebUiQrImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private var lastPayload: String = ""

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshQr()
            handler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.removeCallbacks(refreshRunnable)
        refreshQr()
        handler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(refreshRunnable)
        super.onDetachedFromWindow()
    }

    private fun refreshQr() {
        if (!AppSettings.isWebUiEnabled(context)) {
            visibility = View.GONE
            setImageDrawable(null)
            lastPayload = ""
            return
        }

        val ip = NetworkUtils.getLocalIpAddress()
        if (ip.isNullOrBlank()) {
            visibility = View.INVISIBLE
            return
        }

        val port = RuntimeState.webUiPort.takeIf { it in 1024..65535 }
            ?: AppSettings.getWebUiLastBoundPort(context)
            ?: AppSettings.getWebUiPort(context)
        val baseUrl = "http://$ip:$port/"
        val payload = if (AppSettings.isWebUiAuthRequired(context)) {
            "$baseUrl#token=${AppSettings.getOrCreateWebUiToken(context)}"
        } else {
            baseUrl
        }

        if (payload == lastPayload && drawable != null) {
            visibility = View.VISIBLE
            return
        }

        QrCodeUtils.create(payload, QR_SIZE_PX)?.let { bitmap ->
            setImageBitmap(bitmap)
            contentDescription = "扫码打开 MiracastReceiver WebUI"
            visibility = View.VISIBLE
            lastPayload = payload
        } ?: run {
            visibility = View.INVISIBLE
        }
    }

    companion object {
        private const val QR_SIZE_PX = 420
        private const val REFRESH_INTERVAL_MS = 1_500L
    }
}
