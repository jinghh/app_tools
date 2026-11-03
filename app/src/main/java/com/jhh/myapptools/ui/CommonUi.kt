package com.jhh.myapptools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 页面骨架：标题 + 可滚动内容。
 *
 * 4 个工具页共用同一套结构，避免各自实现导致行为不一致。
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
