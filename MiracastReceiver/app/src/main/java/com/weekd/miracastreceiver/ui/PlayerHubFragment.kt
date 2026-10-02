package com.weekd.miracastreceiver.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.web.RuntimeState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Lightweight TV-side playback status page; remote playback actions remain available in WebUI. */
class PlayerHubFragment : Fragment(), MainActivity.TvPage {
    private lateinit var tvState: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvSource: TextView
    private lateinit var tvUri: TextView
    private var refreshJob: Job? = null
    private var lastSnapshot: RuntimeState.PlaybackSnapshot? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View =
        inflater.inflate(R.layout.fragment_player_hub, container, false)

    override fun onViewCreated(view: View, state: Bundle?) {
        tvState = view.findViewById(R.id.tv_player_state)
        tvTitle = view.findViewById(R.id.tv_player_title)
        tvSource = view.findViewById(R.id.tv_player_source)
        tvUri = view.findViewById(R.id.tv_player_uri)
    }

    override fun onStart() {
        super.onStart()
        refreshJob?.cancel()
        refreshJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                val snapshot = RuntimeState.playbackSnapshot()
                if (snapshot != lastSnapshot) {
                    render(snapshot)
                    lastSnapshot = snapshot
                }
                delay(1_000)
            }
        }
    }

    override fun onStop() {
        refreshJob?.cancel()
        refreshJob = null
        super.onStop()
    }

    private fun render(snapshot: RuntimeState.PlaybackSnapshot) {
        if (!isAdded) return
        tvState.text = getString(R.string.player_hub_state, snapshot.state)
        tvTitle.text = snapshot.title.ifBlank { getString(R.string.player_hub_nothing_playing) }
        tvSource.text = getString(R.string.player_hub_source, snapshot.source.ifBlank { "-" })
        tvUri.text = snapshot.uri.ifBlank { getString(R.string.player_hub_webui_hint) }
    }
}
