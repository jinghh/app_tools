package com.jhh.myapptools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jhh.myapptools.ui.DownloadScreen
import com.jhh.myapptools.ui.GifCompressScreen
import com.jhh.myapptools.ui.GifToolScreen
import com.jhh.myapptools.ui.theme.AppTheme
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    // 页签按使用频率排序：X 下载（最常用，默认页）→ 视频转 GIF → GIF 压缩
    var tab by remember { mutableStateOf(TAB_DOWNLOAD) }
    // 下载模块产出的视频，跨页签直传给转 GIF 模块（避免手动保存再导入）
    var pendingVideo by remember { mutableStateOf<File?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_DOWNLOAD,
                    onClick = { tab = TAB_DOWNLOAD },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    label = { Text("视频下载") },
                )
                NavigationBarItem(
                    selected = tab == TAB_GIF,
                    onClick = { tab = TAB_GIF },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("视频转 GIF") },
                )
                NavigationBarItem(
                    selected = tab == TAB_COMPRESS,
                    onClick = { tab = TAB_COMPRESS },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    label = { Text("GIF 压缩") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            TAB_DOWNLOAD -> DownloadScreen(
                modifier = Modifier.padding(padding),
                onSendToGif = { file ->
                    pendingVideo = file
                    tab = TAB_GIF
                },
            )

            TAB_GIF -> GifToolScreen(
                modifier = Modifier.padding(padding),
                preloadedVideo = pendingVideo,
                onPreloadConsumed = { pendingVideo = null },
            )

            else -> GifCompressScreen(Modifier.padding(padding))
        }
    }
}

private const val TAB_DOWNLOAD = 0
private const val TAB_GIF = 1
private const val TAB_COMPRESS = 2
