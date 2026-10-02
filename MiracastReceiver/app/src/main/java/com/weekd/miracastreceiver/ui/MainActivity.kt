package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.util.PrivilegedAccess
import com.weekd.miracastreceiver.utils.NetworkUtils
import com.weekd.miracastreceiver.web.RuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

/** Android TV 10-foot status/configuration surface; receiver work stays in the background service. */
class MainActivity : AppCompatActivity() {

    private lateinit var deviceInfoProvider: DeviceInfoProvider
    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectionCode: TextView
    private lateinit var tvWebUiUrl: TextView
    private lateinit var tvWebUiToken: TextView
    private lateinit var tvWebUiPortStatus: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvBootStatus: TextView
    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var switchAirPlay: SwitchCompat
    private lateinit var switchDlna: SwitchCompat
    private lateinit var switchMiracast: SwitchCompat
    private lateinit var switchWebUi: SwitchCompat
    private lateinit var btnOptimizeBackground: Button
    private lateinit var btnRestartReceiver: Button

    private var wifiPermissionPending = false
    private var overlayPromptShown = false
    private var privilegedSetupAttempted = false
    private var suppressSettingCallbacks = false
    private var settingsApplyJob: Job? = null
    private val backPressExitGate = BackPressExitGate()

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PrivilegedAccess.SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        runOnUiThread {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                toast(R.string.shizuku_granted_optimizing)
                performBackgroundOptimization(userInitiated = true)
            } else {
                toast(R.string.shizuku_denied)
                refreshPrivilegeStatus()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001
        private const val FOCUS_SCALE = 1.035f
        private const val FOCUS_ANIMATION_MS = 110L
        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
                .onFailure { Timber.w(it, "Unable to register Shizuku permission listener") }
        }

        deviceInfoProvider = DeviceInfoProvider(this)
        initViews()
        if (AppSettings.isMiracastEnabled(this)) requestWifiDirectPermissions()
        startCastService()
        updateStatus()
        performBackgroundOptimization(userInitiated = false)
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvConnectionCode = findViewById(R.id.tv_connection_code)
        tvWebUiUrl = findViewById(R.id.tv_webui_url)
        tvWebUiToken = findViewById(R.id.tv_webui_token)
        tvWebUiPortStatus = findViewById(R.id.tv_webui_port_status)
        tvStatus = findViewById(R.id.tv_status)
        tvRootStatus = findViewById(R.id.tv_root_status)
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvBootStatus = findViewById(R.id.tv_boot_status)
        switchAutoStart = findViewById(R.id.switch_auto_start)
        switchAirPlay = findViewById(R.id.switch_airplay)
        switchDlna = findViewById(R.id.switch_dlna)
        switchMiracast = findViewById(R.id.switch_miracast)
        switchWebUi = findViewById(R.id.switch_webui)
        btnOptimizeBackground = findViewById(R.id.btn_optimize_background)
        btnRestartReceiver = findViewById(R.id.btn_restart_receiver)

