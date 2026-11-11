package com.vauth.rxfoxyvpn.data.manifest

import android.content.Context
import com.vauth.rxfoxyvpn.data.ControlPlaneHttp
import com.vauth.rxfoxyvpn.data.SettingsStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Process-wide singleton access to [RemoteManifestRepository] and [AnnouncementManager].
 * Installed from `RxFoxyVpnApp.onCreate` before UI or HTTP traffic starts.
 */
object ManifestRuntime {
    val manifestHttpClient: OkHttpClient by lazy {
        ControlPlaneHttp.directClient.newBuilder()
            .followRedirects(true)
            .followSslRedirects(false)
            .connectTimeout(ManifestConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(ManifestConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(ManifestConfig.CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var installedRepo: RemoteManifestRepository? = null

    @Volatile
    private var installedAnnouncements: AnnouncementManager? = null

    val repository: RemoteManifestRepository
        get() = installedRepo ?: synchronized(this) {
            installedRepo ?: RemoteManifestRepository(
                httpClient = { manifestHttpClient },
                cacheBackend = InMemoryManifestCacheBackend(),
                urlProvider = { ManifestConfig.resolveUrls() },
            ).also { installedRepo = it }
        }

    val announcements: AnnouncementManager
        get() = installedAnnouncements ?: synchronized(this) {
            installedAnnouncements ?: AnnouncementManager(InMemoryAnnouncementBackend()).also {
                installedAnnouncements = it
            }
        }

    fun install(context: Context, settingsStore: SettingsStore) {
        synchronized(this) {
            installedRepo = RemoteManifestRepository(
                httpClient = { manifestHttpClient },
                cacheBackend = SharedPrefsManifestCacheBackend(context),
                urlProvider = {
                    ManifestConfig.resolveUrls(
                        primaryOverride = settingsStore.manifestPrimaryUrlOverride,
                        fallbackOverride = settingsStore.manifestFallbackUrlOverride,
                    )
                },
            )
            installedAnnouncements = AnnouncementManager(
                backend = SharedPrefsAnnouncementBackend(context),
            )
        }
    }
}
