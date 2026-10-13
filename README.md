# 安卓本地工具箱（my-app-tools）

个人使用的 Android 原生工具箱：**下载 X 视频 → 截取片段 → 转 GIF → 压缩 GIF**，全部在手机本地完成，不依赖自建服务器。

- **技术栈**：Kotlin + Jetpack Compose + Material 3，Gradle 多模块可扩展结构
- **当前版本**：0.1.0（媒体处理与下载均已真机验证）

---

## 功能

| 功能 | 状态 | 说明 |
|---|---|---|
| **X 视频下载** | ✅ 真机可用 | 粘贴公开帖子链接即可下载；下载完成可一键送去转 GIF |
| **视频转 GIF** | ✅ 真机可用 | 本地视频导入（SAF）、Media3 预览、片段选择、参数可调（fps/宽度/颜色数/抖动） |
| **GIF 压缩** | ✅ 已实现 | 输入已有 GIF + 目标大小，按「减色 → 降帧率 → 降分辨率」级联压缩，每轮校验真实字节数 |
| 工作流编排 | ⬜ 未开始 | 计划把四步串成固定流程（见 PRD 阶段 3） |

---

## 快速开始

### 1. 直接安装 APK（推荐）

到本仓库的 **[Releases](../../releases)** 页面下载最新 APK，传到手机安装即可。

> 推送形如 `v0.1.0` 的 tag 会触发 GitHub Actions 自动构建并发布 APK（见 `.github/workflows/release.yml`）。

### 2. 从源码构建

环境要求：

| 项目 | 版本 |
|---|---|
| JDK | 17 或 21（Android Studio 自带 JBR 即可） |
| Android SDK | compileSdk 36（Platform 36 需已安装） |
| Gradle | 使用仓库内 wrapper（8.14.1），无需单独安装 |
| AGP / Kotlin | 8.13.0 / 2.3.20 |

```bash
# 生成 Debug APK
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug

# 产物路径
app/build/outputs/apk/debug/app-debug.apk
```

用 Android Studio 打开仓库根目录后直接 Run 也可以。

### 3. 真机安装（adb）

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 使用说明

### X 视频下载

1. 复制 X 帖子链接（形如 `https://x.com/<user>/status/<id>`）；
2. 「X 下载」页粘贴 → 「开始下载」；
3. 下载完成后可「保存到本地」或直接「去转 GIF」。

**注意**：只处理你有权访问的公开内容；媒体地址有时效性，请下载后尽快处理。

### 视频转 GIF

1. 「视频转 GIF」页 → 从本地导入视频（系统文件选择器）；
2. 选择片段：≤30 秒视频默认整段，>30 秒默认前 30 秒（可手动调整）；
3. 设置帧率 / 输出宽度 / 调色板颜色数 / 是否抖动 → 开始转换；
4. 结果页可预览并保存。

### GIF 压缩

1. 「GIF 压缩」页 → 导入任意 GIF；
2. 输入目标大小（MB，十进制换算）→ 开始压缩；
3. 界面会显示每轮参数与体积变化、是否达标；
4. **原文件不会被覆盖**；若达到最低参数仍超限，会明确提示未达标。

---

## 工程结构

```text
app/src/main/java/com/jhh/myapptools/
├── MainActivity.kt              # 底部页签：X 下载 → 视频转 GIF → GIF 压缩
├── gif/
│   ├── GifEngine.kt             # GIF 编码接口 + 参数/事件模型
│   ├── FfmpegGifEngine.kt       # FFmpeg 调色板编码（palettegen/paletteuse）
│   ├── FfmpegRunner.kt          # FFmpegKit 同步执行封装（编码/压缩共用）
│   ├── GifCompressor.kt         # GIF 压缩：参数级联 + 真实字节数校验
│   └── GifInspector.kt          # GIF 结构解析（帧数/尺寸/循环校验）
├── download/
│   ├── XMediaResolver.kt        # 解析器接口（PRD 5.3 抽象，实现待补）
│   ├── YtDlpDownloader.kt       # 下载实现（yt-dlp）
│   └── DownloadEvent.kt
├── media/VideoMetadata.kt       # 视频元信息读取
└── ui/                          # 各功能页面 + 预览组件 + SAF 工具
```

---

## 关键技术选型（含选择依据）

| 依赖 | 版本 | 选择理由 |
|---|---|---|
| Media3 ExoPlayer | 1.11.1 | 视频预览与定位；Transformer 不支持 GIF 输出，故不用于编码 |
| FFmpegKit | `dev.ffmpegkit-maintained:ffmpeg-kit-min` 8.1.9 | **官方 com.arthenica 已于 2025 年退役下架**，改用社区维护分支；LGPL-3.0；16KB 页对齐已核实 |
| yt-dlp-android | `dev.ffmpegkit-maintained:yt-dlp-android` 2.0.2 | 内嵌 CPython 3.13 + yt-dlp；MIT；真机验证可下载 X 视频 |
| Compose BOM | 2025.10.00 | 更新的 BOM 要求 AGP 9.1+ 与 compileSdk 37，本机 SDK 上限 36.1，故锁定兼容组合 |
| OkHttp | — | 曾用于自研下载，随解析路径回滚一并移除（详见进度文档） |

**ABI 策略**：仅打包 `arm64-v8a`（近几年真实手机均为该架构），APK 约 58MB；代价是无法在 x86_64 模拟器安装。

---

## 已知限制（如实记录，不掩饰）

1. **下载无法取消**：yt-dlp-android 未暴露取消 API，`Future.cancel` 无法中断内嵌 Python，取消仅为"放弃等待"（不满足 PRD 8.4）；
2. **下载前拿不到清晰度/时长**：该库不转发 stdout，且 CLI 参数白名单不含 `--print`/`-J`/`--write-info-json`（已由字节码核实），故按默认最优格式下载，元信息在下载后本地读取；
3. **yt-dlp 无法应用内更新**：X 改版后需升级依赖并重新打包；
4. **无视频帖会报错**：如链接指向的帖子只有图片/文字，或视频位于被引用的帖子中，会提示"该帖子中没有找到视频"；
5. `minSdk 24`：Android 7.0 以下与纯 32 位设备不支持。

> 1、2 两条可通过实现自研 X 解析器（直接解析公开帖子取 media URL）+ OkHttp 下载来消除，
> 代价是需随 X 改版维护；`XMediaResolver` 接口即为该实现预留的替换点。

---

## 文档

- [产品需求与技术方案 V1.1](./docs/安卓本地工具箱App_产品需求与技术方案_V1.1.md)
- [项目进度与技术验证记录](./docs/安卓本地工具箱_项目进度与技术验证记录.md)——含依赖核查、真机验证数据、库能力边界证据链与 Code Review 记录

---

## 隐私

- 媒体处理全部在设备本地完成，不上传视频/GIF 到自建服务器；
- 仅申请必要权限（`INTERNET` 等），文件读写全程使用系统文件选择器（SAF）；
- 不处理需要登录或付费才能访问的内容，不绕过任何访问控制。
