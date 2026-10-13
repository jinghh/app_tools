plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jhh.myapptools"
    // 依赖链（ffmpeg-kit 8.1.9 / AndroidX 新版本）针对 android-36 编译，compileSdk 必须 ≥ 36
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jhh.myapptools"
        // minSdk 24 与 ffmpeg-kit / yt-dlp-android 维护分支要求对齐；两者均只发布 64 位 ABI
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        ndk {
            // 只保留 arm64-v8a（近几年真实手机均为此架构）。
            // x86_64 仅供 PC 模拟器使用，去掉可省 15-18MB；代价是无法在模拟器上安装。
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // 本机仅有 Android Studio 自带 JBR 21；AGP 8.13 支持 JDK 21。
        // D8 会脱糖到 minSdk 24，字节码版本不影响低版本设备运行。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // ffmpeg-kit 预编译 .so 已做 16KB 页对齐；不使用 legacy 打包以便系统直接加载
        jniLibs.useLegacyPackaging = false
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.kotlinx.coroutines.android)

    // 方案A：FFmpegKit 社区维护分支（palettegen/paletteuse 调色板链路）
    // 纯 Java 引擎（square/gifencoder）已实测弃用：同参数慢 29 倍且无法减色，依赖已移除
    implementation(libs.ffmpeg.kit.min)

    // X 视频解析候选（阶段 2 验证中）：内嵌 CPython 3.13 + yt-dlp，
    // 注意 AAR 21.75MB（解压 60-80MB），仅 arm64-v8a/x86_64，且无应用内 yt-dlp 更新能力
    implementation(libs.ytdlp.android)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
