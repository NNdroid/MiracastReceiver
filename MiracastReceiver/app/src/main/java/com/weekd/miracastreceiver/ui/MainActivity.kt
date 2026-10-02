package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.util.AppSettings
import timber.log.Timber

/** TV navigation shell. Casting continues in the foreground service while pages are Fragments. */
class MainActivity : AppCompatActivity() {

    interface TvPage {
        fun requestInitialFocus() = Unit
    }

    private enum class Destination(val navViewId: Int) {
        HOME(R.id.nav_home),
        PLAYER(R.id.nav_player),
        SETTINGS(R.id.nav_settings),
        ABOUT(R.id.nav_about)
    }

    private val backPressExitGate = BackPressExitGate()
    private var currentDestination = Destination.HOME
    private lateinit var navItems: List<Pair<Destination, TextView>>

    companion object {
        private const val REQUEST_CODE_WIFI_DIRECT = 1001
        private const val FOCUS_SCALE = 1.06f
        private const val FOCUS_ANIMATION_MS = 90L
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
        setupNavigation()
        startCastService()
        if (AppSettings.isMiracastEnabled(this)) requestWifiDirectPermissions()

        if (savedInstanceState == null) {
            showDestination(Destination.HOME, moveFocus = true)
        } else {
            currentDestination = supportFragmentManager.fragments.firstOrNull { !it.isHidden }?.let {
                when (it) {
                    is SettingsFragment -> Destination.SETTINGS
                    is PlayerHubFragment -> Destination.PLAYER
                    is AboutFragment -> Destination.ABOUT
                    else -> Destination.HOME
                }
            } ?: Destination.HOME
            updateNavigationSelection()
        }
    }

    private fun setupNavigation() {
        navItems = listOf(
            Destination.HOME to findViewById(R.id.nav_home),
            Destination.PLAYER to findViewById(R.id.nav_player),
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
                target.animate()
                    .scaleX(scale)
                    .scaleY(scale)
                    .setDuration(FOCUS_ANIMATION_MS)
                    .withLayer()
                    .start()
            }
        }
    }

    private fun fragmentFor(destination: Destination): Fragment = when (destination) {
        Destination.HOME -> HomeFragment()
        Destination.PLAYER -> PlayerHubFragment()
        Destination.SETTINGS -> SettingsFragment()
        Destination.ABOUT -> AboutFragment()
    }

    private fun showDestination(destination: Destination, moveFocus: Boolean) {
        if (destination == currentDestination) {
            if (!moveFocus) {
                (supportFragmentManager.findFragmentByTag(destination.name) as? TvPage)?.requestInitialFocus()
            }
            return
        }

        val manager = supportFragmentManager
        val target = manager.findFragmentByTag(destination.name) ?: fragmentFor(destination)
        val transaction = manager.beginTransaction().setReorderingAllowed(true)

        manager.fragments.forEach { fragment ->
            if (fragment != target && !fragment.isHidden) transaction.hide(fragment)
        }

        if (target.isAdded) {
            transaction.show(target)
        } else {
            transaction.add(R.id.fragment_container, target, destination.name)
        }

        currentDestination = destination
        transaction.runOnCommit {
            if (!moveFocus) (target as? TvPage)?.requestInitialFocus()
        }
        transaction.commit()
        updateNavigationSelection()

        if (moveFocus) {
            val navView = navItems.first { it.first == destination }.second
            navView.post { navView.requestFocus() }
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
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_WIFI_DIRECT)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_WIFI_DIRECT) return
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startCastService()
        } else {
            Toast.makeText(this, R.string.wifi_direct_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    fun startCastService() {
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, CastReceiverService::class.java))
        }.onFailure { Timber.e(it, "Unable to start receiver service") }
    }

    override fun onPause() {
        backPressExitGate.reset()
        super.onPause()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (currentDestination != Destination.HOME) {
            showDestination(Destination.HOME, moveFocus = true)
            backPressExitGate.reset()
            return
        }
        if (backPressExitGate.registerPress(SystemClock.elapsedRealtime())) {
            finish()
        } else {
            Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show()
        }
    }
}
