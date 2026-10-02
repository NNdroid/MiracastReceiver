package com.weekd.miracastreceiver.web

import java.net.URI

/** Validated HTTP(S) media request accepted by the authenticated WebUI API. */
data class MediaUrlRequest(
    val url: String,
    val title: String,
    val headers: Map<String, String>
) {
    companion object {
        const val MAX_URL_LENGTH = 4096
        const val MAX_TITLE_LENGTH = 160
        const val MAX_HEADERS = 12
        const val MAX_HEADER_NAME_LENGTH = 64
        const val MAX_HEADER_VALUE_LENGTH = 2048

        private val blockedHeaders = setOf(
            "host", "content-length", "connection", "transfer-encoding",
            "proxy-authorization", "proxy-authenticate", "x-api-token"
        )

        fun parse(url: String, title: String = "", headers: Map<String, String> = emptyMap()): MediaUrlRequest {
            val cleanUrl = url.trim()
            require(cleanUrl.isNotEmpty()) { "url_required" }
            require(cleanUrl.length <= MAX_URL_LENGTH) { "url_too_long" }

            val uri = runCatching { URI(cleanUrl) }.getOrElse { throw IllegalArgumentException("invalid_url") }
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https") { "unsupported_url_scheme" }
            require(!uri.host.isNullOrBlank()) { "url_host_required" }
            require(uri.userInfo.isNullOrBlank()) { "url_userinfo_not_allowed" }

            val cleanTitle = title.trim().take(MAX_TITLE_LENGTH)
            require(headers.size <= MAX_HEADERS) { "too_many_headers" }
            val cleanHeaders = linkedMapOf<String, String>()
            headers.forEach { (rawName, rawValue) ->
                val name = rawName.trim()
                val value = rawValue.trim()
                require(name.isNotEmpty() && name.length <= MAX_HEADER_NAME_LENGTH) { "invalid_header_name" }
                require(name.all { it.isLetterOrDigit() || it == '-' }) { "invalid_header_name" }
                require(name.lowercase() !in blockedHeaders) { "blocked_header" }
                require(value.length <= MAX_HEADER_VALUE_LENGTH) { "header_value_too_long" }
                require(!value.contains('\r') && !value.contains('\n')) { "invalid_header_value" }
                if (value.isNotEmpty()) cleanHeaders[name] = value
            }

            return MediaUrlRequest(cleanUrl, cleanTitle, cleanHeaders)
        }
    }
}
