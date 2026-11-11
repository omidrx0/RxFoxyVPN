plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val hevSocks5TunnelVersion = "2.17.1"
val hevSocks5TunnelDir = file("src/main/jni")

val fetchHevSocks5Tunnel = tasks.register<Exec>("fetchHevSocks5Tunnel") {
    val marker = File(hevSocks5TunnelDir, "Android.mk")
    outputs.file(marker)
    onlyIf { !marker.exists() }
    doFirst { hevSocks5TunnelDir.mkdirs() }
    commandLine(
        "git", "clone",
        "--branch", hevSocks5TunnelVersion,
        "--depth", "1",
        "--recursive",
        "--shallow-submodules",
        "https://github.com/heiher/hev-socks5-tunnel.git",
        hevSocks5TunnelDir.absolutePath,
    )
}

tasks.matching { it.name.startsWith("externalNativeBuild") || it.name.contains("NdkBuild") }
    .configureEach { dependsOn(fetchHevSocks5Tunnel) }
tasks.named("preBuild").configure { dependsOn(fetchHevSocks5Tunnel) }

val releaseKeystoreFile = rootProject.file("foxyvpn-release.jks")

val releaseKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
val releaseKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
val releaseStorePassword = System.getenv("SIGNING_STORE_PASSWORD")

val hasReleaseSigning = releaseKeystoreFile.exists() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank()

android {
    namespace = "com.vauth.rxfoxyvpn"
    compileSdk = 35

    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.vauth.rxfoxyvpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.5"

        buildConfigField(
            "String",
            "MANIFEST_PRIMARY_URL",
            "\"https://raw.githubusercontent.com/arman-org/update-manifests/main/arman-vpn-client/updates.json\"",
        )
        buildConfigField(
            "String",
            "MANIFEST_FALLBACK_URL",
            "\"https://cdn.jsdelivr.net/gh/arman-org/update-manifests@main/arman-vpn-client/updates.json\"",
        )
        val sharedRelaySecret = (project.findProperty("SHARED_RELAY_SECRET") as? String)
            ?: System.getenv("RXFOXY_SHARED_RELAY_SECRET")
            ?: ""
        buildConfigField(
            "String",
            "SHARED_RELAY_SECRET",
            "\"${sharedRelaySecret.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )

        externalNativeBuild {
            ndkBuild {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
                arguments += listOf(
                    "APP_PLATFORM=android-26",
                    "APP_CFLAGS=-DPKGNAME=com/vauth/rxfoxyvpn/vpn/tun -DCLSNAME=HevSocks5Tunnel",
                )
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    // One build -> a separate APK per CPU (ABI) + one universal APK that contains all of them.
    // Output: app/build/outputs/apk/<buildType>/app-arm64-v8a-*.apk, app-armeabi-v7a-*.apk,
    //         app-x86_64-*.apk, app-x86-*.apk and app-universal-*.apk
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = true
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")

            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // No release keystore/env vars found: sign with the debug key so the APKs are installable.
                // Delete this else-branch if you want unsigned release APKs instead.
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            all {
                it.maxHeapSize = "256m"
            }
        }
    }

    packaging {
        resources.excludes.add("META-INF/INDEX.LIST")
        resources.excludes.add("META-INF/*.SF")
        resources.excludes.add("META-INF/*.DSA")
        resources.excludes.add("META-INF/*.RSA")
        resources.pickFirsts.add("META-INF/io.netty.versions.properties")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.4.0-alpha10")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Browser Sign-in: origin-restricted WebMessageListener for Mozilla's WebChannel bridge.
    implementation("androidx.webkit:webkit:1.12.1")

    implementation("io.netty:netty-handler:4.1.115.Final")
    implementation("io.netty:netty-codec-http2:4.1.115.Final")
    implementation("io.netty:netty-transport:4.1.115.Final")

    implementation("io.netty:netty-handler-proxy:4.1.115.Final")

    implementation("org.conscrypt:conscrypt-android:2.7.0")
    implementation("com.caverock:androidsvg-aar:1.4")

    testImplementation("junit:junit:4.13.2")
    // The Android SDK's org.json is a stub in local unit tests; use the real implementation there.
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
