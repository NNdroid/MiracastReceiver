package com.weekd.miracastreceiver.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.util.PrivilegedAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import timber.log.Timber

/** Receiver and privileged integration settings. */
class SettingsFragment : Fragment(), MainActivity.TvPage {
    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var switchAirPlay: SwitchCompat
    private lateinit var switchDlna: SwitchCompat
    private lateinit var switchMiracast: SwitchCompat
    private lateinit var switchWebUi: SwitchCompat
    private lateinit var btnOptimizeBackground: Button
    private lateinit var btnRestartReceiver: Button
    private lateinit var tvRootStatus: TextView
    private lateinit var tvShizukuStatus: TextView
    private lateinit var tvOverlayStatus: TextView
    private lateinit var tvBootStatus: TextView

    private var suppressCallbacks = false
    private var settingsApplyJob: Job? = null
    private var overlayPromptShown = false

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != PrivilegedAccess.SHIZUKU_PERMISSION_REQUEST) return@OnRequestPermissionResultListener
        val host = activity ?: return@OnRequestPermissionResultListener
        host.runOnUiThread {
            if (!isAdded || view == null) return@runOnUiThread
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                safeToast(R.string.shizuku_granted_optimizing)
                performBackgroundOptimization(true)
            } else {
                safeToast(R.string.shizuku_denied)
                refreshPrivilegeStatus()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        switchAutoStart = view.findViewById(R.id.switch_auto_start)
        switchAirPlay = view.findViewById(R.id.switch_airplay)
        switchDlna = view.findViewById(R.id.switch_dlna)
        switchMiracast = view.findViewById(R.id.switch_miracast)
        switchWebUi = view.findViewById(R.id.switch_webui)
        btnOptimizeBackground = view.findViewById(R.id.btn_optimize_background)
        btnRestartReceiver = view.findViewById(R.id.btn_restart_receiver)
        tvRootStatus = view.findViewById(R.id.tv_root_status)
        tvShizukuStatus = view.findViewById(R.id.tv_shizuku_status)
        tvOverlayStatus = view.findViewById(R.id.tv_overlay_status)
        tvBootStatus = view.findViewById(R.id.tv_boot_status)

        bindControls()
        setupFocusEffects()
        syncSwitches()
        refreshPrivilegeStatus()
    }

    override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { Shizuku.addRequestPermissionResultListener(shizukuPermissionListener) }
                .onFailure { Timber.w(it, "Unable to register Shizuku permission listener") }
        }
    }

    override fun onStop() {
        settingsApplyJob?.cancel()
        settingsApplyJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (view != null) {
            syncSwitches()
            refreshPrivilegeStatus()
        }
    }

    override fun requestInitialFocus() {
        if (::switchAutoStart.isInitialized && view != null) {
            switchAutoStart.post {
                if (isAdded && viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    switchAutoStart.requestFocus()
                }
            }
        }
    }

    private fun bindControls() {
        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val appContext = context?.applicationContext ?: return@setOnCheckedChangeListener
            AppSettings.setAutoStartOnBoot(appContext, checked)
            viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                runCatching {
                    if (checked) PrivilegedAccess.installMagiskBootScript(appContext)
                    else PrivilegedAccess.removeMagiskBootScript()
                }.onFailure { Timber.w(it, "Unable to update Magisk boot integration") }
                withContext(Dispatchers.Main) {
                    if (view != null && isAdded) refreshPrivilegeStatus()
                }
            }
        }
        switchAirPlay.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val appContext = context?.applicationContext ?: return@setOnCheckedChangeListener
            AppSettings.setAirPlayEnabled(appContext, checked)
            scheduleReceiverReload("AirPlay")
        }
        switchDlna.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val appContext = context?.applicationContext ?: return@setOnCheckedChangeListener
            AppSettings.setDlnaEnabled(appContext, checked)
            scheduleReceiverReload("DLNA")
        }
        switchMiracast.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val appContext = context?.applicationContext ?: return@setOnCheckedChangeListener
            AppSettings.setMiracastEnabled(appContext, checked)
            if (checked) (activity as? MainActivity)?.requestWifiDirectPermissions()
            scheduleReceiverReload("Miracast")
        }
        switchWebUi.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            val appContext = context?.applicationContext ?: return@setOnCheckedChangeListener
            AppSettings.setWebUiEnabled(appContext, checked)
            scheduleReceiverReload("WebUI")
        }
        btnOptimizeBackground.setOnClickListener { performBackgroundOptimization(true) }
        btnRestartReceiver.setOnClickListener { restartReceiverService(true) }
    }

    private fun setupFocusEffects() {
        val controls = listOf<View>(switchAutoStart, switchAirPlay, switchDlna, switchMiracast, switchWebUi, btnOptimizeBackground, btnRestartReceiver)
        controls.forEachIndexed { index, view ->
            val previous = controls[(index - 1 + controls.size) % controls.size]
            val next = controls[(index + 1) % controls.size]
            view.nextFocusUpId = previous.id
            view.nextFocusDownId = next.id
            view.setOnFocusChangeListener { target, focused ->
                target.animate().cancel()
                val scale = if (focused) 1.035f else 1f
                target.animate().scaleX(scale).scaleY(scale).setDuration(110).start()
                if (focused) target.post {
                    if (target.isAttachedToWindow) {
                        target.requestRectangleOnScreen(android.graphics.Rect(0, 0, target.width, target.height), true)
                    }
                }
            }
        }
    }

    private fun syncSwitches() {
        val appContext = context?.applicationContext ?: return
        if (!::switchAutoStart.isInitialized) return
        suppressCallbacks = true
        try {
            switchAutoStart.isChecked = AppSettings.isAutoStartOnBoot(appContext)
            switchAirPlay.isChecked = AppSettings.isAirPlayEnabled(appContext)
            switchDlna.isChecked = AppSettings.isDlnaEnabled(appContext)
            switchMiracast.isChecked = AppSettings.isMiracastEnabled(appContext)
            switchWebUi.isChecked = AppSettings.isWebUiEnabled(appContext)
        } finally {
            suppressCallbacks = false
        }
    }

    private fun scheduleReceiverReload(source: String) {
        val appContext = context?.applicationContext ?: return
        settingsApplyJob?.cancel()
        settingsApplyJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            if (!isAdded || view == null) return@launch
            restartReceiverService(false)
            Toast.makeText(appContext, getString(R.string.config_applied, source), Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartReceiverService(showToast: Boolean) {
        val appContext = context?.applicationContext ?: return
        if (::btnRestartReceiver.isInitialized) btnRestartReceiver.isEnabled = false
        runCatching { appContext.stopService(Intent(appContext, CastReceiverService::class.java)) }
            .onFailure { Timber.w(it, "Unable to stop receiver service") }
        viewLifecycleOwner.lifecycleScope.launch {
            delay(450)
            if (!isAdded || view == null) return@launch
            (activity as? MainActivity)?.startCastService()
            delay(150)
            if (isAdded && view != null && ::btnRestartReceiver.isInitialized) {
                btnRestartReceiver.isEnabled = true
                if (showToast) safeToast(R.string.receiver_restarted)
            }
        }
    }

    private fun performBackgroundOptimization(userInitiated: Boolean) {
        val appContext = context?.applicationContext ?: return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val statusBefore = runCatching { PrivilegedAccess.getStatus(appContext) }
                .onFailure { Timber.w(it, "Unable to read privileged status") }
                .getOrNull() ?: return@launch
            if (!statusBefore.rootAvailable && statusBefore.shizukuAlive && !statusBefore.shizukuAuthorized) {
                withContext(Dispatchers.Main) {
                    if (!isAdded || view == null) return@withContext
                    if (userInitiated) runCatching { PrivilegedAccess.requestShizukuPermission() }
                        .onFailure { Timber.w(it, "Unable to request Shizuku permission") }
                    refreshPrivilegeStatus()
                }
                return@launch
            }
            val result = runCatching {
                PrivilegedAccess.applyBackgroundOptimizations(appContext, AppSettings.isAutoStartOnBoot(appContext))
            }.onFailure { Timber.w(it, "Background optimization failed") }.getOrNull() ?: return@launch
            withContext(Dispatchers.Main) {
                if (!isAdded || view == null) return@withContext
                if (userInitiated) {
                    val message = when {
                        result.success && result.bootScriptInstalled -> getString(R.string.optimize_done_magisk, result.privilegedChannel)
                        result.success -> getString(R.string.optimize_done, result.privilegedChannel)
                        result.privilegedChannel == "Android" -> getString(R.string.optimize_android_fallback)
                        else -> getString(R.string.optimize_partial)
                    }
                    Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
                }
                refreshPrivilegeStatus()
                promptOverlayPermissionIfNeeded()
            }
        }
    }

    private fun refreshPrivilegeStatus() {
        val appContext = context?.applicationContext ?: return
        if (!isAdded || view == null) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val status = runCatching { PrivilegedAccess.getStatus(appContext) }
                .onFailure { Timber.w(it, "Unable to refresh privileged status") }
                .getOrNull() ?: return@launch
            val overlayAllowed = Settings.canDrawOverlays(appContext)
            val autoStart = AppSettings.isAutoStartOnBoot(appContext)
            withContext(Dispatchers.Main) {
                if (!isAdded || view == null || !viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@withContext
                }
                tvRootStatus.setText(when {
                    status.magiskAvailable -> R.string.root_magisk_authorized
                    status.rootAvailable -> R.string.root_authorized
                    else -> R.string.root_unavailable
                })
                tvRootStatus.setTextColor(ContextCompat.getColor(appContext, if (status.rootAvailable) R.color.success else R.color.text_secondary))
                tvShizukuStatus.setText(when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> R.string.shizuku_android7
                    status.shizukuAuthorized -> R.string.shizuku_authorized
                    status.shizukuAlive -> R.string.shizuku_waiting_auth
                    else -> R.string.shizuku_not_connected
                })
                tvShizukuStatus.setTextColor(ContextCompat.getColor(appContext, if (status.shizukuAuthorized) R.color.success else R.color.text_secondary))
                tvOverlayStatus.setText(if (overlayAllowed) R.string.overlay_allowed else R.string.overlay_needs_permission)
                tvOverlayStatus.setTextColor(ContextCompat.getColor(appContext, if (overlayAllowed) R.color.success else R.color.warning))
                tvBootStatus.setText(when {
                    !autoStart -> R.string.boot_receiver_off
                    status.bootScriptInstalled -> R.string.boot_receiver_magisk
                    else -> R.string.boot_receiver_android
                })
                btnOptimizeBackground.setText(if (!status.rootAvailable && status.shizukuAlive && !status.shizukuAuthorized) R.string.authorize_shizuku_optimize else R.string.optimize_background)
            }
        }
    }

    private fun promptOverlayPermissionIfNeeded() {
        val hostContext = context ?: return
        if (!AppSettings.isAutoLaunchPlayer(hostContext) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(hostContext) || !isAdded) return
        overlayPromptShown = true
        runCatching {
            AlertDialog.Builder(hostContext)
                .setTitle(R.string.overlay_dialog_title)
                .setMessage(R.string.overlay_dialog_message)
                .setPositiveButton(R.string.go_to_settings) { _, _ -> openOverlaySettings() }
                .setNegativeButton(R.string.later, null)
                .show()
        }.onFailure {
            overlayPromptShown = false
            Timber.w(it, "Unable to show overlay permission dialog")
        }
    }

    private fun openOverlaySettings() {
        val hostContext = context ?: return
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${hostContext.packageName}")),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("Overlay settings not available: ${intent.data}")
            } catch (e: RuntimeException) {
                Timber.w(e, "Unable to launch overlay settings")
            }
        }
        Toast.makeText(hostContext.applicationContext, R.string.overlay_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun safeToast(resId: Int) {
        context?.applicationContext?.let { Toast.makeText(it, resId, Toast.LENGTH_SHORT).show() }
    }
}
