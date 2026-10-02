package com.weekd.miracastreceiver.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.NetworkUtils
import com.weekd.miracastreceiver.web.RuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Single-screen receiver dashboard. No settings controls live here. */
class HomeFragment : Fragment(), MainActivity.TvPage {
    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectionCode: TextView
    private lateinit var tvWebUiUrl: TextView
    private lateinit var tvWebUiPortStatus: TextView
    private lateinit var tvWebUiToken: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvAirPlay: TextView
    private lateinit var tvDlna: TextView
    private lateinit var tvMiracast: TextView
    private lateinit var tvWebUi: TextView
    private lateinit var tvPlayback: TextView
    private var refreshJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        deviceInfoProvider = DeviceInfoProvider(requireContext())
        tvDeviceName = view.findViewById(R.id.tv_device_name)
        tvDeviceIp = view.findViewById(R.id.tv_device_ip)
        tvConnectionCode = view.findViewById(R.id.tv_connection_code)
        tvWebUiUrl = view.findViewById(R.id.tv_webui_url)
        tvWebUiPortStatus = view.findViewById(R.id.tv_webui_port_status)
        tvWebUiToken = view.findViewById(R.id.tv_webui_token)
        tvStatus = view.findViewById(R.id.tv_status)
        tvAirPlay = view.findViewById(R.id.tv_home_airplay)
        tvDlna = view.findViewById(R.id.tv_home_dlna)
        tvMiracast = view.findViewById(R.id.tv_home_miracast)
        tvWebUi = view.findViewById(R.id.tv_home_webui)
        tvPlayback = view.findViewById(R.id.tv_home_playback)
        refreshNow()
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                refreshNow()
                delay(2_000)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private fun refreshNow() {
        if (!isAdded) return
        val context = requireContext()
        val ip = NetworkUtils.getLocalIpAddress()
        val networkReady = NetworkUtils.isNetworkAvailable(context)
        tvDeviceName.text = deviceInfoProvider.getDeviceName()
        tvDeviceIp.text = getString(R.string.device_ip, ip ?: getString(R.string.waiting_network))
        tvConnectionCode.text = getString(R.string.connection_code, AppSettings.getOrCreateConnectionCode(context))
        tvStatus.setText(if (networkReady) R.string.waiting_connection else R.string.waiting_network)

        val webEnabled = AppSettings.isWebUiEnabled(context)
        val preferredPort = AppSettings.getWebUiPort(context)
        val runtimePort = RuntimeState.webUiPort.takeIf { it in 1024..65535 }
            ?: AppSettings.getWebUiLastBoundPort(context)
            ?: preferredPort
        val fallback = runtimePort != preferredPort
        tvWebUiUrl.text = when {
            !webEnabled -> getString(R.string.webui_disabled)
            ip == null -> getString(R.string.webui_waiting_network)
            else -> "http://$ip:$runtimePort"
        }
        tvWebUiPortStatus.text = when {
            !webEnabled -> getString(R.string.webui_service_disabled)
            fallback -> getString(R.string.webui_port_fallback, runtimePort, preferredPort)
            else -> getString(R.string.webui_port_preferred, runtimePort)
        }
        tvWebUiPortStatus.setTextColor(ContextCompat.getColor(context, if (fallback) R.color.warning else R.color.text_secondary))
        tvWebUiToken.text = if (!AppSettings.isWebUiAuthRequired(context)) {
            getString(R.string.webui_token_auth_off)
        } else {
            val token = AppSettings.getOrCreateWebUiToken(context)
            getString(R.string.webui_token_embedded, token.take(8), token.takeLast(6))
        }

        renderProtocol(tvAirPlay, AppSettings.isAirPlayEnabled(context))
        renderProtocol(tvDlna, AppSettings.isDlnaEnabled(context))
        renderProtocol(tvMiracast, AppSettings.isMiracastEnabled(context))
        renderProtocol(tvWebUi, webEnabled)
        val playback = RuntimeState.playbackSnapshot()
        tvPlayback.text = getString(R.string.home_playback_state, playback.state)
    }

    private fun renderProtocol(view: TextView, enabled: Boolean) {
        view.text = getString(if (enabled) R.string.home_enabled else R.string.home_disabled)
        view.setTextColor(ContextCompat.getColor(requireContext(), if (enabled) R.color.success else R.color.text_muted))
    }
}
