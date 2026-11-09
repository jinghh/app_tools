# 安卓本地工具箱（my-app-tools）

个人使用的 Android 原生工具箱：**下载 X 视频 → 截取片段 → 转 GIF → 压缩 GIF**，全部在手机本地完成，不依赖自建服务器。

- **技术栈**：Kotlin + Jetpack Compose + Material 3，Gradle 多模块可扩展结构
- **当前版本**：0.3.0（新增应用内登录自动获取 Cookie、下载/工具页 UI 重构与全新应用图标；图片压缩模块为 0.2.0 新增，广色域/HEIC/超大图待验证）

---

## 功能

| 功能 | 状态 | 说明 |
|---|---|---|
| **视频下载（多站点）** | ✅ 真机可用 | 基于 yt-dlp：**X（Twitter）、Facebook 已实测可用**；小红书公开笔记可下、需登录的笔记可在 App 内登录或导入 Cookie；YouTube 返回 403（库限制，见下）；支持粘贴分享文本自动提取链接；下载完可一键送去转 GIF |
| **应用内登录获取 Cookie** | ✅ 已实现（待真机验证） | 下载提示需要登录时，一键打开 WebView 登录页，登录后自动把 Cookie 按站点保存并重试；无需电脑导出 cookies.txt |
| **视频转 GIF** | ✅ 真机可用 | 本地视频导入（SAF）、Media3 预览、片段选择、参数可调（fps/宽度/颜色数/抖动） |
| **GIF 压缩** | ✅ 已实现 | 输入已有 GIF + 目标大小，按「减色 → 降帧率 → 降分辨率」级联压缩，每轮校验真实字节数 |
| **视频压缩** | ✅ 已实现 | 目标大小 + 可选倍速；码率 → 分辨率 → 帧率级联，硬编 h264_mediacodec 失败自动回落 mpeg4 |
| **图片压缩** | ✅ 已实现（待真机验证） | 批量导入；有损 / 无损 / 目标大小三种模式；尺寸（长边/百分比/自定义）与格式（JPEG/PNG/WebP）转换；透明通道合成、EXIF 自动转正；**纯原生 Bitmap 编解码，零新增依赖** |
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

### 视频下载

1. 复制视频链接（X 形如 `https://x.com/<user>/status/<id>`；Facebook 复制视频页链接；小红书直接用「分享」复制文案即可）；
2. 「视频下载」页粘贴 → 「开始下载」；
   - **可直接粘贴分享文本**：形如「节奏感很强烈 https://xhslink.cn/o/3OsPOYWaqSm 来【小红书】发现这篇笔记的精彩~」，
     输入框会自动去掉链接以外的文字（含中文推广语与句末标点）；
3. 下载完成后可「保存到本地」或直接「去转 GIF」。

> **界面分三层**：首屏常驻「链接输入 + 唯一主按钮 + 进度/结果/错误」，
> 操作与反馈不再分开；「Cookie 与登录」「通知与存储」「诊断与日志」默认折叠，点标题展开，
> 收起时只显示一行摘要。主按钮会随状态在「开始下载 / 继续下载 / 重试下载」之间切换。

**需要登录的内容（可选配置）**：小红书、微博等站点对部分内容要求登录态，否则链接会跳转到登录页导致失败。
有两种方式，推荐第一种（**不需要电脑**）：

1. **应用内登录**：下载失败时，主任务卡里的错误下方会直接出现「在 App 内登录并获取 Cookie」；
   也可展开「Cookie 与登录」预先登录。App 打开一个 WebView，正常登录目标站点
   （验证码 / 短信验证由本人完成），点底部的「完成并导入 Cookie」后自动把登录 Cookie 转成
   Netscape 格式、按站点存入应用私有目录。回到下载页即显示已导入，点主按钮「重试下载」即可。
2. **导入 cookies.txt**：电脑浏览器安装扩展（如 **Get cookies.txt LOCALLY**）→ 登录目标站点 →
   导出 cookies.txt → 传到手机（可用 `adb push` 到 `/sdcard/Download/`）→
   在 App 内点「导入 cookies.txt」选择它。

