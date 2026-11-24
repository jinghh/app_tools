package com.jhh.myapptools.ai.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.jhh.myapptools.ai.AiChatClient
import com.jhh.myapptools.ai.AiConfigStore
import com.jhh.myapptools.ai.ChatOutcome
import com.jhh.myapptools.ai.ChatRequest
import com.jhh.myapptools.ai.ProviderConfig
import com.jhh.myapptools.ui.ScreenScaffold
import com.jhh.myapptools.ui.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 极简模型配置页（参考 cc-switch 的「服务商 + 地址 + 模型 + Key」四件套，砍掉其余能力）。
 *
 * 目前只内置 DeepSeek 预设；其余字段保持通用，后续新增预设只需改 ProviderConfig.presets。
 */
class AiSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = AiConfigStore(this)
        val initial = store.load()
        enableEdgeToEdge()
        setContent {
            AppTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
                ) {
                    AiSettingsScreen(
                        initial = initial,
                        onSave = { config ->
                            runCatching { store.save(config) }
                                .onSuccess {
                                    Toast.makeText(
                                        this@AiSettingsActivity,
                                        "已保存模型配置",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    finish()
                                }
                                .onFailure { e ->
                                    Toast.makeText(
                                        this@AiSettingsActivity,
                                        "保存失败（Key 不会明文保存）：${e.message ?: "设备不支持安全存储"}",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                        },
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}

@Composable
private fun AiSettingsScreen(
    initial: ProviderConfig,
    onSave: (ProviderConfig) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var baseUrl by rememberSaveable { mutableStateOf(initial.baseUrl) }
    var model by rememberSaveable { mutableStateOf(initial.model) }
    // 不用 rememberSaveable：Key 不能进 Bundle/savedState；Activity 已配 configChanges，旋转不重建
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    fun currentConfig() = ProviderConfig(
        id = initial.id,
        name = initial.name,
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        apiKey = apiKey.trim(),
    )

    fun testConnection() {
        if (testing) return
        testing = true
        testResult = "正在测试…"
        val config = currentConfig()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                AiChatClient().chat(
                    config,
                    ChatRequest(prompt = "请只回复：OK", temperature = 0.1, maxTokens = 128),
                )
            }
            testing = false
            testResult = when (outcome) {
                is ChatOutcome.Success -> "连接成功：${outcome.content.take(60)}"
                is ChatOutcome.Failure -> "连接失败：${outcome.message}"
                ChatOutcome.Cancelled -> "已取消"
            }
        }
    }

    ScreenScaffold(
        title = "模型设置",
        onBack = onBack,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("当前服务商：${initial.name}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "仅内置 DeepSeek（视觉模型 deepseek-flash）。接口保持 OpenAI 兼容格式，后续可直接扩展其他服务商。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("接口地址 Base URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("模型名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API Key") },
                    placeholder = { Text("sk-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { testConnection() },
                        enabled = !testing && apiKey.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("测试连接", maxLines = 1) }
                    Button(
                        onClick = { onSave(currentConfig()) },
                        modifier = Modifier.weight(1f),
                    ) { Text("保存", maxLines = 1) }
                }
                testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text("隐私说明", style = MaterialTheme.typography.titleSmall)
                Text(
                    "API Key 使用 Android Keystore 加密后保存在本机，不随云备份外带，App 不内置任何 Key。\n" +
                        "只有你点击「生成爆款标题」时，才会把最多 12 张压缩关键帧（640px JPEG）、资源描述与文件名发送到你配置的服务商；" +
                        "原始视频与 GIF 文件不会上传。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(
                    onClick = {
                        val config = currentConfig().copy(apiKey = "")
                        apiKey = ""
                        onSave(config)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("清除已保存的 API Key") }
            }
        }
    }
}
