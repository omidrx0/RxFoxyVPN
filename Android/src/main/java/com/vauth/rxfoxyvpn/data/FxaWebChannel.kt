package com.vauth.rxfoxyvpn.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mozilla WebChannel bridge for Browser Sign-in, ported from the reference's `FxaWebChannel`
 * (app/lib/ui/browser_signin.dart).
 *
 * accounts.firefox.com, opened with `context=fx_desktop_v3&service=sync&action=email`, talks to "its browser" through
 * `WebChannelMessageToChrome` DOM events. This class plays the browser's part:
 *  - `fxaccounts:fxa_status`      → "no user is signed in, no special capabilities"
 *  - `fxaccounts:can_link_account` → `{ok: true}`
 *  - `fxaccounts:login` / `fxaccounts:oauth_login` → the signed-in session. Its `sessionToken` (64 lowercase hex) and
 *    email are handed to [onSession] once per distinct session.
 *
 * `fxaccounts:login` arrives right after the password, BEFORE any email / authenticator code is entered on the page, so
 * receiving it does not mean the account is verified. The caller must keep the page open and follow the session
 * (see FxaAuthRepository.completeBrowserLogin).
 *
 * Security: this class only parses and validates. The Android layer is responsible for delivering messages only from
 * the trusted origin (WebMessageListener with an origin allow-list) and for replying only to that origin. Message size is
 * bounded, structure is validated, and neither tokens nor message bodies are ever logged here.
 */
class FxaWebChannel(
    /** Runs a JavaScript statement in the page that sent the message. */
    private val reply: (String) -> Unit,
    /** Called once per distinct session token with (email, sessionToken). */
    private val onSession: (email: String, sessionToken: String) -> Unit,
) {
    private var lastToken: String = ""

    /**
     * @param raw JSON string posted by the injected hook: `{"kind":"webchannel","detail":{"id":..,"message":{..}}}`.
     * @return true when the message was a well-formed WebChannel message (handled or deliberately ignored command).
     */
    @Synchronized
    fun handle(raw: String?): Boolean {
        if (raw == null || raw.length > MAX_MESSAGE_CHARS) return false
        val root = try { JSONObject(raw) } catch (_: Exception) { return false }
        if (root.optString("kind") != "webchannel") return false
        val detail = root.optJSONObject("detail") ?: return false
        val id = detail.opt("id") as? String ?: return false
        val message = detail.optJSONObject("message") ?: return false
        val command = message.opt("command") as? String ?: return false
        val messageId = message.opt("messageId")

        when (command) {
            "fxaccounts:fxa_status" -> sendReply(
                id, messageId, command,
                JSONObject().put("capabilities", JSONObject()).put("signedInUser", JSONObject.NULL),
            )
            "fxaccounts:can_link_account" -> sendReply(id, messageId, command, JSONObject().put("ok", true))
            "fxaccounts:login", "fxaccounts:oauth_login" -> login(message.optJSONObject("data"))
            else -> Unit // every other command is left unanswered, as a minimal browser would
        }
        return true
    }

    private fun login(data: JSONObject?) {
        if (data == null) return
        val token = (data.opt("sessionToken") as? String) ?: (data.opt("token") as? String) ?: return
        if (!isValidSessionToken(token)) return
        if (token == lastToken) return // the page repeats its message (for one, once confirmed)
        lastToken = token // a different token means the person signed in again on the page: it replaces the first
        val email = (data.opt("email") as? String).orEmpty().trim().take(320)
        onSession(email, token)
    }

    private fun sendReply(id: String, messageId: Any?, command: String, data: JSONObject) {
        val args = JSONArray()
            .put(id)
            .put(if (messageId == null || messageId == JSONObject.NULL) JSONObject.NULL else messageId)
            .put(command)
            .put(data)
        reply("window.__rxReply.apply(null, $args)")
    }

    companion object {
        private val TOKEN_PATTERN = Regex("^[0-9a-f]{64}$")
        const val MAX_MESSAGE_CHARS = 64 * 1024

        /** Name of the JavaScript object the Android WebMessageListener is registered under. */
        const val BRIDGE_NAME = "__rxfxa"

        /** Origin allowed to talk to the bridge. */
        const val TRUSTED_ORIGIN = "https://accounts.firefox.com"

        fun isValidSessionToken(token: String): Boolean = TOKEN_PATTERN.matches(token)

        /** The sign-in page Firefox desktop opens for Sync: with these parameters it reports the session over its WebChannel. */
        fun loginUrl(email: String): String {
            val builder = okhttp3.HttpUrl.Builder().scheme("https").host("accounts.firefox.com").addPathSegment("")
                .addQueryParameter("context", "fx_desktop_v3")
                .addQueryParameter("service", "sync")
                .addQueryParameter("action", "email")
            if (email.isNotBlank()) builder.addQueryParameter("email", email.trim())
            return builder.build().toString()
        }

        /** Firefox-for-Android user agent: accounts.firefox.com only speaks its WebChannel to what looks like Firefox. */
        const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile; rv:145.0) Gecko/145.0 Firefox/145.0"

        /**
         * Injected at document start. Forwards the page's WebChannel messages to the native listener and defines
         * `window.__rxReply` for the replies. Idempotent.
         */
        const val HOOK_JS = """
(function () {
  if (window.__rxHooked) return;
  window.__rxHooked = true;
  var send = function (o) { try { window.$BRIDGE_NAME.postMessage(JSON.stringify(o)); } catch (e) {} };
  var onChannel = function (e) {
    var d = e.detail;
    if (typeof d === 'string') { try { d = JSON.parse(d); } catch (e2) { return; } }
    if (d && d.message) send({ kind: 'webchannel', detail: d });
  };
  window.addEventListener('WebChannelMessageToChrome', onChannel);
  document.addEventListener('WebChannelMessageToChrome', onChannel);
  window.__rxReply = function (id, messageId, command, data) {
    var detail = { id: id, message: { messageId: messageId, command: command, data: data } };
    window.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
    document.dispatchEvent(new CustomEvent('WebChannelMessageToContent', { detail: detail }));
  };
})();
"""
    }
}

/**
 * Where the Browser Sign-in WebView may navigate. The page itself is accounts.firefox.com; Mozilla's sign-in also uses
 * other Mozilla / Firefox hosts, and accounts created with Google or Apple sign-in (which the reference supports only
 * through the browser) pass through those providers' sign-in hosts. Everything else is blocked and never opened.
 * The bridge is NOT exposed to any of these other hosts: it is bound to [FxaWebChannel.TRUSTED_ORIGIN] only.
 */
object BrowserSignInPolicy {
    private val MOZILLA_DOMAINS = listOf("firefox.com", "mozilla.org", "mozilla.com", "mozilla.net")
    private val IDENTITY_PROVIDER_HOSTS = setOf("accounts.google.com", "appleid.apple.com")

    fun isNavigationAllowed(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        if (!parsed.isHttps || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
        val host = parsed.host.lowercase()
        return MOZILLA_DOMAINS.any { host == it || host.endsWith(".$it") } || host in IDENTITY_PROVIDER_HOSTS
    }

    fun isTrustedBridgeOrigin(origin: String): Boolean = origin == FxaWebChannel.TRUSTED_ORIGIN
}
