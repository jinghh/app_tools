package com.jhh.myapptools.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jhh.myapptools.R
import java.util.Locale

/**
 * 页面骨架：标题 + 可滚动内容。
 *
 * 5 个工具页共用同一套结构，避免各自实现导致行为不一致。
 * 操作按钮一律放在内容流里（不放悬浮底栏），避免遮挡内容、也不误触。
 */
@Composable
internal fun ScreenScaffold(
    modifier: Modifier = Modifier,
    title: String,
    /** 标题右侧的附加元素（如「?」帮助图标） */
    titleExtra: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
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
}

/**
 * 参数滑块 + 下方「原始值 / 设定值」对照。
 *
 * 为什么把目标大小、质量这类参数统一改成滑块：
 * - 输入框要弹系统键盘、要处理空值/非法字符/超范围，还要额外给预设按钮才勉强好用；
 * - 这类参数本来就是「大致范围」而不是精确数字，拖动一下就到，手感更接近调音量的直觉；
 * - 下方同时列出原值与设定值，用户不用自己心算「设成 3MB 到底算不算小」。
 *
 * [leftLabel] 建议放原值（如「原始 42.8 MB」），[rightLabel] 放当前设定值。
 */
@Composable
internal fun ParamSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    leftLabel: String,
    rightLabel: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = leftLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = rightLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** MB 值展示：≥10MB 取整，否则保留一位小数。 */
internal fun formatMb(mb: Float): String =
    if (mb >= 10f) {
        String.format(Locale.US, "%.0f MB", mb)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }

// ---------- 5 个工具页共用的分区 / 帮助 / 诊断组件 ----------

/**
 * 可折叠区块：低频内容默认收起，只显示一行摘要；折叠状态跨配置变更保留。
 * 从 DownloadScreen 提升为 internal，供 5 个页共用。
 */
@Composable
internal fun CollapsibleSection(
    title: String,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (!expanded) {
                        Text(
                            summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
                Icon(
                    imageVector = if (expanded) {
                        Icons.Filled.KeyboardArrowUp
                    } else {
                        Icons.Filled.KeyboardArrowDown
                    },
                    contentDescription = if (expanded) "收起" else "展开",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    content()
                }
            }
        }
    }
}

/** 「?」帮助图标：点击弹出说明弹窗，长说明不占版面 */
@Composable
internal fun HelpIcon(title: String, text: String) {
    var show by remember { mutableStateOf(false) }
    IconButton(
        onClick = { show = true },
        modifier = Modifier.size(32.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_help),
            contentDescription = title,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            confirmButton = {
                TextButton(onClick = { show = false }) { Text("知道了") }
            },
            title = { Text(title) },
            text = { Text(text, style = MaterialTheme.typography.bodyMedium) },
        )
    }
}

/** 卡片标题 + 「?」帮助入口 */
@Composable
internal fun TitleWithHelp(title: String, helpTitle: String, helpText: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        HelpIcon(title = helpTitle, text = helpText)
    }
}

/** 卡片标题 + 「复制」按钮（日志/诊断信息用） */
@Composable
internal fun SectionHeaderWithCopy(
    title: String,
    textToCopy: String,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onCopy, enabled = textToCopy.isNotBlank()) {
            Text("复制")
        }
    }
}

/** 等宽文本块：限高 200dp 内部滚动，避免长日志把整页撑到无限长 */
@Composable
internal fun MonoTextBlock(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 200.dp)
            .verticalScroll(rememberScrollState()),
    )
}

internal data class DiagnosticsBlock(val title: String, val text: String)

/**
 * 诊断 / 命令 / 日志的统一折叠区：默认收起，展开后每个文本块限高。
 * [blocks] 为空时不渲染任何东西，避免各页多一张空卡。
 */
@Composable
internal fun DiagnosticsSection(
    blocks: List<DiagnosticsBlock>,
    summary: String,
    title: String = "诊断与日志",
    onCopy: ((label: String, text: String) -> Unit)? = null,
) {
    if (blocks.isEmpty()) return
    val copy = onCopy
    CollapsibleSection(title = title, summary = summary) {
        blocks.forEach { block ->
            if (copy != null) {
                SectionHeaderWithCopy(
                    title = block.title,
                    textToCopy = block.text,
                    onCopy = { copy(block.title, block.text) },
                )
            } else {
                Text(block.title, style = MaterialTheme.typography.titleSmall)
            }
            MonoTextBlock(block.text)
        }
    }
}