> 两种方式写入的是同一份 `filesDir/cookies/<注册域>.txt`，下载时按链接自动匹配，
> 只把同站点的 Cookie 交给 yt-dlp。短链（`xhslink.cn` 等）会先归一化到真实站点再存。
>
> 登录页走 Android 系统网络栈；如需代理请在系统层使用 VPN 全局模式
> （App 内已移除单独的网络代理设置）。仅支持白名单内站点；
> YouTube 的 403 属 TLS 指纹 / JS 挑战，登录也解不了，因此不会弹出登录入口。
>
> 小红书、抖音、Instagram 等在**移动端 UA** 下只推「下载 App」、不提供 WAP 登录页，而站点按
> HTTP 请求头的 UA 分流。因此登录页按站点请求**桌面版页面**（改 WebView 的 UA + 开宽视口），
> 只作用于 WebView，**不改变 yt-dlp 下载端的 UA**；这是为了拿到平台本来提供的 PC 页面，
> 验证码 / 短信仍由本人完成。Chrome 版本取自系统 WebView，无需手工维护；个别站点若 PC 版
> 反而不好用，可在 `LoginSites.Site.desktopMode` 里单独关闭。

Cookie 仅保存在应用私有目录，不写入日志、不外传；换设备或登录态过期后需重新登录 / 导出。

**站点支持情况（真机实测）**：

| 站点 | 结果 |
|---|---|
| X（Twitter） | ✅ 可用 |
| Facebook（公开视频） | ✅ 可用 |
| 小红书（分享短链） | ⚠️ 公开笔记可用；需登录的笔记需应用内登录或导入 Cookie（否则跳转登录页失败） |
| B 站 | ⚠️ 解析正常；默认返回 DASH 分离流，`-f b`(单一最佳) 会报「Requested format is not available」。现已改为格式失败自动降级到 yt-dlp 默认组合（真机待复验） |
| 抖音 | ⚠️ 应用内登录与 Cookie 导出成功，但播放地址需 `a_bogus`/`X-Bogus` 签名与 TLS 指纹，免费版解析库大概率拿不到（见已知限制 8） |
| YouTube | ❌ 返回 403（反爬指纹 或 签名挑战需 JS 运行时，均属 yt-dlp-android 免费版能力之外） |
| TikTok / Instagram | ⚠️ 未验证；官方说明这类站点需 Pro 版（curl-cffi）才较可靠 |

> 内嵌的 yt-dlp 为 2026.6.9，含 941 个提取器模块，覆盖 B 站、微博、优酷、爱奇艺、知乎、
> AcFun、虎牙、今日头条等；但**"有提取器" ≠ "能下成功"**，实际成败取决于是否需要
> TLS 指纹伪装、登录 Cookie 或 JS 运行时（见「已知限制」）。

**注意**：只处理你有权访问的公开内容；媒体地址有时效性，请下载后尽快处理。

### 视频转 GIF

1. 「视频转 GIF」页 → 从本地导入视频（系统文件选择器）；
2. 选择片段：≤30 秒视频默认整段，>30 秒默认前 30 秒（可手动调整）；
3. 设置帧率 / 输出宽度 / 调色板颜色数 / 是否抖动 → 开始转换；
4. 结果页可预览并保存。

### GIF 压缩

1. 「GIF 压缩」页 → 导入任意 GIF；
2. **拖动目标大小滑块**（范围自动收到原文件大小，下方实时显示「原始 / 设置」）→ 开始压缩；
3. 界面会显示每轮参数与体积变化、是否达标；
4. **原文件不会被覆盖**；若达到最低参数仍超限，会明确提示未达标。

### 视频压缩

