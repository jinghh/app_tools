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
    var tab by remember { mutableStateOf(0) }
    // 下载模块产出的视频，跨页签直传给转 GIF 模块（避免手动保存再导入）
    var pendingVideo by remember { mutableStateOf<File?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("视频转 GIF") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    label = { Text("GIF 压缩") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    label = { Text("X 下载") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            0 -> GifToolScreen(
                modifier = Modifier.padding(padding),
                preloadedVideo = pendingVideo,
                onPreloadConsumed = { pendingVideo = null },
            )

            1 -> GifCompressScreen(Modifier.padding(padding))

            else -> DownloadScreen(
                modifier = Modifier.padding(padding),
                onSendToGif = { file ->
                    pendingVideo = file
                    tab = 0
                },
            )
        }
    }
}
