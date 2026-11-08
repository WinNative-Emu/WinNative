# Project ProGuard / R8 rules. Built APK is `minifyEnabled true`; without
# these keeps, R8's release build strips classes that the runtime reaches
# only via reflection — most notably BouncyCastle's java.security.Provider
# Service registrations (the SPI map BouncyCastleProvider uses to expose
# KeyStore.BKS, MessageDigest.*, etc.).
#
# Crash symptom 2026-05-19 (commit e1cb6c6 onward; not caused by hybrid work):
#   java.security.NoSuchAlgorithmException: BKS KeyStore not available
#   at sun.security.jca.GetInstance.getInstance:159
#   at java.security.Security.getImpl:628
#   at java.security.KeyStore.getInstance:901
#   at com.android.org.conscrypt.KeyManagerFactoryImpl.engineInit
#   at OkHttp Platform.systemDefaultTrustManager (obfuscated e8.j.n)
#
# PluviaApp.onCreate calls Security.addProvider(BouncyCastleProvider()),
# which DOES expose "BKS" — but only if R8 didn't strip the Service
# classes BC registers reflectively. The rules below keep them.
# [[project_bks_keystore_crash]]

-keep class org.bouncycastle.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }
-dontwarn org.bouncycastle.**

# BouncyCastle reads service-loader entries from META-INF/services. R8's
# default config copies META-INF/services for the kept classes; the
# explicit `-keep` above ensures the impl classes survive.

# JNI bridge classes — Rust `libwnsteam.so` and the wn-steam-bootstrap C++ lib
# look these up by string in JNI_OnLoad / via FindClass. R8 renames break
# the lookup → JNI_OnLoad returns JNI_ERR and the entire native side dies.
# Crash 2026-05-19: "JNI_ERR returned from JNI_OnLoad in libwnsteam.so"
# was triggered by R8 renaming WnConnectionObserver and the auth/library/
# session callback observers the native JNI layer finds by name.
-keep class com.winlator.cmod.feature.stores.steam.wnsteam.** {
}
-keepclassmembers class com.winlator.cmod.feature.stores.steam.wnsteam.** {
}
# JNI also calls back into PrefManager / SteamService observers + Steam
# data classes (UserFileInfo, AppMetadata, etc.). Keep the feature
# package broadly — the .so depends on the runtime layout, and shaving
# bytes here is high-risk-low-reward.
-keep class com.winlator.cmod.feature.stores.steam.** { *; }
-keepclassmembers class com.winlator.cmod.feature.stores.steam.** { *; }

# Native-method classes: `native` keyword on Kotlin/Java methods is a
# universal R8 keep signal, but pinning the enclosing class avoids
# subclass-rename surprises.
-keepclasseswithmembernames class * {
    native <methods>;
}

# SDL3 (Steam Controller): SDL's native side resolves these classes by name.
-keep class org.winnative.steam.** { *; }

# zstd-jni — the native side does GetFieldID("srcPos", "J") /
# GetFieldID("dstPos", "J") on ZstdInputStreamNoFinalizer and
# similar via JNI; R8 renames the Kotlin/Java fields and the lookup
# crashes the process at sign-in (`libwnsteam.so` decompresses Steam
# CM messages with zstd). Diagnosed 2026-05-19 from
# `Abort message: java.lang.NoSuchFieldError: no "J" field "srcPos"
# in class Lcom/github/luben/zstd/ZstdInputStreamNoFinalizer`.
-keep class com.github.luben.zstd.** { *; }
-keepclassmembers class com.github.luben.zstd.** { *; }
-dontwarn com.github.luben.zstd.**

# Conscrypt / OkHttp use reflection to discover security providers; keep
# the Provider names they look up by string.
-keep class java.security.Provider { *; }
-keep class * extends java.security.Provider { *; }
-keep class * extends java.security.KeyStoreSpi { *; }
-keep class * extends java.security.MessageDigestSpi { *; }
-keep class * extends javax.crypto.CipherSpi { *; }
-keep class * extends javax.crypto.MacSpi { *; }
-keep class * extends javax.crypto.KeyAgreementSpi { *; }
-keep class * extends java.security.KeyFactorySpi { *; }
-keep class * extends javax.crypto.SecretKeyFactorySpi { *; }
-keep class * extends javax.crypto.KeyGeneratorSpi { *; }
-keep class * extends java.security.AlgorithmParametersSpi { *; }
-keep class * extends java.security.SignatureSpi { *; }
-keep class com.winlator.cmod.shared.io.NativeContentIO {
    *;
}

-keep class com.winlator.cmod.shared.util.OnExtractFileListener {
    public java.io.File onExtractFile(java.io.File, long);
}

-keep class com.winlator.cmod.runtime.content.Downloader$DownloadListener {
    public void onProgress(long, long);
}

-dontwarn androidx.window.extensions.WindowExtensions
-dontwarn androidx.window.extensions.WindowExtensionsProvider
-dontwarn androidx.window.extensions.area.ExtensionWindowAreaPresentation
-dontwarn androidx.window.extensions.core.util.function.Consumer
-dontwarn androidx.window.extensions.core.util.function.Function
-dontwarn androidx.window.extensions.core.util.function.Predicate
-dontwarn androidx.window.extensions.layout.DisplayFeature
-dontwarn androidx.window.extensions.layout.FoldingFeature
-dontwarn androidx.window.extensions.layout.WindowLayoutComponent
-dontwarn androidx.window.extensions.layout.WindowLayoutInfo
-dontwarn androidx.window.sidecar.SidecarDeviceState
-dontwarn androidx.window.sidecar.SidecarDisplayFeature
-dontwarn androidx.window.sidecar.SidecarInterface$SidecarCallback
-dontwarn androidx.window.sidecar.SidecarInterface
-dontwarn androidx.window.sidecar.SidecarProvider
-dontwarn androidx.window.sidecar.SidecarWindowLayoutInfo

# snakeyaml's optional JavaBeans introspection path is unreachable on Android
# (java.beans.* is not part of the platform), but R8 still resolves its
# references and fails the release build without these suppressions.
-dontwarn java.beans.**
-dontwarn org.yaml.snakeyaml.introspector.**

# The app talks to its own native code in both directions: ~20 Java/Kotlin
# classes declare native/external methods that resolve to exported
# Java_<package>_<class>_<method> symbols, and libwinlator/libwaylandcomp/
# libwnsteam look callbacks up by name with GetMethodID (for example
# OnExtractFileListener.onExtractFileProgress, WaylandCompositor.onGameFrame,
# GPUImage.setStride, XConnectorEpoll.ConnectionHandler.handleNewConnection).
# Renaming any of those makes the lookup throw NoSuchMethodError at runtime,
# which is exactly what a release build hit while extracting imagefs.
# Keep names so the JNI surface stays addressable; shrinking and optimisation
# still run.
-dontobfuscate

# R8 cannot see calls that cross the JNI boundary, so it treats every Java method
# that only native code calls as dead and removes it. That broke imagefs
# extraction in the first release build: libwinlator looked up
# OnExtractFileListener.onExtractFileProgress with GetMethodID and got
# NoSuchMethodError because the method had been shrunk away. The same applies to
# the other direction - WaylandCompositor's static frame callbacks, the Xconnector
# connection handlers, GPUImage.setStride, and the Kotlin listeners that the Rust
# Steam client calls back (onProgress, onComplete, onAuthResult, ...).
# Keep the app's own classes intact and let R8 shrink only third-party code.
-keep class com.winlator.** { *; }
-keep class com.winnative.** { *; }
-keep class org.love2d.** { *; }

