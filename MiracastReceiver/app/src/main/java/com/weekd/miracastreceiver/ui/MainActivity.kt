package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber

/** TV navigation shell. Casting continues in the foreground service while pages are Fragments. */
class MainActivity : AppCompatActivity() {

    interface TvPage {
        fun requestInitialFocus() = Unit
    }

    private enum class Destination {
        HOME, SETTINGS, ABOUT
    }

    private val backPressExitGate = BackPressExitGate()
    private var currentDestination = Destination.HOME
    private var pendingDestination: Pair<Destination, Boolean>? = null
    private lateinit var navItems: List<Pair<Destination, TextView>>

    private val wifiDirectPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            if (grants.isNotEmpty() && grants.values.all { it }) {
                startCastService()
            } else {
                Toast.makeText(this, R.string.wifi_direct_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    companion object {
        private const val FOCUS_SCALE = 1.06f
        private const val FOCUS_ANIMATION_MS = 90L
        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                // `NEARBY_WIFI_DEVICES` alone covers the scan APIs but not the Wi-Fi service's
                // broadcast gate, so a sink can be listed and still never see its group form.
                arrayOf(
                    Manifest.permission.NEARBY_WIFI_DEVICES,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setupBackHandling()
        setupNavigation()
        startCastService()
        if (AppSettings.isMiracastEnabled(this)) requestWifiDirectPermissions()

        if (savedInstanceState == null) {
            showDestination(Destination.HOME, moveFocus = true)
        } else {
            currentDestination = restoredDestination()
            repairRestoredFragments(currentDestination)
            updateNavigationSelection()
            navItems.firstOrNull { it.first == currentDestination }?.second?.let { navView ->
                navView.post {
                    if (!isFinishing && !isDestroyed) navView.requestFocus()
                }
            }
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPressed()
            }
        })
    }

    private fun handleBackPressed() {
        if (currentDestination != Destination.HOME) {
            showDestination(Destination.HOME, moveFocus = true)
            backPressExitGate.reset()
            return
        }
        if (backPressExitGate.registerPress(SystemClock.elapsedRealtime())) finish()
        else Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
    }

    override fun onPostResume() {
        super.onPostResume()
        pendingDestination?.let { (destination, moveFocus) ->
            pendingDestination = null
            showDestination(destination, moveFocus)
        }
    }

    private fun restoredDestination(): Destination {
        val visible = supportFragmentManager.fragments.firstOrNull { it.isAdded && !it.isHidden }
        return when (visible) {
            is SettingsFragment -> Destination.SETTINGS
            is AboutFragment -> Destination.ABOUT
            else -> Destination.HOME
        }
    }

    private fun repairRestoredFragments(destination: Destination) {
        val manager = supportFragmentManager
        if (manager.isStateSaved || isFinishing || isDestroyed) return
        val target = manager.findFragmentByTag(destination.name)
            ?: manager.fragments.firstOrNull { fragment ->
                when (destination) {
                    Destination.HOME -> fragment is HomeFragment
                    Destination.SETTINGS -> fragment is SettingsFragment
                    Destination.ABOUT -> fragment is AboutFragment
                }
            }
            ?: return

        runCatching {
            val transaction = manager.beginTransaction().setReorderingAllowed(true)
            manager.fragments.filter { it.isAdded }.forEach { fragment ->
                if (fragment == target) {
                    transaction.show(fragment)
                    transaction.setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
                } else {
                    transaction.hide(fragment)
                    transaction.setMaxLifecycle(fragment, Lifecycle.State.CREATED)
                }
            }
            transaction.commit()
        }.onFailure { Timber.w(it, "Unable to normalize restored TV fragments") }
    }

    private fun setupNavigation() {
        navItems = listOf(
            Destination.HOME to findViewById(R.id.nav_home),
            Destination.SETTINGS to findViewById(R.id.nav_settings),
            Destination.ABOUT to findViewById(R.id.nav_about)
        )
        navItems.forEachIndexed { index, (destination, view) ->
            val previous = navItems[(index - 1 + navItems.size) % navItems.size].second
            val next = navItems[(index + 1) % navItems.size].second
            view.nextFocusUpId = previous.id
            view.nextFocusDownId = next.id
            view.setOnClickListener { showDestination(destination, moveFocus = false) }
            view.setOnFocusChangeListener { target, hasFocus ->
                target.animate().cancel()
                val scale = if (hasFocus) FOCUS_SCALE else 1f
                target.animate().scaleX(scale).scaleY(scale).setDuration(FOCUS_ANIMATION_MS).withLayer().start()
            }
        }
    }

    private fun fragmentFor(destination: Destination): Fragment = when (destination) {
        Destination.HOME -> HomeFragment()
        Destination.SETTINGS -> SettingsFragment()
        Destination.ABOUT -> AboutFragment()
    }

    private fun showDestination(destination: Destination, moveFocus: Boolean) {
        val manager = supportFragmentManager
        if (isFinishing || isDestroyed) return
        if (manager.isStateSaved) {
            pendingDestination = destination to moveFocus
            Timber.d("Deferring TV navigation to $destination until Activity resumes")
            return
        }

        val existing = manager.findFragmentByTag(destination.name)
        if (destination == currentDestination && existing != null && !existing.isHidden) {
            if (!moveFocus) (existing as? TvPage)?.requestInitialFocus()
            return
        }

        val target = existing ?: fragmentFor(destination)
        runCatching {
            val transaction = manager.beginTransaction().setReorderingAllowed(true)
            manager.fragments.filter { it.isAdded }.forEach { fragment ->
                if (fragment != target) {
                    transaction.hide(fragment)
                    transaction.setMaxLifecycle(fragment, Lifecycle.State.CREATED)
                }
            }
            if (target.isAdded) transaction.show(target)
            else transaction.add(R.id.fragment_container, target, destination.name)
            transaction.setMaxLifecycle(target, Lifecycle.State.RESUMED)

            currentDestination = destination
            transaction.runOnCommit {
                if (!moveFocus && !isFinishing && !isDestroyed) (target as? TvPage)?.requestInitialFocus()
            }
            transaction.commit()
            updateNavigationSelection()

            if (moveFocus) {
                navItems.firstOrNull { it.first == destination }?.second?.let { navView ->
                    navView.post {
                        if (!isFinishing && !isDestroyed) navView.requestFocus()
                    }
                }
            }
        }.onFailure {
            Timber.e(it, "Unable to navigate to $destination")
            pendingDestination = destination to moveFocus
        }
    }

    private fun updateNavigationSelection() {
        if (!::navItems.isInitialized) return
        navItems.forEach { (destination, view) ->
            val selected = destination == currentDestination
            view.isSelected = selected
            view.alpha = if (selected) 1f else 0.72f
            view.setTextColor(ContextCompat.getColor(this, if (selected) R.color.accent else R.color.text_primary))
        }
    }

    fun requestWifiDirectPermissions() {
        if (isFinishing || isDestroyed) return
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) return
        runCatching { wifiDirectPermissionLauncher.launch(missing.toTypedArray()) }
            .onFailure { Timber.w(it, "Unable to request Wi-Fi Direct permissions") }
    }

    fun startCastService() {
        runCatching { ContextCompat.startForegroundService(this, Intent(this, CastReceiverService::class.java)) }
            .onFailure { Timber.e(it, "Unable to start receiver service") }
    }

    override fun onPause() {
        backPressExitGate.reset()
        super.onPause()
    }
}
