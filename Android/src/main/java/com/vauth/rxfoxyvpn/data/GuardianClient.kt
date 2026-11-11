package com.vauth.rxfoxyvpn.data

import com.vauth.rxfoxyvpn.data.model.Entitlement
import com.vauth.rxfoxyvpn.data.model.SubscriptionQuotaState
import com.vauth.rxfoxyvpn.data.relay.RelayPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

const val GUARDIAN_ENDPOINT_DEFAULT = "https://vpn.mozilla.org"

open class GuardianHttpError(message: String, val statusCode: Int? = null) : Exception(message)

/**
 * Guardian answered HTTP 451 (unavailable_in_region) and the recovery in
 * [com.vauth.rxfoxyvpn.data.relay.RelayFallbackInterceptor] did not help.
 * [relaysTried] is "none", "personal", "shared" or "personal,shared": what was actually attempted.
 */
class GuardianRegionBlockedError(message: String, val relaysTried: String) : GuardianHttpError(message, 451)
class QuotaExceededError(message: String) : Exception(message)
class TokenInvalidError(message: String) : Exception(message)

data class ProxyPass(
    val token: String,
    val expiresAtEpochSeconds: Long?,
    val quotaMax: Long? = null,
    val quotaRemaining: Long? = null,
    val quotaReset: Long? = null,
    val quotaUnlimited: Boolean = false,
)

/**
 * Result of inspecting Guardian's `X-Quota-*` HTTP headers on `/api/v1/fpn/token`.
 */
sealed interface ParsedTokenQuota {
    data object Absent : ParsedTokenQuota
    data object ExplicitUnlimited : ParsedTokenQuota
    data class Limited(
        val limitBytes: Long,
        val remainingBytes: Long,
        val resetRaw: String? = null,
    ) : ParsedTokenQuota
    data object Invalid : ParsedTokenQuota
}

