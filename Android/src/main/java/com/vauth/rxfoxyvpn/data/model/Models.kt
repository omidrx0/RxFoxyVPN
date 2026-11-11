package com.vauth.rxfoxyvpn.data.model

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

enum class LoginStepState { CREDENTIALS, TWO_FACTOR }

data class VpnProtocol(
    val name: String,
    val host: String = "",
    val port: Int = 0,
    val scheme: String = "",
    val templateString: String = "",
)

data class VpnServerNode(
    val hostname: String,
    val port: Int = 0,
    val quarantined: Boolean = false,
    val protocols: List<VpnProtocol> = emptyList(),
)

data class VpnCity(
    val name: String,
    val code: String,
    val servers: List<VpnServerNode> = emptyList(),
)

data class VpnCountry(
    val name: String,
    val code: String,
    val cities: List<VpnCity> = emptyList(),
)

data class ProxyCandidate(
    val host: String,
    val port: Int,
    val countryCode: String,
    val countryName: String,
    val cityCode: String = "",
) {
    val authority: String get() = "$host:$port"
}

/**
 * Explicit 4-state model for subscription bandwidth/quota status:
 *  1. [Unlimited] — API explicitly confirmed the subscription has no bandwidth cap.
 *  2. [Limited]   — API returned valid finite total (> 0) and remaining (0..limit) bytes
 *                   (including zero-usage accounts where `remainingBytes == limitBytes`).
 *  3. [Error]     — Network, transport, authentication, or HTTP error while fetching status.
 *  4. [Unknown]   — Response was received, but lacks explicit unlimited confirmation and lacks
 *                   complete, consistent quota figures.
 */
sealed interface SubscriptionQuotaState {
    data object Unlimited : SubscriptionQuotaState

    data class Limited(
        val limitBytes: Long,
        val remainingBytes: Long,
        val resetRaw: String? = null,
    ) : SubscriptionQuotaState {
        val usedBytes: Long
            get() = (limitBytes - remainingBytes).coerceAtLeast(0L)
    }

    data object Unknown : SubscriptionQuotaState

    data class Error(val message: String) : SubscriptionQuotaState
}

data class Entitlement(
    val subscribed: Boolean,
    val uid: String,
    val maxBytes: Long?,
    val limitedBandwidth: Boolean,
    val quotaRemaining: Long? = null,
    val quotaState: SubscriptionQuotaState = SubscriptionQuotaState.Unknown,
) {
    val isUnlimited: Boolean
        get() = quotaState is SubscriptionQuotaState.Unlimited
}

data class RuntimeAuth(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
)