        syncSwitchesFromSettings()

        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingCallbacks) return@setOnCheckedChangeListener
            AppSettings.setAutoStartOnBoot(this, checked)
            syncMagiskBootIntegration(checked)
        }
        switchAirPlay.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingCallbacks) return@setOnCheckedChangeListener
            AppSettings.setAirPlayEnabled(this, checked)
            scheduleReceiverReload("AirPlay")
        }
        switchDlna.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingCallbacks) return@setOnCheckedChangeListener
            AppSettings.setDlnaEnabled(this, checked)
            scheduleReceiverReload("DLNA")
        }
        switchMiracast.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingCallbacks) return@setOnCheckedChangeListener
            AppSettings.setMiracastEnabled(this, checked)
            if (checked) requestWifiDirectPermissions()
            scheduleReceiverReload("Miracast")
        }
        switchWebUi.setOnCheckedChangeListener { _, checked ->
            if (suppressSettingCallbacks) return@setOnCheckedChangeListener
            AppSettings.setWebUiEnabled(this, checked)
            updateWebUiInfo()
            scheduleReceiverReload("WebUI")
        }

        btnOptimizeBackground.setOnClickListener { performBackgroundOptimization(userInitiated = true) }
        btnRestartReceiver.setOnClickListener { restartReceiverService(showToast = true) }

        setupTvRemoteNavigation()
        updateIdentityInfo()
        switchAutoStart.post { switchAutoStart.requestFocus() }
    }

    /**
     * Keep D-pad navigation deterministic on TV launchers. The focus chain wraps at both ends,
     * focused controls get a small scale cue, and the active row is always scrolled into view.
     */
    private fun setupTvRemoteNavigation() {
        val controls = listOf<View>(
            switchAutoStart,
            switchAirPlay,
            switchDlna,
            switchMiracast,
            switchWebUi,
            btnOptimizeBackground,
            btnRestartReceiver
        )
        controls.forEachIndexed { index, view ->
            val previous = controls[(index - 1 + controls.size) % controls.size]
            val next = controls[(index + 1) % controls.size]
            view.nextFocusUpId = previous.id
            view.nextFocusDownId = next.id
            view.setOnFocusChangeListener { target, hasFocus ->
                target.animate().cancel()
                val scale = if (hasFocus) FOCUS_SCALE else 1f
                target.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(FOCUS_ANIMATION_MS)
                    .start()
                if (hasFocus) {
                    target.post {
                        val rect = Rect(0, 0, target.width, target.height)
                        target.requestRectangleOnScreen(rect, true)
                    }
                }
            }
        }
    }

    private fun syncSwitchesFromSettings() {
        suppressSettingCallbacks = true
        switchAutoStart.isChecked = AppSettings.isAutoStartOnBoot(this)
        switchAirPlay.isChecked = AppSettings.isAirPlayEnabled(this)
        switchDlna.isChecked = AppSettings.isDlnaEnabled(this)
        switchMiracast.isChecked = AppSettings.isMiracastEnabled(this)
        switchWebUi.isChecked = AppSettings.isWebUiEnabled(this)
        suppressSettingCallbacks = false
    }

    private fun updateIdentityInfo() {
        tvDeviceName.text = deviceInfoProvider.getDeviceName()
        tvConnectionCode.text = getString(R.string.connection_code, AppSettings.getOrCreateConnectionCode(this))
        updateWebUiInfo()
    }

    private fun updateWebUiInfo() {
        val ip = NetworkUtils.getLocalIpAddress()
        val enabled = AppSettings.isWebUiEnabled(this)
        val preferredPort = AppSettings.getWebUiPort(this)
        val runtimePort = RuntimeState.webUiPort.takeIf { it in 1024..65535 }
            ?: AppSettings.getWebUiLastBoundPort(this)
            ?: preferredPort
        val fallback = runtimePort != preferredPort

        tvWebUiUrl.text = when {
            !enabled -> getString(R.string.webui_disabled)
            ip == null -> getString(R.string.webui_waiting_network)
            else -> "http://$ip:$runtimePort"
        }
        tvWebUiPortStatus.text = when {
            !enabled -> getString(R.string.webui_service_disabled)
            fallback -> getString(R.string.webui_port_fallback, runtimePort, preferredPort)
            else -> getString(R.string.webui_port_preferred, runtimePort)
        }
        tvWebUiPortStatus.setTextColor(ContextCompat.getColor(this, if (fallback) R.color.warning else R.color.text_secondary))

        tvWebUiToken.text = if (!AppSettings.isWebUiAuthRequired(this)) {
            getString(R.string.webui_token_auth_off)
        } else {
            val token = AppSettings.getOrCreateWebUiToken(this)
            getString(R.string.webui_token_embedded, token.take(8), token.takeLast(6))
        }
    }

    private fun requestWifiDirectPermissions() {
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && !wifiPermissionPending) {
            wifiPermissionPending = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        wifiPermissionPending = false
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCastService()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            tvStatus.setText(R.string.wifi_direct_permission_denied)
        }
        if (privilegedSetupAttempted) promptOverlayPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        syncSwitchesFromSettings()
        updateIdentityInfo()
        refreshPrivilegeStatus()
        if (!wifiPermissionPending && privilegedSetupAttempted) promptOverlayPermissionIfNeeded()
    }

    override fun onPause() {
        backPressExitGate.reset()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (backPressExitGate.registerPress(SystemClock.elapsedRealtime())) {
            finish()
        } else {
            toast(R.string.press_back_again_to_exit)
        }
    }

    private fun promptOverlayPermissionIfNeeded() {
        if (!AppSettings.isAutoLaunchPlayer(this) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(this)) return
        overlayPromptShown = true
        AlertDialog.Builder(this)
            .setTitle(R.string.overlay_dialog_title)
            .setMessage(R.string.overlay_dialog_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ -> openOverlaySettings() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun openOverlaySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("Overlay settings not available: ${intent.data}")
            }
        }
        Toast.makeText(this, R.string.overlay_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun startCastService() {
        ContextCompat.startForegroundService(this, Intent(this, CastReceiverService::class.java))
    }

    private fun scheduleReceiverReload(source: String) {
        settingsApplyJob?.cancel()
        settingsApplyJob = lifecycleScope.launch {
            delay(500)
            restartReceiverService(showToast = false)
            Toast.makeText(this@MainActivity, getString(R.string.config_applied, source), Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartReceiverService(showToast: Boolean) {
        btnRestartReceiver.isEnabled = false
        stopService(Intent(this, CastReceiverService::class.java))
        lifecycleScope.launch {
            delay(450)
            startCastService()
            delay(150)
            btnRestartReceiver.isEnabled = true
            updateIdentityInfo()
            if (showToast) toast(R.string.receiver_restarted)
        }
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                val ip = NetworkUtils.getLocalIpAddress()
                tvDeviceIp.text = getString(R.string.device_ip, ip ?: getString(R.string.waiting_network))
                tvStatus.setText(if (NetworkUtils.isNetworkAvailable(this@MainActivity)) R.string.waiting_connection else R.string.waiting_network)
                updateIdentityInfo()
                delay(2000)
            }
        }
    }

    private fun performBackgroundOptimization(userInitiated: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val statusBefore = PrivilegedAccess.getStatus(this@MainActivity)
            if (!statusBefore.rootAvailable && statusBefore.shizukuAlive && !statusBefore.shizukuAuthorized) {
                privilegedSetupAttempted = true
                withContext(Dispatchers.Main) {
                    if (userInitiated) PrivilegedAccess.requestShizukuPermission()
                    refreshPrivilegeStatus()
                    if (!userInitiated && !wifiPermissionPending) promptOverlayPermissionIfNeeded()
                }
                return@launch
            }

            val result = PrivilegedAccess.applyBackgroundOptimizations(
                this@MainActivity,
                installBootScript = AppSettings.isAutoStartOnBoot(this@MainActivity)
            )
            privilegedSetupAttempted = true
            withContext(Dispatchers.Main) {
                if (userInitiated) {
                    val message = when {
                        result.success && result.bootScriptInstalled -> getString(R.string.optimize_done_magisk, result.privilegedChannel)
                        result.success -> getString(R.string.optimize_done, result.privilegedChannel)
                        result.privilegedChannel == "Android" -> getString(R.string.optimize_android_fallback)
                        else -> getString(R.string.optimize_partial)
                    }
                    Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                }
                refreshPrivilegeStatus()
                if (!wifiPermissionPending) promptOverlayPermissionIfNeeded()
            }
        }
    }

    private fun syncMagiskBootIntegration(enabled: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            if (enabled) PrivilegedAccess.installMagiskBootScript(this@MainActivity) else PrivilegedAccess.removeMagiskBootScript()
            withContext(Dispatchers.Main) { refreshPrivilegeStatus() }
        }
    }

    private fun refreshPrivilegeStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = PrivilegedAccess.getStatus(this@MainActivity)
            val overlayAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity)
            val autoStart = AppSettings.isAutoStartOnBoot(this@MainActivity)
            withContext(Dispatchers.Main) {
                tvRootStatus.setText(when {
                    status.magiskAvailable -> R.string.root_magisk_authorized
                    status.rootAvailable -> R.string.root_authorized
                    else -> R.string.root_unavailable
                })
                tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (status.rootAvailable) R.color.success else R.color.text_secondary))

                tvShizukuStatus.setText(when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> R.string.shizuku_android7
                    status.shizukuAuthorized -> R.string.shizuku_authorized
                    status.shizukuAlive -> R.string.shizuku_waiting_auth
                    else -> R.string.shizuku_not_connected
                })
                tvShizukuStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (status.shizukuAuthorized) R.color.success else R.color.text_secondary))

                tvOverlayStatus.setText(if (overlayAllowed) R.string.overlay_allowed else R.string.overlay_needs_permission)
                tvOverlayStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (overlayAllowed) R.color.success else R.color.warning))

                tvBootStatus.setText(when {
                    !autoStart -> R.string.boot_receiver_off
                    status.bootScriptInstalled -> R.string.boot_receiver_magisk
                    else -> R.string.boot_receiver_android
                })
                tvBootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (autoStart) R.color.success else R.color.text_secondary))

                btnOptimizeBackground.setText(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !status.rootAvailable && status.shizukuAlive && !status.shizukuAuthorized) {
                        R.string.authorize_shizuku_optimize
                    } else R.string.optimize_background
                )
            }
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        settingsApplyJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        }
        Timber.i("MainActivity destroyed; background cast receiver remains active")
        super.onDestroy()
    }
}
