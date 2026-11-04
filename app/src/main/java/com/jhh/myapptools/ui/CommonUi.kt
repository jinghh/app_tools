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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

/**
 * MB 值展示：≥10MB 取整，否则保留一位小数。
 * 滑块是连续值，读数跟着拖动实时变化，位数太多会一直抖动。
 */
internal fun formatMb(mb: Float): String =
    if (mb >= 10f) {
        String.format(Locale.US, "%.0f MB", mb)
    } else {
        String.format(Locale.US, "%.1f MB", mb)
    }
