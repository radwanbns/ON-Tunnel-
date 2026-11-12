# ── ON Tunnel release / hardening rules ──────────────────────────────────
# Full-strength shrinking + obfuscation: renames and repackages every class
# that isn't explicitly kept below, flattens package structure, strips all
# debugging metadata, and removes logging. This is what makes classes.dex
# hard to read and patch after decompilation.

-repackageclasses ''
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively

-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*

# Strip line numbers / source file names from stack traces
-renamesourcefileattribute ''
-keepattributes !SourceFile,!LineNumberTable,!LocalVariableTable,!LocalVariableTypeTable

# Remove all logging calls from the release dex
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# ── Native / JNI bridge – must keep exact names & members ───────────────
-keep class libv2ray.** { *; }
-keep class go.** { *; }
-dontwarn libv2ray.**
-dontwarn go.**
-keep class com.ontunnel.app.NativeIntegrity { *; }

# OpenVPN for Android remote API (AIDL stubs/parcelable must keep their binder descriptors)
-keep class de.blinkt.openvpn.api.** { *; }
-dontwarn de.blinkt.openvpn.api.**

# SSH client (JSch loads its algorithms by class name, so nothing in it may be renamed or removed)
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn org.bouncycastle.**
-dontwarn com.sun.jna.**
-dontwarn javax.security.auth.kerberos.**

# ── Android framework entry points R8 cannot infer on its own ───────────
-keep public class com.ontunnel.app.MainActivity
-keep public class com.ontunnel.app.ONTunnelVpnService
-keepclassmembers class * extends android.app.Service { public <init>(...); }
-keepclassmembers class * extends android.content.BroadcastReceiver { public <init>(...); }

# Parcelable / enum support some libraries rely on via reflection
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Jetpack Compose & Kotlin coroutines ship their own consumer rules via the
# AAR/JAR they're distributed in, so nothing extra is required here.