1. 「视频压缩」页 → 导入视频（SAF，或从下载页直接「压缩」跳转）；
2. **拖动目标大小滑块**（默认 25MB = 微信上限；范围自动收到原片大小，下方显示「原始 / 设置」），需要时选倍速；
3. 级联顺序：码率 → 分辨率 → 帧率，硬编失败自动回落软件 mpeg4；
4. 压缩结果可「存到下载」「另存为」，也可一键「转 GIF」。

### 图片压缩

1. 「图片」页 → 从本地导入（**可多选**，批量串行处理）；
2. 选压缩模式（数值参数均为**滑块拖动**，下方实时显示「原始 / 设置」）：
   - **有损**：拖动质量滑块（另有 高 85 / 中 75 / 低 60 快捷档）；
   - **无损**：只重编码、像素不变，体积通常仅减 0–15%（界面已如实提示）；
   - **目标大小**：拖动目标大小滑块（上限为原图大小、下限固定 10KB，下方显示占原图百分比；过低会红字提示），自动二分搜索质量，仍超限再按尺寸阶梯下降；
3. 选输出尺寸（原始 / 长边 2048·1920·1280·1080·640 / 75%·50%·25% / 自定义）与输出格式（原格式 / JPEG / PNG / WebP）；
4. 开始压缩 → 结果列表显示每张的前后体积、降幅、尺寸与参数；
5. 「存到相册」写入 `Pictures/MyAppTools`（Android 10+ 免权限；更低版本请用「另存为」）。

注意事项：

- 选「无损 + 原格式 + 原始尺寸」时**直接保留原图**（体积不变）：Android 平台没有无损再优化能力，重编码反而会把 JPEG / 调色板 PNG 撑大数倍；要变小请用「有损」或「目标大小」；
- 任何模式下只要没改尺寸/格式、且重编码结果不小于原图，都会回退保留原图，不会「越压越大」；
- 带透明通道的 PNG/WebP 转 JPEG 时，透明区域**合成白色底**（不会变黑）；
- 手机竖拍照片会按 EXIF 方向自动转正；
- 动图（GIF / 动图 WebP）请走「GIF 压缩」页；
- HEIC 可作为输入（Android 9+），输出统一转 JPEG/PNG/WebP（平台无 HEIF 编码 API）；
- 原文件只读，产物在应用缓存目录，**不会被覆盖**。

---

## 工程结构

