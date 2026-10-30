package com.jhh.myapptools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 页面骨架：标题 + 可滚动内容 + 底部固定操作条。
 *
 * 4 个工具页共用同一套结构，避免各自实现导致行为不一致
 * （之前每页都把「开始」按钮埋在滚动内容中间，内容一长就得往下翻）。
 */
@Composable
internal fun ScreenScaffold(
    modifier: Modifier = Modifier,
    title: String,
    /** 标题右侧的附加元素（如「?」帮助图标） */
    titleExtra: (@Composable () -> Unit)? = null,
    actionBar: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                titleExtra?.invoke()
            }
            content()
        }
        actionBar()
    }
}

/**
 * 页面底部常驻的操作条。
 *
 * 解决的问题：原来「开始下载 / 开始压缩」按钮埋在滚动内容中间，内容一长就得往下翻
 * 才能找到主操作；结果出现后按钮又跑到更下面。固定到底部后主操作永远可达。
 *
 * @param progress 非 null 时在按钮上方显示进度条
 * @param stageText 进度条下方的状态文案
 */
@Composable
internal fun PrimaryActionBar(
    primaryText: String,
    onPrimary: () -> Unit,
    primaryEnabled: Boolean = true,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    secondaryEnabled: Boolean = true,
    progress: Float? = null,
    stageText: String? = null,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (stageText != null) {
                    Text(
                        stageText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPrimary,
                    enabled = primaryEnabled,
                    modifier = Modifier.weight(1f),
                ) { Text(primaryText, maxLines = 1) }

                if (secondaryText != null && onSecondary != null) {
                    OutlinedButton(
                        onClick = onSecondary,
                        enabled = secondaryEnabled,
                    ) { Text(secondaryText, maxLines = 1) }
                }
            }
        }
    }
}
