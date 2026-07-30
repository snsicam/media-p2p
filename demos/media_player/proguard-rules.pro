# P2P Camera Media Player — ProGuard / R8 Rules
# 保护 JNI native 方法不被混淆/删除

# ── RustBridge JNI 方法 ──
-keep class com.p2pcamera.mediaplayer.RustBridge {
    native <methods>;
    <init>();
}

# ── 保持所有 JNI native 方法 ──
-keepclasseswithmembernames class * {
    native <methods>;
}

# ── 保持 Rust 返回的帧数据不被干扰 ──
-keep class com.p2pcamera.mediaplayer.RustBridgeKt {
    *;
}

# ── kotlinx.coroutines (常见 ProGuard 规则) ──
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── JSON (org.json, Android 内置) ──
-keepclassmembers class org.json.JSONObject {
    <init>(java.lang.String);
    *;
}
-dontwarn org.json.**
