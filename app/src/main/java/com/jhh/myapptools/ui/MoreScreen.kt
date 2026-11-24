package com.jhh.myapptools.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.jhh.myapptools.MainActivity
import com.jhh.myapptools.R

/**
 * 聚合页：把低频工具收进来，底部导航不再受 5 项上限约束。
 * 后续新功能优先加在这里，只有真正高频的才提升到主导航。
 */
@Composable
internal fun MoreScreen(
    modifier: Modifier = Modifier,
    onOpenTab: (Int) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    var storageBytes by remember { mutableLongStateOf(0L) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        storageBytes = AppStorage.totalBytes(context)
    }

    ScreenScaffold(modifier = modifier, title = "更多") {
        Text("工具", style = MaterialTheme.typography.titleMedium)

        MoreItem(
            title = "GIF 压缩",
            subtitle = "减小 GIF 体积，可保留原文件名",
            icon = { Icon(painterResource(R.drawable.ic_tab_image), contentDescription = null) },
            onClick = { onOpenTab(MainActivity.TAB_COMPRESS) },
        )
        MoreItem(
            title = "视频压缩",
            subtitle = "按目标大小压缩视频，支持倍速",
            icon = { Icon(painterResource(R.drawable.ic_tab_image), contentDescription = null) },
            onClick = { onOpenTab(MainActivity.TAB_VIDEO_COMPRESS) },
        )
        MoreItem(
            title = "图片压缩",
            subtitle = "批量压缩图片，支持有损/无损",
            icon = { Icon(painterResource(R.drawable.ic_tab_image), contentDescription = null) },
            onClick = { onOpenTab(MainActivity.TAB_IMAGE_COMPRESS) },
        )
        MoreItem(
            title = "模型设置",
            subtitle = "配置 DeepSeek API Key 与接口地址",
            icon = { Icon(painterResource(R.drawable.ic_help), contentDescription = null) },
            onClick = onOpenSettings,
        )

        Text("存储", style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("工具缓存占用：${formatMb(storageBytes / 1024f / 1024f)}")
                Text(
                    "保留每个目录最近一次产物，其余临时文件会被清理。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        val freed = AppStorage.clean(context)
                        storageBytes = AppStorage.totalBytes(context)
                        Toast.makeText(
                            context,
                            "已释放 ${formatMb(freed / 1024f / 1024f)}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清理临时文件") }
            }
        }
    }
}

@Composable
private fun MoreItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("›", style = MaterialTheme.typography.headlineSmall)
        }
    }
}