```text
app/src/main/java/com/jhh/myapptools/
├── MainActivity.kt              # 底部页签：下载 → 转 GIF → GIF 压缩 → 视频压缩 → 图片
├── gif/
│   ├── GifEngine.kt             # GIF 编码接口 + 参数/事件模型
│   ├── FfmpegGifEngine.kt       # FFmpeg 调色板编码（palettegen/paletteuse）
│   ├── FfmpegRunner.kt          # FFmpegKit 同步执行封装（编码/压缩共用）
│   ├── GifCompressor.kt         # GIF 压缩：参数级联 + 真实字节数校验
│   └── GifInspector.kt          # GIF 结构解析（帧数/尺寸/循环校验）
├── download/
│   ├── XMediaResolver.kt        # 解析器接口（PRD 5.3 抽象，实现待补）
│   ├── YtDlpDownloader.kt       # 下载实现（yt-dlp）+ 结构化失败原因分类
│   ├── DownloadEvent.kt         # 事件模型 + FailureReason
│   ├── CookieStore.kt           # Netscape cookies.txt 按平台存取（原子写入）
│   ├── LoginSites.kt            # 应用内登录站点白名单
│   └── WebViewCookieExporter.kt # CookieManager → Netscape 7 列
├── media/VideoMetadata.kt       # 视频元信息读取
├── video/VideoCompressor.kt     # 视频压缩（码率 → 分辨率 → 帧率级联）
├── image/                       # 图片压缩（纯原生 Bitmap，不依赖 ffmpeg）
│   ├── ImageCompressor.kt       # 有损/无损/目标大小 + 尺寸与格式转换 + 批量串行
│   ├── ImageInspector.kt        # 尺寸/格式/透明通道/EXIF 方向/动图探测
│   ├── ImageFormat.kt           # 输出格式与平台编码能力（WebP 无损仅 API 30+）
│   └── ImageToolPrefs.kt        # 压缩参数记忆
└── ui/                          # 各功能页面 + 预览组件 + SAF 工具
    └── LoginCookieActivity.kt    # 独立 WebView 登录页：登录后自动导出 Cookie
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
5. **YouTube 不可用（403）**：yt-dlp 对 YouTube 的反爬拦截需要 TLS 指纹伪装或 JS 运行时（解签名挑战），
   两者在 yt-dlp-android 免费版 + Android 内嵌 Python 环境下都不具备；
6. `minSdk 24`：Android 7.0 以下与纯 32 位设备不支持；
7. **应用内登录不保证下载一定成功**：`CookieManager.getCookie()` 只返回 `name=value`，
   domain / path / expires 等属性需按父域补齐（近似值）；WebView（Chrome UA / Chromium TLS）
   与 yt-dlp（Python requests）指纹不同，部分站点还绑定设备签名，仅复制 Cookie 可能仍被拒绝。
   按站点请求 PC 版页面虽能绕开「移动端只推下载 App」，
   但 `navigator.platform` / 触屏 / WebGL 等仍是移动设备信号，个别站点可能因此触发更严验证，
   且桌面 UA **不改变 yt-dlp 下载端的 UA**。验证码 / 风控需本人完成，App 不做任何绕过尝试。
8. **B 站等 DASH 站点需格式降级**：B 站默认返回音视频分离流，`-f b`(best) 只挑单一格式，
   会报 `Requested format is not available`；日志里的「1080P 高码率 are missing」只是可忽略的
   大会员画质提示，不是失败原因。现在检测到该报错会自动改用 `bv*+ba/b`（yt-dlp 默认组合）重试一次。
   内嵌环境无 ffmpeg 时合并会被跳过，产物可能是**不含声音的视频流**；
   `--list-formats` 不在库的参数白名单内，无法先列出格式再精确挑。
9. **抖音大概率下不了**：应用内登录、Cookie 导出均已验证成功，但播放地址接口需要
   `a_bogus`/`X-Bogus` 签名（前端 JS 生成、绑定 `ttwid`/`verifyFp` 等设备指纹），
   yt-dlp-android 免费版无 JS 运行时、无 TLS 指纹伪装。Cookie 只回答「我是谁」，
   生成不了签名；这与小红书「导入 Cookie 即可下载」不是同一类问题。
   另「清除文件」只删 `cookies/<注册域>.txt`，WebView 的 `CookieManager` 登录态需在登录页单独清除。

> 1、2 两条可通过实现自研 X 解析器（直接解析公开帖子取 media URL）+ OkHttp 下载来消除，
> 代价是需随 X 改版维护；`XMediaResolver` 接口即为该实现预留的替换点。

---

## 文档

- [产品需求与技术方案 V1.1](./docs/安卓本地工具箱App_产品需求与技术方案_V1.1.md)
- [项目进度与技术验证记录](./docs/安卓本地工具箱_项目进度与技术验证记录.md)——含依赖核查、真机验证数据、库能力边界证据链与 Code Review 记录
- [优化建议与功能规划](./docs/安卓本地工具箱_优化建议与功能规划.md)——基于 v0.1.5 代码走读的优化项清单、新功能候选与落地顺序
- [图片压缩模块需求与技术方案](./docs/安卓本地工具箱_图片压缩模块需求与技术方案.md)——模式定义、编码能力矩阵、级联与二分策略、验收用例

---

## 隐私

- 媒体处理全部在设备本地完成，不上传视频/GIF 到自建服务器；
- 仅申请必要权限（`INTERNET` 等），文件读写全程使用系统文件选择器（SAF）；
- 应用内登录只用于用户本人账号访问本人有权访问的内容；登录 Cookie 仅存应用私有目录
  （`allowBackup=false`），不写日志、不外传；不绕过验证码与任何访问控制。
