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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Single-screen receiver dashboard. No settings controls live here. */
class HomeFragment : Fragment(), MainActivity.TvPage {
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
    private var lastSnapshot: DashboardSnapshot? = null
    private lateinit var deviceName: String
    private lateinit var connectionCode: String

    private data class DashboardSnapshot(
        val addresses: NetworkUtils.LanAddresses,
        val networkReady: Boolean,
        val webEnabled: Boolean,
        val preferredPort: Int,
        val runtimePort: Int,
        val authRequired: Boolean,
        val token: String,
        val airPlayEnabled: Boolean,
        val dlnaEnabled: Boolean,
        val miracastEnabled: Boolean,
        val playbackState: String
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_home, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        val context = requireContext()
        deviceName = DeviceInfoProvider(context).getDeviceName()
        connectionCode = AppSettings.getOrCreateConnectionCode(context)
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
        tvDeviceName.text = deviceName
        tvConnectionCode.text = getString(R.string.connection_code, connectionCode)
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val snapshot = withContext(Dispatchers.Default) { collectSnapshot() }
                if (snapshot != lastSnapshot) {
                    render(snapshot)
                    lastSnapshot = snapshot
                }
                delay(2_000)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private fun collectSnapshot(): DashboardSnapshot {
        val context = requireContext().applicationContext
        val preferredPort = AppSettings.getWebUiPort(context)
        val authRequired = AppSettings.isWebUiAuthRequired(context)
        return DashboardSnapshot(
            addresses = NetworkUtils.getLanAddresses(),
            networkReady = NetworkUtils.isNetworkAvailable(context),
            webEnabled = AppSettings.isWebUiEnabled(context),
            preferredPort = preferredPort,
            runtimePort = RuntimeState.webUiPort.takeIf { it in 1024..65535 }
                ?: AppSettings.getWebUiLastBoundPort(context)
                ?: preferredPort,
            authRequired = authRequired,
            token = if (authRequired) AppSettings.getOrCreateWebUiToken(context) else "",
            airPlayEnabled = AppSettings.isAirPlayEnabled(context),
            dlnaEnabled = AppSettings.isDlnaEnabled(context),
            miracastEnabled = AppSettings.isMiracastEnabled(context),
            playbackState = RuntimeState.playbackSnapshot().state
        )
    }

    private fun render(snapshot: DashboardSnapshot) {
        if (!isAdded) return
        val context = requireContext()
        val addressText = buildList {
            snapshot.addresses.ipv4?.let { add("IPv4 $it") }
            snapshot.addresses.ipv6?.let { add("IPv6 $it") }
        }.joinToString("  •  ").ifBlank { getString(R.string.waiting_network) }
        tvDeviceIp.text = getString(R.string.device_ip, addressText)
        tvStatus.setText(if (snapshot.networkReady) R.string.waiting_connection else R.string.waiting_network)
        val fallback = snapshot.runtimePort != snapshot.preferredPort
        val preferredAddress = snapshot.addresses.preferred
        tvWebUiUrl.text = when {
            !snapshot.webEnabled -> getString(R.string.webui_disabled)
            preferredAddress == null -> getString(R.string.webui_waiting_network)
            else -> NetworkUtils.buildHttpUrl(preferredAddress, snapshot.runtimePort, "/")
        }
        tvWebUiPortStatus.text = when {
            !snapshot.webEnabled -> getString(R.string.webui_service_disabled)
            fallback -> getString(R.string.webui_port_fallback, snapshot.runtimePort, snapshot.preferredPort)
            else -> getString(R.string.webui_port_preferred, snapshot.runtimePort)
        }
        tvWebUiPortStatus.setTextColor(ContextCompat.getColor(context, if (fallback) R.color.warning else R.color.text_secondary))
        tvWebUiToken.text = if (!snapshot.authRequired) getString(R.string.webui_token_auth_off)
        else getString(R.string.webui_token_embedded, snapshot.token.take(8), snapshot.token.takeLast(6))
        renderProtocol(tvAirPlay, snapshot.airPlayEnabled)
        renderProtocol(tvDlna, snapshot.dlnaEnabled)
        renderProtocol(tvMiracast, snapshot.miracastEnabled)
        renderProtocol(tvWebUi, snapshot.webEnabled)
        tvPlayback.text = getString(R.string.home_playback_state, snapshot.playbackState)
    }

    private fun renderProtocol(view: TextView, enabled: Boolean) {
        val expected = getString(if (enabled) R.string.home_enabled else R.string.home_disabled)
        if (view.text != expected) view.text = expected
        val color = ContextCompat.getColor(requireContext(), if (enabled) R.color.success else R.color.text_muted)
        if (view.currentTextColor != color) view.setTextColor(color)
    }
}
