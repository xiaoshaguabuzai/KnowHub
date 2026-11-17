package com.example.knowhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.knowhub.state.AppState
import kotlinx.coroutines.CoroutineScope

@Composable
fun SettingsScreen(state: AppState, scope: CoroutineScope) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (state.isDirect) {
            DirectModeCard(state)
            DirectConfigCard(state)
            AboutCard(state)
        } else {
            AccountCard(state)
            AccessModeCard(state)
            ConnectionCard(state, scope)
            RetrievalCard(state)
            AboutCard(state)
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun AccessModeCard(state: AppState) {
    SurfaceCard {
        SectionHeader("接入方式", "中台模式 · Ktor 服务")
        Spacer(Modifier.height(8.dp))
        Text(
            "当前通过 Ktor 中台使用院内知识库、业务流程与附件解析。也可以直接用 API Key 调用模型。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { state.switchToDirectLogin() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("改用 API Key 直连")
        }
    }
}

@Composable
private fun DirectModeCard(state: AppState) {
    SurfaceCard {
        SectionHeader("接入方式", "直连模式 · 不依赖中台")
        Spacer(Modifier.height(8.dp))
        Text("当前模型：${state.directModel}", style = MaterialTheme.typography.bodySmall, color = Ink)
        Text("接口：${state.directBaseUrl}", style = MaterialTheme.typography.labelSmall, color = Muted)
        Spacer(Modifier.height(4.dp))
        Text(
            "直连模式只提供对话，不含院内知识库、流程与附件解析。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = { state.switchToPlatformLogin() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("切回中台登录")
        }
    }
}

@Composable
private fun DirectConfigCard(state: AppState) {
    var keyVisible by remember { mutableStateOf(false) }
    SurfaceCard {
        SectionHeader("直连配置", "Key 仅保存在本次运行")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.directApiKey,
            onValueChange = { state.directApiKey = it },
            label = { Text("API Key", fontSize = 12.sp) },
            singleLine = true,
            visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconAction(
                    glyph = if (keyVisible) Glyph.EyeOff else Glyph.Eye,
                    onClick = { keyVisible = !keyVisible },
                    tint = Muted,
                )
            },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.directBaseUrl,
            onValueChange = { state.directBaseUrl = it },
            label = { Text("接口地址", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = state.directModel,
            onValueChange = { state.directModel = it },
            label = { Text("模型", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "修改后对下一条消息生效；Key 不会上传到中台。",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
}

@Composable
private fun AccountCard(state: AppState) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(46.dp).clip(CircleShape).background(BrandSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.user?.displayName?.take(1) ?: "?",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Brand,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.user?.displayName ?: "未登录",
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
                Text(
                    "@${state.user?.username ?: "-"} · 租户 ${state.user?.tenantId ?: "-"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            TextButton(onClick = { state.logout() }) { Text("退出", color = Muted, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.user?.roles?.forEach { role ->
                RoleChip(role)
            }
            if (state.isAdmin) RoleChip("可管理")
        }
    }
}

@Composable
private fun RoleChip(role: String) {
    val label = roleLabel(role)
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(Color(0xFFF2F4F7))
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 10.5.sp, color = Muted, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun ConnectionCard(state: AppState, scope: CoroutineScope) {
    SurfaceCard {
        SectionHeader("服务端连接", "真机调试需先执行 adb reverse tcp:8080 tcp:8080")
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = { state.serverUrl = it },
            label = { Text("服务端地址", fontSize = 12.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.small,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val health = state.health
            if (health != null) {
                StatusDot(Color(0xFF15803D))
                Spacer(Modifier.width(6.dp))
                Text(
                    "连接正常 · ${health.documents} 篇 / ${health.chunks} 段 · ${if (health.llm.startsWith("openai")) "在线大模型" else "检索模式"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
            } else {
                StatusDot(Faint)
                Spacer(Modifier.width(6.dp))
                Text(
                    "尚未检测",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedButton(
                onClick = { state.checkHealth(scope) },
                shape = CircleShape,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text("检查", fontSize = 12.sp, color = Brand)
            }
        }
    }
}

@Composable
private fun RetrievalCard(state: AppState) {
    SurfaceCard {
        val rag = state.rag
        SectionHeader(
            title = "检索配置",
            subtitle = if (state.isAdmin) "修改后即时生效" else "仅管理员可修改",
            action = if (rag != null && state.isAdmin) {
                { TextButton(onClick = { state.startEditRag() }) { Text("调整", color = Brand, fontSize = 12.sp) } }
            } else null,
        )
        Spacer(Modifier.height(8.dp))
        if (rag == null) {
            Text(
                if (state.isAdmin) "进入「知识库」页可加载配置" else "当前账号无查看权限",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        } else {
            SettingRow("召回数量", "TopK ${rag.topK}")
            SettingRow("切片长度", "${rag.chunkSizeTokens} tokens / 重叠 ${rag.overlapTokens}")
            SettingRow("强制溯源", if (rag.forceCitation) "开启" else "关闭")
            SettingRow("调教加权", if (rag.tuningEnabled) "开启" else "关闭")
        }
    }
}

@Composable
private fun SettingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun AboutCard(state: AppState) {
    SurfaceCard {
        SectionHeader("关于", "MVP v1.0")
        Spacer(Modifier.height(8.dp))
        Text(
            "医院知识中枢：Ktor AI 中台（混合检索 + SSE + 流程引擎）+ Compose Multiplatform 客户端，Android / Desktop / Web 共用一套 UI。",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TechChip("Kotlin")
            TechChip("Ktor")
            TechChip("Compose MP")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "数据位置：服务端 data/（制度原文、索引快照、令牌密钥）；演示环境仅含脱敏样例。",
            style = MaterialTheme.typography.labelSmall,
            color = Faint,
        )
    }
}

@Composable
private fun TechChip(text: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(BrandSoft)
            .padding(horizontal = 9.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 10.5.sp, color = BrandDeep)
    }
}
