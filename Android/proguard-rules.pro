# sing-box / libbox (gomobile): reflection and JNI bridge classes must survive R8.
-keep class io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**
-keep class go.** { *; }
-dontwarn go.**
-keep class * implements io.nekohasekai.libbox.PlatformInterface { *; }
-keep class * implements io.nekohasekai.libbox.CommandServerHandler { *; }
-keep class * implements io.nekohasekai.libbox.CommandClientHandler { *; }
-keep class * implements io.nekohasekai.libbox.AutoRedirectHandler { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class org.conscrypt.** { *; }
-dontwarn org.conscrypt.**

-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

-dontwarn com.google.errorprone.annotations.**