class GuardianClient(
    private val challengeSolver: FastlyChallengeSolver = FastlyChallengeSolver(ControlPlaneHttp.cookieJar),
    private val httpClientProvider: () -> OkHttpClient = { ControlPlaneHttp.client },
) {

    suspend fun fetchProxyPass(endpoint: String, accessToken: String): ProxyPass = withContext(Dispatchers.IO) {
        val url = "${endpoint.trimEnd('/')}/api/v1/fpn/token"
        authorizedRequest("GET", url, accessToken).use { resp ->
            when (resp.code) {
                401, 403 -> throw TokenInvalidError("access token was rejected by Guardian")
                429 -> throw QuotaExceededError("account proxy quota exceeded")
                451 -> throw regionBlocked(resp)
            }
            val text = resp.body?.string().orEmpty()
            if (resp.code != 200) {
                throw GuardianHttpError("failed to fetch proxy pass: HTTP ${resp.code}", resp.code)
            }
            val body = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw GuardianHttpError("invalid JSON in proxy pass response", resp.code)
            }
            val token = body.optString("token", "")
            if (token.isEmpty()) throw GuardianHttpError("proxy pass response did not contain a token")
            val parsedQuota = parseTokenQuotaHeaders(resp.headers)
            val unlimited = parsedQuota is ParsedTokenQuota.ExplicitUnlimited
            val limited = parsedQuota as? ParsedTokenQuota.Limited
            ProxyPass(
                token = token,
                expiresAtEpochSeconds = body.optLong("expires_at", 0L).takeIf { it > 0 }
                    ?: jwtExpiryEpochSeconds(token),
                quotaMax = limited?.limitBytes ?: resp.header("X-Quota-Limit")?.trim()?.toLongOrNull(),
                quotaRemaining = limited?.remainingBytes ?: resp.header("X-Quota-Remaining")?.trim()?.toLongOrNull(),
                quotaReset = resp.header("X-Quota-Reset")?.trim()?.toLongOrNull(),
                quotaUnlimited = unlimited,
            )
        }
    }

    /**
     * Fetches Guardian subscription status (`/api/v1/fpn/status`) and probes `/api/v1/fpn/token`
     * (`HEAD` first to avoid minting unnecessary passes, falling back to `GET` on 403/405) to
     * accurately classify the account into [SubscriptionQuotaState.Unlimited],
     * [SubscriptionQuotaState.Limited], or [SubscriptionQuotaState.Unknown].
     *
     * Throws on genuine transport/HTTP/auth errors so callers map failures to
     * [SubscriptionQuotaState.Error].
     */
    suspend fun fetchUserInfo(endpoint: String, accessToken: String): Entitlement = withContext(Dispatchers.IO) {
        val baseUrl = endpoint.trimEnd('/')
        val statusUrl = "$baseUrl/api/v1/fpn/status"
        val statusText = authorizedRequest("GET", statusUrl, accessToken).use { resp ->
            when (resp.code) {
                401, 403 -> throw TokenInvalidError("access token was rejected by Guardian (HTTP ${resp.code})")
                451 -> throw regionBlocked(resp)
            }
            val text = resp.body?.string().orEmpty()
            if (resp.code != 200) {
                throw GuardianHttpError("failed to fetch account info: HTTP ${resp.code}", resp.code)
            }
            text
        }

        val trimmedStatus = statusText.trim()
        if (trimmedStatus.isEmpty()) {
            return@withContext Entitlement(
                subscribed = false,
                uid = "",
                maxBytes = null,
                limitedBandwidth = true,
                quotaRemaining = null,
                quotaState = SubscriptionQuotaState.Unknown,
            )
        }

        val statusJson = try {
            JSONObject(trimmedStatus)
        } catch (e: Exception) {
            throw GuardianHttpError("invalid account status JSON")
        }

        val tokenQuota = runCatching { probeTokenQuota(baseUrl, accessToken) }.getOrNull()
        evaluateEntitlement(statusJson, tokenQuota)
    }

    suspend fun activateGuardian(endpoint: String, accessToken: String): Entitlement = withContext(Dispatchers.IO) {
        val url = "${endpoint.trimEnd('/')}/api/v1/fpn/activate"
        authorizedRequest("POST", url, accessToken).use { resp ->
            when (resp.code) {
                401, 403 -> throw TokenInvalidError("access token was rejected by Guardian (HTTP ${resp.code})")
                451 -> throw regionBlocked(resp)
            }
            val text = resp.body?.string().orEmpty()
            if (resp.code != 200) {
                throw GuardianHttpError("failed to activate guardian entitlement: HTTP ${resp.code}", resp.code)
            }
            val trimmed = text.trim()
            if (trimmed.isEmpty()) {
                return@withContext Entitlement(
                    subscribed = false,
                    uid = "",
                    maxBytes = null,
                    limitedBandwidth = true,
                    quotaRemaining = null,
                    quotaState = SubscriptionQuotaState.Unknown,
                )
            }
            val json = try {
                JSONObject(trimmed)
            } catch (e: Exception) {
                throw GuardianHttpError("invalid activation response JSON")
            }
            evaluateEntitlement(json, null)
        }
    }

    /**
     * Probes `/api/v1/fpn/token` using `HEAD` first (so reading quota does not mint a new JWT pass),
     * falling back to `GET` if `HEAD` is rejected (403/405) or stripped of `X-Quota-*` headers by a
     * relay/CDN edge. Also parses valid `X-Quota-*` headers on HTTP `429 Too Many Requests`.
     */
    internal suspend fun probeTokenQuota(baseEndpoint: String, accessToken: String): ParsedTokenQuota {
        val tokenUrl = "${baseEndpoint.trimEnd('/')}/api/v1/fpn/token"
        val headParsed = authorizedRequest("HEAD", tokenUrl, accessToken).use { resp ->
            when (resp.code) {
                200, 204, 429 -> parseTokenQuotaHeaders(resp.headers)
                else -> ParsedTokenQuota.Absent
            }
        }
        if (headParsed !is ParsedTokenQuota.Absent) {
            return headParsed
        }
        return authorizedRequest("GET", tokenUrl, accessToken).use { resp ->
            when (resp.code) {
                200, 429 -> parseTokenQuotaHeaders(resp.headers)
                else -> ParsedTokenQuota.Absent
            }
        }
    }

    private fun regionBlocked(resp: Response): GuardianRegionBlockedError {
        val tried = resp.header(RelayPolicy.RECOVERY_EXHAUSTED_HEADER) ?: "none"
        val text = if (tried == "none") {
            LanguageManager.t(
                "relay_err_region_none",
                "Mozilla refused this region (HTTP 451). Turn on the shared relay or add a personal relay in Settings > Control-plane relay.",
            )
        } else {
            LanguageManager.t(
                "relay_err_region_all_failed",
                "Mozilla refused this region (HTTP 451) and no relay could recover it. Add a personal relay in Settings > Control-plane relay.",
            )
        }
        return GuardianRegionBlockedError(text, tried)
    }

    private suspend fun authorizedRequest(method: String, url: String, accessToken: String): Response {
        val client = httpClientProvider()
        fun buildRequest(): Request {
            val builder = Request.Builder().url(url).applyMozillaVpnHeaders()
            builder.header("Authorization", "Bearer $accessToken")
            when (method) {
                "HEAD" -> builder.head()
                "POST" -> {
                    builder.header("Content-Type", "application/json")
                    builder.post(ByteArray(0).toRequestBody(null))
                }
                else -> {
                    builder.header("Content-Type", "application/json")
                    builder.get()
                }
            }
            return builder.build()
        }
        var response = client.newCall(buildRequest()).execute()
        if (response.code == 406) {
            response.close()
            challengeSolver.solveAndInstall()
            response = client.newCall(buildRequest()).execute()
        }
        return response
    }

    companion object {
        /**
         * Parses `X-Quota-Unlimited`, `X-Quota-Limit`, `X-Quota-Remaining`, and `X-Quota-Reset`
         * matching Mozilla's official `ProxyUsage.fromResponse` in `GuardianTypes.sys.mjs`:
         *
         * - `X-Quota-Unlimited: true` ALWAYS takes priority -> [ParsedTokenQuota.ExplicitUnlimited]
         *   (Guardian still includes the nominal `53687091200` / 50 GB `X-Quota-Limit` header alongside
         *   `X-Quota-Unlimited: true` on unlimited accounts).
         * - Otherwise, `X-Quota-Limit > 0` AND `0 <= X-Quota-Remaining <= X-Quota-Limit` -> [ParsedTokenQuota.Limited].
         * - `X-Quota-Limit == 0` (without `X-Quota-Unlimited: true`), missing counterpart header,
         *   negative numbers, malformed values, or `remaining > limit` -> [ParsedTokenQuota.Invalid].
         * - No `X-Quota-*` headers at all -> [ParsedTokenQuota.Absent].
         */
        fun parseTokenQuotaHeaders(headers: Headers): ParsedTokenQuota {
            val unlimitedRaw = headers["X-Quota-Unlimited"]?.trim()
            val limitRaw = headers["X-Quota-Limit"]?.trim()
            val remainingRaw = headers["X-Quota-Remaining"]?.trim()
            val resetRaw = headers["X-Quota-Reset"]?.trim()?.takeIf { it.isNotEmpty() }

            if (unlimitedRaw != null) {
                if (unlimitedRaw.equals("true", ignoreCase = true)) {
                    return ParsedTokenQuota.ExplicitUnlimited
                }
                if (!unlimitedRaw.equals("false", ignoreCase = true)) {
                    return ParsedTokenQuota.Invalid
                }
            }

            val hasAnyLimitHeader = !limitRaw.isNullOrEmpty() || !remainingRaw.isNullOrEmpty()
            if (hasAnyLimitHeader) {
                val limit = limitRaw?.toLongOrNull()
                val remaining = remainingRaw?.toLongOrNull()
                if (limit != null && limit > 0L && remaining != null && remaining in 0L..limit) {
                    return ParsedTokenQuota.Limited(
                        limitBytes = limit,
                        remainingBytes = remaining,
                        resetRaw = resetRaw,
                    )
                }
                return ParsedTokenQuota.Invalid
            }

            return ParsedTokenQuota.Absent
        }

        /**
         * Evaluates [Entitlement] and [SubscriptionQuotaState] strictly from `/api/v1/fpn/status`
         * JSON and optional `/api/v1/fpn/token` headers, aligned with Mozilla's `GuardianTypes.sys.mjs`
         * and `IPPUsageHelper.sys.mjs`:
         *
         * Note: In Guardian's `/api/v1/fpn/status` JSON schema, `"maxBytes"` (e.g. `"53687091200"` = 50 GB)
         * is a REQUIRED field even when `"limited_bandwidth": false` or `"subscribed": true`! Therefore:
         * - Explicit unlimited signals (`X-Quota-Unlimited: true`, `"limited_bandwidth": false`,
         *   `"unlimited": true`, or paid Mozilla VPN `"subscribed": true` with `limited_bandwidth != true`)
         *   classify the account as [SubscriptionQuotaState.Unlimited] (`نامحدود ∞`) rather than 50 GB.
         * - Capped accounts (`limited_bandwidth == true` or non-subscribed accounts without an explicit
         *   unlimited flag) with valid finite quota classify as [SubscriptionQuotaState.Limited].
         * - Never infers [SubscriptionQuotaState.Unlimited] from `null`, `0`, empty JSON `{}`,
         *   missing fields, or malformed headers.
         */
        fun evaluateEntitlement(
            body: JSONObject,
            tokenQuota: ParsedTokenQuota? = null,
        ): Entitlement {
            if (body.length() == 0 && tokenQuota !is ParsedTokenQuota.Limited && tokenQuota !is ParsedTokenQuota.ExplicitUnlimited) {
                return Entitlement(
                    subscribed = false,
                    uid = "",
                    maxBytes = null,
                    limitedBandwidth = true,
                    quotaRemaining = null,
                    quotaState = SubscriptionQuotaState.Unknown,
                )
            }

            val explicitSubscribed: Boolean? = if (body.has("subscribed") && !body.isNull("subscribed")) {
                body.opt("subscribed") as? Boolean
            } else {
                null
            }
            val subscribed = explicitSubscribed ?: false

            val uid: String = if (body.has("uid") && !body.isNull("uid")) {
                body.opt("uid")?.toString()?.trim().orEmpty()
            } else {
                ""
            }
            val hasAccountIdentity = explicitSubscribed != null || uid.isNotEmpty()

            val explicitLimitedBandwidth: Boolean? =
                if (body.has("limited_bandwidth") && !body.isNull("limited_bandwidth")) {
                    body.opt("limited_bandwidth") as? Boolean
                } else {
                    null
                }

            val explicitUnlimitedField: Boolean? =
                if (body.has("unlimited") && !body.isNull("unlimited")) {
                    body.opt("unlimited") as? Boolean
                } else {
                    null
                }

            val statusMaxBytes = parseStrictNonNegativeLong(body, "maxBytes")
            val statusRemainingBytes = parseStrictNonNegativeLong(body, "quotaRemaining")
                ?: parseStrictNonNegativeLong(body, "remainingBytes")

            val statusExplicitlyUnlimited = hasAccountIdentity && (
                explicitLimitedBandwidth == false ||
                    (explicitUnlimitedField == true && explicitLimitedBandwidth != true) ||
                    (explicitSubscribed == true && explicitLimitedBandwidth != true)
                )

            val quotaState: SubscriptionQuotaState = when {
                // 1. Corrupt / inconsistent `X-Quota-*` headers are never trusted as Unlimited.
                tokenQuota is ParsedTokenQuota.Invalid -> SubscriptionQuotaState.Unknown

                // 2. Explicit `X-Quota-Unlimited: true` from `/api/v1/fpn/token`.
                tokenQuota is ParsedTokenQuota.ExplicitUnlimited -> SubscriptionQuotaState.Unlimited

                // 3. Explicit unlimited entitlement in `/api/v1/fpn/status` (`"limited_bandwidth": false`,
                //    `"unlimited": true`, or active Mozilla VPN `"subscribed": true` without a cap),
                //    even though Guardian still populates the required `"maxBytes": "53687091200"` field.
                statusExplicitlyUnlimited -> SubscriptionQuotaState.Unlimited

                // 4. Authoritative finite quota from `/api/v1/fpn/token` headers (including 0 usage & 0 remaining).
                tokenQuota is ParsedTokenQuota.Limited -> SubscriptionQuotaState.Limited(
                    limitBytes = tokenQuota.limitBytes,
                    remainingBytes = tokenQuota.remainingBytes,
                    resetRaw = tokenQuota.resetRaw,
                )

                // 5. Complete finite quota directly in status payload (> 0 total and 0 <= remaining <= total).
                statusMaxBytes != null && statusMaxBytes > 0L &&
                    statusRemainingBytes != null && statusRemainingBytes in 0L..statusMaxBytes ->
                    SubscriptionQuotaState.Limited(
                        limitBytes = statusMaxBytes,
                        remainingBytes = statusRemainingBytes,
                    )

                // 6. Anything else (null quota, maxBytes == 0 alone, missing remainingBytes, empty JSON, etc.)
                else -> SubscriptionQuotaState.Unknown
            }

            val resolvedMax = when (quotaState) {
                is SubscriptionQuotaState.Limited -> quotaState.limitBytes
                is SubscriptionQuotaState.Unlimited -> null
                else -> statusMaxBytes?.takeIf { it > 0L }
            }
            val resolvedRemaining = when (quotaState) {
                is SubscriptionQuotaState.Limited -> quotaState.remainingBytes
                is SubscriptionQuotaState.Unlimited -> null
                else -> statusRemainingBytes
            }
            val resolvedLimitedFlag = quotaState !is SubscriptionQuotaState.Unlimited

            return Entitlement(
                subscribed = subscribed,
                uid = uid,
                maxBytes = resolvedMax,
                limitedBandwidth = resolvedLimitedFlag,
                quotaRemaining = resolvedRemaining,
                quotaState = quotaState,
            )
        }

        private fun parseStrictNonNegativeLong(json: JSONObject, key: String): Long? {
            if (!json.has(key) || json.isNull(key)) return null
            val raw = json.opt(key) ?: return null
            val parsed = when (raw) {
                is Long -> raw
                is Int -> raw.toLong()
                is Number -> {
                    val d = raw.toDouble()
                    val l = raw.toLong()
                    if (d != l.toDouble()) return null
                    l
                }
                is String -> raw.trim().toLongOrNull()
                else -> null
            } ?: return null
            return parsed.takeIf { it >= 0L }
        }
    }
}

internal fun jwtExpiryEpochSeconds(token: String): Long? {
    val parts = token.split('.')
    if (parts.size < 2) return null
    val payload = runCatching {
        var b64 = parts[1]
        while (b64.length % 4 != 0) b64 += "="
        String(
            java.util.Base64.getUrlDecoder().decode(b64),
            Charsets.UTF_8,
        )
    }.getOrNull() ?: return null
    return runCatching { JSONObject(payload).optLong("exp", 0L) }.getOrNull()?.takeIf { it > 0 }
}
