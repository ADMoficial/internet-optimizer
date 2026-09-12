# ProGuard / R8 rules for the Internet Optimizer app.

# Keep Gson model classes used for config serialization
-keep class com.internetoptimizer.app.AppConfig { *; }
-keep class com.internetoptimizer.app.ProxyType { *; }

# Keep Kotlin metadata used by Compose and coroutines
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Keep Compose runtime classes
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.flow.*

# Keep OkHttp / Gson (standard rules)
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.code.gson.** { *; }

# Keep Cronet (if initialized via reflection)
-keep class com.google.android.gms.net.** { *; }
-dontwarn com.google.android.gms.net.*

# Native library — keep all JNI entry points
-keepclasseswithmembernames class com.internetoptimizer.tunnel.NativeTunnel {
    *;
}

# Keep the main activity and service class names
-keep class com.internetoptimizer.app.MainActivity { *; }
-keep class com.internetoptimizer.app.OptimizerApp { *; }
-keep class com.internetoptimizer.tunnel.OptimizerVpnService { *; }
-keep class com.internetoptimizer.tunnel.TunnelService { *; }
-keep class com.internetoptimizer.tunnel.TunnelManager { *; }
-keep class com.internetoptimizer.network.NetworkClient { *; }
-keep class com.internetoptimizer.network.SpeedTest { *; }
