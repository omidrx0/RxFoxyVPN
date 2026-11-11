package com.vauth.rxfoxyvpn.data.relay

import okhttp3.HttpUrl

/**
 * Control-plane relay settings. Independent of the VPN server selection and of the upstream
 * SOCKS5/HTTP proxy settings: a relay only ever carries Mozilla sign-in / Guardian / server-list requests.
 *
 * Priority (clearly defined):
 *  1. Direct request.
 *  2. After HTTP 451 on Guardian only: the doubled-leading-slash direct retry.
 *  3. Personal relay, when enabled and valid.
 *  4. Shared relay, when enabled, the request is a Guardian request and personal-only mode is off.
 */
data class RelayConfig(
    val sharedEnabled: Boolean = true,
    val personalEnabled: Boolean = false,
    val personalUrl: String = "",
    val personalOnly: Boolean = false,
    val sharedSecret: String = "",
) {
    /** The validated personal relay, or null when disabled or invalid. The saved URL is kept either way. */
    val personalRelay: HttpUrl?
        get() = if (personalEnabled) RelayPolicy.validatePersonalRelayUrl(personalUrl).getOrNull() else null

    /** Personal-only mode only has meaning while the personal relay is enabled. */
    val sharedAllowed: Boolean
        get() = sharedEnabled && !(personalEnabled && personalOnly)
}

fun interface RelayConfigSource {
    fun current(): RelayConfig
}

fun interface RelayLogger {
    fun log(message: String)
}
