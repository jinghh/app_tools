package com.jhh.myapptools

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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

    /** 通知点击等外部入口请求的页签；变化时 Compose 会切过去 */
    private var requestedTab by mutableIntStateOf(TAB_DOWNLOAD)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTab = intent.readTabExtra()
        enableEdgeToEdge()
        setContent {
            AppTheme {
                MainScreen(requestedTab = requestedTab)
            }
        }
    }

    /** 点击下载结果通知时，Activity 已在栈顶 → 走这里切页签，不重建界面 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab = intent.readTabExtra()
    }

    private fun Intent?.readTabExtra(): Int =
        this?.getIntExtra(EXTRA_TAB, TAB_DOWNLOAD) ?: TAB_DOWNLOAD

    companion object {
        const val EXTRA_TAB = "extra_tab"
        /** 页签按使用频率排序：X 下载（最常用，默认页）→ 视频转 GIF → GIF 压缩 */
        const val TAB_DOWNLOAD = 0
        const val TAB_GIF = 1
        const val TAB_COMPRESS = 2
    }
}

@Composable
private fun MainScreen(requestedTab: Int) {
    var tab by remember { mutableIntStateOf(requestedTab) }
    // 通知点击等外部请求：切到指定页签（不做重建，保留已有界面状态）
    LaunchedEffect(requestedTab) { tab = requestedTab }

    // 跨页签直传：下载 → 转 GIF；转 GIF → 压缩（均免去手动保存再导入）
    var pendingVideo by remember { mutableStateOf<File?>(null) }
    var pendingGif by remember { mutableStateOf<File?>(null) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_DOWNLOAD,
                    onClick = { tab = MainActivity.TAB_DOWNLOAD },
                    icon = { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null) },
                    label = { Text("视频下载") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_GIF,
                    onClick = { tab = MainActivity.TAB_GIF },
                    icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                    label = { Text("视频转 GIF") },
                )
                NavigationBarItem(
                    selected = tab == MainActivity.TAB_COMPRESS,
                    onClick = { tab = MainActivity.TAB_COMPRESS },
                    icon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                    label = { Text("GIF 压缩") },
                )
            }
        },
    ) { padding ->
        when (tab) {
            MainActivity.TAB_DOWNLOAD -> DownloadScreen(
                modifier = Modifier.padding(padding),
                onSendToGif = { file ->
                    pendingVideo = file
                    tab = MainActivity.TAB_GIF
                },
            )

            MainActivity.TAB_GIF -> GifToolScreen(
                modifier = Modifier.padding(padding),
                preloadedVideo = pendingVideo,
                onPreloadConsumed = { pendingVideo = null },
                onSendToCompress = { file ->
                    pendingGif = file
                    tab = MainActivity.TAB_COMPRESS
                },
            )

            else -> GifCompressScreen(
                modifier = Modifier.padding(padding),
                preloadedGif = pendingGif,
                onPreloadConsumed = { pendingGif = null },
            )
        }
    }
}
