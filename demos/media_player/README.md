# P2P Camera Media Player — Android Demo

基于 libp2p + Rust JNI 的 P2P 摄像头 Android 播放器。

## 架构

```
┌─────────────────────────────────────────────┐
│  MainActivity.kt  (协程 5ms 轮询循环)        │
│                                              │
│  ┌──────────┐  ┌────────────┐  ┌──────────┐ │
│  │RustBridge │  │H265Decoder │  │PcmPlayer │ │
│  │ (JNI .so)│  │(MediaCodec)│  │(AudioTrak)│ │
│  └──────────┘  └────────────┘  └──────────┘ │
│       │              │               │       │
│  ┌────▼──────────────▼───────────────▼─────┐ │
│  │         libmobile_core.so              │ │
│  │  P2pViewer → Swarm → libp2p + Relay   │ │
│  └────────────────────────────────────────┘ │
└─────────────────────────────────────────────┘
```

## 构建要求

- **Rust**: 1.74+, `cargo-ndk`
- **Android**: SDK 34+, NDK 27+, API Level 26
- **JDK**: 11+

```bash
# 安装 cargo-ndk
cargo install cargo-ndk

# 安装 Rust Android targets
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

## 一键构建

```bash
cd p2p-camera

# 完整构建（Rust .so → APK）
./scripts/build_apk.sh

# 仅编译 Rust .so
./scripts/build_android.sh
```

输出 APK: `android-media/demos/media_player/buildout/outputs/apk/release/demo-media-player-release.apk`

## 使用

1. 确保 DeviceCam 已运行并连接到 Relay
2. 打开 P2P Camera Media Player
3. 输入 Relay 地址: `/ip4/1.2.3.4/tcp/5001/p2p/12D3KooW...`
4. 输入设备 Peer ID: `12D3KooW...`
5. 点击 "连接"

连接成功后自动开始解码渲染。

## 数据格式

### Rust → Kotlin JNI 协议

```
nativePollVideoFrame() → ByteArray?
  格式: [PTS(8B big-endian i64 µs)] + [H.265 NAL data]

nativePollAudioFrame() → ByteArray?
  格式: [PTS(8B big-endian i64 µs)] + [PCM 16-bit LE, 16kHz, mono]

nativePollEvent() → String? (JSON)
  事件类型: Connecting, Connected, StreamReady, Disconnected, Error
```

### Kotlin 侧解包

```kotlin
val raw = RustBridge.nativePollVideoFrame(handle) ?: return
val buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
val ptsUs = buf.long                         // 前 8 字节
val nalData = raw.sliceArray(8 until raw.size) // 剩余 = H.265 NAL
```

## 文件结构

```
demos/media_player/
├── build.gradle
├── proguard-rules.pro
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/p2pcamera/mediaplayer/
    │   ├── RustBridge.kt              # JNI 声明 + PTS 解包
    │   ├── MainActivity.kt            # 主界面 + 轮询循环
    │   ├── video/H265Decoder.kt       # MediaCodec H.265 硬解
    │   └── audio/PcmAudioPlayer.kt    # AudioTrack PCM 播放
    └── res/
        ├── layout/activity_main.xml
        └── values/strings.xml
```

## 调试

```bash
# 查看 Android logcat
adb logcat -s MediaPlayer:* H265Decoder:* PcmAudioPlayer:*

# 仅编译并安装到设备
cd android-media
./gradlew :demo-media-player:installDebug
adb shell am start -n com.p2pcamera.mediaplayer/.MainActivity
```
