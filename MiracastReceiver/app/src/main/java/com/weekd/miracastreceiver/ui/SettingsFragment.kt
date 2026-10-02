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
        activity?.runOnUiThread {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                toast(R.string.shizuku_granted_optimizing)
                performBackgroundOptimization(true)
            } else {
                toast(R.string.shizuku_denied)
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
        }
    }

    override fun onStop() {
        settingsApplyJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener) }
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        syncSwitches()
        refreshPrivilegeStatus()
    }

    override fun requestInitialFocus() {
        if (::switchAutoStart.isInitialized) switchAutoStart.post { switchAutoStart.requestFocus() }
    }

    private fun bindControls() {
        switchAutoStart.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            AppSettings.setAutoStartOnBoot(requireContext(), checked)
            lifecycleScope.launch(Dispatchers.IO) {
                if (checked) PrivilegedAccess.installMagiskBootScript(requireContext())
                else PrivilegedAccess.removeMagiskBootScript()
                withContext(Dispatchers.Main) { refreshPrivilegeStatus() }
            }
        }
        switchAirPlay.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            AppSettings.setAirPlayEnabled(requireContext(), checked)
            scheduleReceiverReload("AirPlay")
        }
        switchDlna.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            AppSettings.setDlnaEnabled(requireContext(), checked)
            scheduleReceiverReload("DLNA")
        }
        switchMiracast.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            AppSettings.setMiracastEnabled(requireContext(), checked)
            if (checked) (activity as? MainActivity)?.requestWifiDirectPermissions()
            scheduleReceiverReload("Miracast")
        }
        switchWebUi.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            AppSettings.setWebUiEnabled(requireContext(), checked)
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
                if (focused) target.post { target.requestRectangleOnScreen(android.graphics.Rect(0, 0, target.width, target.height), true) }
            }
        }
    }

    private fun syncSwitches() {
        suppressCallbacks = true
        switchAutoStart.isChecked = AppSettings.isAutoStartOnBoot(requireContext())
        switchAirPlay.isChecked = AppSettings.isAirPlayEnabled(requireContext())
        switchDlna.isChecked = AppSettings.isDlnaEnabled(requireContext())
        switchMiracast.isChecked = AppSettings.isMiracastEnabled(requireContext())
        switchWebUi.isChecked = AppSettings.isWebUiEnabled(requireContext())
        suppressCallbacks = false
    }

    private fun scheduleReceiverReload(source: String) {
        settingsApplyJob?.cancel()
        settingsApplyJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(500)
            restartReceiverService(false)
            Toast.makeText(requireContext(), getString(R.string.config_applied, source), Toast.LENGTH_SHORT).show()
        }
    }

    private fun restartReceiverService(showToast: Boolean) {
        btnRestartReceiver.isEnabled = false
        requireContext().stopService(Intent(requireContext(), CastReceiverService::class.java))
        viewLifecycleOwner.lifecycleScope.launch {
            delay(450)
            (activity as? MainActivity)?.startCastService()
            delay(150)
            if (isAdded) {
                btnRestartReceiver.isEnabled = true
                if (showToast) toast(R.string.receiver_restarted)
            }
        }
    }

    private fun performBackgroundOptimization(userInitiated: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext()
            val statusBefore = PrivilegedAccess.getStatus(context)
            if (!statusBefore.rootAvailable && statusBefore.shizukuAlive && !statusBefore.shizukuAuthorized) {
                withContext(Dispatchers.Main) {
                    if (userInitiated) PrivilegedAccess.requestShizukuPermission()
                    refreshPrivilegeStatus()
                }
                return@launch
            }
            val result = PrivilegedAccess.applyBackgroundOptimizations(context, AppSettings.isAutoStartOnBoot(context))
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                if (userInitiated) {
                    val message = when {
                        result.success && result.bootScriptInstalled -> getString(R.string.optimize_done_magisk, result.privilegedChannel)
                        result.success -> getString(R.string.optimize_done, result.privilegedChannel)
                        result.privilegedChannel == "Android" -> getString(R.string.optimize_android_fallback)
                        else -> getString(R.string.optimize_partial)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
                refreshPrivilegeStatus()
                promptOverlayPermissionIfNeeded()
            }
        }
    }

    private fun refreshPrivilegeStatus() {
        if (!isAdded) return
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val context = requireContext()
            val status = PrivilegedAccess.getStatus(context)
            val overlayAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
            val autoStart = AppSettings.isAutoStartOnBoot(context)
            withContext(Dispatchers.Main) {
                if (!isAdded) return@withContext
                tvRootStatus.setText(when {
                    status.magiskAvailable -> R.string.root_magisk_authorized
                    status.rootAvailable -> R.string.root_authorized
                    else -> R.string.root_unavailable
                })
                tvRootStatus.setTextColor(ContextCompat.getColor(context, if (status.rootAvailable) R.color.success else R.color.text_secondary))
                tvShizukuStatus.setText(when {
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.N -> R.string.shizuku_android7
                    status.shizukuAuthorized -> R.string.shizuku_authorized
                    status.shizukuAlive -> R.string.shizuku_waiting_auth
                    else -> R.string.shizuku_not_connected
                })
                tvShizukuStatus.setTextColor(ContextCompat.getColor(context, if (status.shizukuAuthorized) R.color.success else R.color.text_secondary))
                tvOverlayStatus.setText(if (overlayAllowed) R.string.overlay_allowed else R.string.overlay_needs_permission)
                tvOverlayStatus.setTextColor(ContextCompat.getColor(context, if (overlayAllowed) R.color.success else R.color.warning))
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
        if (!AppSettings.isAutoLaunchPlayer(requireContext()) || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(requireContext())) return
        overlayPromptShown = true
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.overlay_dialog_title)
            .setMessage(R.string.overlay_dialog_message)
            .setPositiveButton(R.string.go_to_settings) { _, _ -> openOverlaySettings() }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun openOverlaySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}")),
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
        Toast.makeText(requireContext(), R.string.overlay_settings_unavailable, Toast.LENGTH_LONG).show()
    }

    private fun toast(resId: Int) = Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
}
