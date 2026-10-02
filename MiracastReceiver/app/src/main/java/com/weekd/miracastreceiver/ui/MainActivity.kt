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
import com.weekd.miracastreceiver.discovery.MdnsAdvertiser
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.util.PrivilegedAccess
import com.weekd.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

/**
 * Android TV main screen. The receiver itself lives in [CastReceiverService]; this Activity is a
 * remote-control friendly status/configuration surface and can be closed without stopping casting.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectionCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvRootStatus: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvBootStatus: TextView
    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var btnOptimizeBackground: Button
    private lateinit var btnRestartReceiver: Button

    private var connectionCode: String = ""

    /** 定位权限弹窗还在时不叠加悬浮窗权限提示，等它有结果再说。 */
    private var wifiPermissionPending = false
    private var overlayPromptShown = false
    private var privilegedSetupAttempted = false

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

        /** Wi-Fi Direct runtime permissions. */
        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
            .onFailure { Timber.w(it, "Unable to register Shizuku permission listener") }

        initServices()
        initViews()
        requestWifiDirectPermissions()
        checkNetworkAndStart()

        // On the intended Magisk-rooted TV this silently installs/refreshes the boot fallback and
        // background allowances. Without root it simply leaves the standard Android path intact.
        performBackgroundOptimization(userInitiated = false)
    }

    private fun requestWifiDirectPermissions() {
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Timber.i("Requesting Wi-Fi Direct permissions: $missing")
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
            Timber.i("Wi-Fi Direct permissions granted, ensuring cast service is running")
            startCastService()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            tvStatus.text = "未授予 Wi-Fi Direct 权限，Miracast 不可用"
        }

        if (privilegedSetupAttempted) promptOverlayPermissionIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        refreshPrivilegeStatus()
        if (!wifiPermissionPending && privilegedSetupAttempted) promptOverlayPermissionIfNeeded()
    }

    /**
     * Android 10+ normally blocks an Activity launch from the background. Root/Shizuku setup grants
     * the overlay app-op automatically; this dialog remains as the non-privileged fallback.
     */
    private fun promptOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(this)) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle("允许后台自动显示投屏画面")
            .setMessage(
                "当应用退回 Android TV 桌面后，投屏连接仍由后台服务接收。为了让收到投屏时自动切到播放画面，" +
                    "请允许本应用“显示在其他应用上层”。Magisk Root / Shizuku 优化成功时通常会自动配置。"
            )
            .setPositiveButton("去设置") { _, _ -> openOverlaySettings() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openOverlaySettings() {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        val intents = listOf(withPackage, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("Overlay settings not available: ${intent.data}")
            }
        }
        Toast.makeText(
            this,
            "此电视没有悬浮窗设置页，可使用 Root/Shizuku 的“优化后台运行”自动配置",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun initServices() {
        deviceInfoProvider = DeviceInfoProvider(this)
        mdnsAdvertiser = MdnsAdvertiser(this)
        connectionCode = NetworkUtils.generateConnectionCode()
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvConnectionCode = findViewById(R.id.tv_connection_code)
        tvStatus = findViewById(R.id.tv_status)
        tvRootStatus = findViewById(R.id.tv_root_status)
        tvShizukuStatus = findViewById(R.id.tv_shizuku_status)
        tvOverlayStatus = findViewById(R.id.tv_overlay_status)
        tvBootStatus = findViewById(R.id.tv_boot_status)
        switchAutoStart = findViewById(R.id.switch_auto_start)
        btnOptimizeBackground = findViewById(R.id.btn_optimize_background)
        btnRestartReceiver = findViewById(R.id.btn_restart_receiver)

        switchAutoStart.isChecked = AppSettings.isAutoStartOnBoot(this)
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoStartOnBoot(this, isChecked)
            Timber.i("Auto start on boot set to $isChecked")
            syncMagiskBootIntegration(isChecked)
        }

        btnOptimizeBackground.setOnClickListener {
            performBackgroundOptimization(userInitiated = true)
        }

        btnRestartReceiver.setOnClickListener {
            restartReceiverService()
        }

        tvDeviceName.text = deviceInfoProvider.getDeviceName()
        tvConnectionCode.text = getString(R.string.connection_code, connectionCode)

        // TV quality requirement: always expose an obvious initial D-pad focus target.
        switchAutoStart.post { switchAutoStart.requestFocus() }
    }

    private fun checkNetworkAndStart() {
        // The service has its own network-ready retry loop, so start it even before Wi-Fi obtains IP.
        startCastService()

        if (!NetworkUtils.isNetworkAvailable(this)) {
            tvStatus.text = "等待网络连接"
            tvDeviceIp.text = getString(R.string.device_ip, "等待网络")
            updateStatus()
            return
        }

        if (!NetworkUtils.isWifiConnected(this)) {
            Timber.w("Wi-Fi not connected; LAN casting may be unavailable")
        }

        val ipAddress = NetworkUtils.getLocalIpAddress()
        tvDeviceIp.text = getString(R.string.device_ip, ipAddress ?: "获取中…")
        if (ipAddress != null) Timber.i("Local IP: $ipAddress")

        startAdvertising()
        updateStatus()
    }

    private fun startCastService() {
        val intent = Intent(this, CastReceiverService::class.java)
        ContextCompat.startForegroundService(this, intent)
        Timber.i("Cast receiver foreground service requested")
    }

    private fun restartReceiverService() {
        btnRestartReceiver.isEnabled = false
        stopService(Intent(this, CastReceiverService::class.java))
        lifecycleScope.launch {
            delay(350)
            startCastService()
            btnRestartReceiver.isEnabled = true
            Toast.makeText(this@MainActivity, "投屏接收服务已重新启动", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startAdvertising() {
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        val deviceName = deviceInfoProvider.getDeviceName()

        // AirPlay is advertised by CastReceiverService/AirPlayReceiver only. This separate record is
        // retained for the project's custom Android sender discovery path.
        mdnsAdvertiser.startAdvertising(
            serviceName = deviceName,
            port = 8080,
            deviceInfo = deviceInfo + ("code" to connectionCode)
        )

        Timber.i("Started custom Miracast mDNS advertising: $deviceName")
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                val ip = NetworkUtils.getLocalIpAddress()
                if (ip != null) tvDeviceIp.text = getString(R.string.device_ip, ip)

                tvStatus.text = when {
                    !NetworkUtils.isNetworkAvailable(this@MainActivity) -> "等待网络连接"
                    mdnsAdvertiser.isAdvertising() -> getString(R.string.waiting_connection)
                    else -> "接收服务运行中"
                }
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
                    if (userInitiated) {
                        PrivilegedAccess.requestShizukuPermission()
                    }
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
                        result.privilegedChannel == "Android" ->
                            "未获得 Root/Shizuku 权限，将继续使用 Android 标准后台模式"
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
            if (enabled) {
                PrivilegedAccess.installMagiskBootScript(this@MainActivity)
            } else {
                PrivilegedAccess.removeMagiskBootScript()
            }
            withContext(Dispatchers.Main) { refreshPrivilegeStatus() }
        }
    }

    private fun refreshPrivilegeStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = PrivilegedAccess.getStatus(this@MainActivity)
            val overlayAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this@MainActivity)
            val autoStart = AppSettings.isAutoStartOnBoot(this@MainActivity)

            withContext(Dispatchers.Main) {
                tvRootStatus.text = when {
                    status.magiskAvailable -> "● Magisk Root · 已授权"
                    status.rootAvailable -> "● Root · 已授权"
                    else -> "○ Root · 不可用"
                }
                tvRootStatus.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (status.rootAvailable) R.color.success else R.color.text_secondary
                    )
                )

                tvShizukuStatus.text = when {
                    status.shizukuAuthorized -> "● Shizuku · 已连接并授权"
                    status.shizukuAlive -> "● Shizuku · 已连接，等待授权"
                    else -> "○ Shizuku · 未连接"
                }
                tvShizukuStatus.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (status.shizukuAuthorized) R.color.success else R.color.text_secondary
                    )
                )

                tvOverlayStatus.text = if (overlayAllowed) {
                    "● 后台弹出 · 已允许"
                } else {
                    "○ 后台弹出 · 需要授权"
                }
                tvOverlayStatus.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (overlayAllowed) R.color.success else R.color.warning
                    )
                )

                tvBootStatus.text = when {
                    !autoStart -> "○ 开机接收 · 已关闭"
                    status.bootScriptInstalled -> "● 开机接收 · BootReceiver + Magisk service.d"
                    else -> "● 开机接收 · Android BootReceiver"
                }
                tvBootStatus.setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (autoStart) R.color.success else R.color.text_secondary
                    )
                )

                btnOptimizeBackground.text = if (
                    !status.rootAvailable && status.shizukuAlive && !status.shizukuAuthorized
                ) {
                    "授权 Shizuku 并优化"
                } else {
                    getString(R.string.optimize_background)
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        mdnsAdvertiser.stopAdvertising()
        Timber.i("MainActivity destroyed; background cast receiver remains active")
        super.onDestroy()
    }
}
