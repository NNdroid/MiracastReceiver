package com.weekd.miracastreceiver.miracast

import timber.log.Timber

/**
 * Best-effort endpoint information learned from Android's Wi-Fi P2P framework.
 *
 * Miracast Sources are allowed to choose an RTSP control port other than 7236. Some Android/MIUI
 * sources do this, so the session starter must not assume the Windows-common 7236 value.
 */
object WfdSourceHint {
    data class Snapshot(val ipAddress: String?, val controlPort: Int?)

    @Volatile private var sourceIp: String? = null
    @Volatile private var sourcePort: Int? = null

    fun update(ipAddress: String? = null, controlPort: Int? = null, reason: String = "") {
        if (!ipAddress.isNullOrBlank()) sourceIp = ipAddress
        if (controlPort != null && controlPort in 1..65535) sourcePort = controlPort
        Timber.i("WFD source hint: ip=${sourceIp ?: "?"} port=${sourcePort ?: "?"} reason=$reason")
    }

    fun snapshot(): Snapshot = Snapshot(sourceIp, sourcePort)

    fun clear() {
        sourceIp = null
        sourcePort = null
    }
}
