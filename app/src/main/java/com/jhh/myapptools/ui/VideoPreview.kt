package com.jhh.myapptools.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

/**
 * Media3 ExoPlayer 视频预览（配合片段起止时间选择）。
 *
 * 播放策略：**不自动播放 + 默认静音**。
 * 原因：预览页常在会议、办公室、地铁等公共场合打开，导入即出声非常尴尬；
 * 即使用户主动点播放，也先静音输出，需要声音时再手动开启。
 */
@Composable
fun VideoPreview(
    file: File,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 默认静音；换视频时重置为静音，避免上一条视频"解除静音"的选择带过来
    var muted by remember(file.path) { mutableStateOf(true) }

    val player = remember(file) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            // 不自动播放：等用户主动点播放按钮
            playWhenReady = false
            // 初始静音：prepare 之后即使被播放也不会出声
            volume = 0f
            prepare()
        }
    }

    LaunchedEffect(muted) {
        player.volume = if (muted) 0f else 1f
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    this.player = player
                }
            },
        )
        // 右上角声音开关：显示的是「下一步动作」，避免用户误以为是当前状态
        Surface(
            onClick = { muted = !muted },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Text(
                text = if (muted) "开启声音" else "静音",
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}
