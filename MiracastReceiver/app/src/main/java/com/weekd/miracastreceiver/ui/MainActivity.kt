package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PrivilegedAccess.SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        runOnUiThread {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 已授权，正在优化后台运行", Toast.LENGTH_SHORT).show()
                performBackgroundOptimization(userInitiated = true)
            } else {
                Toast.makeText(this, "Shizuku 授权被拒绝", Toast.LENGTH_SHORT).show()
                refreshPrivilegeStatus()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001

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
        checkNetworkAndStart()
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

        updateIdentityInfo()
        switchAutoStart.post { switchAutoStart.requestFocus() }
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
            !enabled -> "WebUI 已关闭"
            ip == null -> "WebUI 等待网络"
            else -> "http://$ip:$runtimePort"
        }
        tvWebUiPortStatus.text = when {
            !enabled -> "局域网管理服务未启用"
            fallback -> "实际 $runtimePort · 首选 $preferredPort 被占用，已临时回退"
            else -> "实际 $runtimePort · 使用首选端口"
        }
        tvWebUiPortStatus.setTextColor(
            ContextCompat.getColor(this, if (fallback) R.color.warning else R.color.text_secondary)
        )

        tvWebUiToken.text = if (!AppSettings.isWebUiAuthRequired(this)) {
            "扫码直达 · Token 验证已关闭"
        } else {
            val token = AppSettings.getOrCreateWebUiToken(this)
            "Token 已嵌入二维码 · ${token.take(8)}…${token.takeLast(6)}"
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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        wifiPermissionPending = false

        val granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            startCastService()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            tvStatus.text = "未授予 Wi-Fi Direct 权限，Miracast 不可用"
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

    private fun promptOverlayPermissionIfNeeded() {
        if (!AppSettings.isAutoLaunchPlayer(this)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(this)) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle("允许后台自动显示投屏画面")
            .setMessage(
                "应用退出到 Android TV 桌面后接收服务仍会运行。为了在收到投屏时自动切到播放器，" +
                    "请允许“显示在其他应用上层”。Magisk Root / Shizuku 优化成功时通常会自动配置。"
            )
            .setPositiveButton("去设置") { _, _ -> openOverlaySettings() }
            .setNegativeButton("稍后", null)
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
        Toast.makeText(this, "此电视没有悬浮窗设置页，可使用 Root/Shizuku 自动配置", Toast.LENGTH_LONG).show()
    }

    private fun checkNetworkAndStart() {
        startCastService()
        updateStatus()
    }

    private fun startCastService() {
        ContextCompat.startForegroundService(this, Intent(this, CastReceiverService::class.java))
    }

    private fun scheduleReceiverReload(source: String) {
        settingsApplyJob?.cancel()
        settingsApplyJob = lifecycleScope.launch {
            delay(500)
            restartReceiverService(showToast = false)
            Toast.makeText(this@MainActivity, "$source 配置已应用", Toast.LENGTH_SHORT).show()
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
            if (showToast) Toast.makeText(this@MainActivity, "投屏接收服务已重新启动", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                val ip = NetworkUtils.getLocalIpAddress()
                tvDeviceIp.text = getString(R.string.device_ip, ip ?: "等待网络")
                tvStatus.text = if (NetworkUtils.isNetworkAvailable(this@MainActivity)) {
                    getString(R.string.waiting_connection)
                } else {
                    "等待网络连接"
                }
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
                        result.success && result.bootScriptInstalled ->
                            "${result.privilegedChannel} 优化完成，Magisk 开机兜底已安装"
                        result.success -> "${result.privilegedChannel} 后台优化完成"
                        result.privilegedChannel == "Android" -> "未获得 Root/Shizuku 权限，将使用 Android 标准后台模式"
                        else -> "后台优化未完全成功，请检查授权"
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
            if (enabled) PrivilegedAccess.installMagiskBootScript(this@MainActivity)
            else PrivilegedAccess.removeMagiskBootScript()
            withContext(Dispatchers.Main) { refreshPrivilegeStatus() }
        }
    }

    private fun refreshPrivilegeStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = PrivilegedAccess.getStatus(this@MainActivity)
            val overlayAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this@MainActivity)
            val autoStart = AppSettings.isAutoStartOnBoot(this@MainActivity)

            withContext(Dispatchers.Main) {
                tvRootStatus.text = when {
                    status.magiskAvailable -> "● Magisk Root · 已授权"
                    status.rootAvailable -> "● Root · 已授权"
                    else -> "○ Root · 不可用"
                }
                tvRootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (status.rootAvailable) R.color.success else R.color.text_secondary))

                tvShizukuStatus.text = when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> "○ Shizuku · Android 7+ 可用"
                    status.shizukuAuthorized -> "● Shizuku · 已连接并授权"
                    status.shizukuAlive -> "● Shizuku · 已连接，等待授权"
                    else -> "○ Shizuku · 未连接"
                }
                tvShizukuStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (status.shizukuAuthorized) R.color.success else R.color.text_secondary))

                tvOverlayStatus.text = if (overlayAllowed) "● 后台弹出 · 已允许" else "○ 后台弹出 · 需要授权"
                tvOverlayStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (overlayAllowed) R.color.success else R.color.warning))

                tvBootStatus.text = when {
                    !autoStart -> "○ 开机接收 · 已关闭"
                    status.bootScriptInstalled -> "● 开机接收 · BootReceiver + Magisk service.d"
                    else -> "● 开机接收 · Android BootReceiver"
                }
                tvBootStatus.setTextColor(ContextCompat.getColor(this@MainActivity, if (autoStart) R.color.success else R.color.text_secondary))

                btnOptimizeBackground.text = if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                    !status.rootAvailable && status.shizukuAlive && !status.shizukuAuthorized
                ) "授权 Shizuku 并优化" else getString(R.string.optimize_background)
            }
        }
    }

    override fun onDestroy() {
        settingsApplyJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        }
        Timber.i("MainActivity destroyed; background cast receiver remains active")
        super.onDestroy()
    }
}
