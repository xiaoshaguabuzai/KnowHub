package com.example.knowhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.knowhub.core.model.Roles
import com.example.knowhub.state.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val usernamePattern = Regex("^[A-Za-z0-9_\\u4e00-\\u9fa5]{2,32}$")

private fun usernameError(value: String): String? = when {
    value.isBlank() -> "请输入账号或工号"
    value.length < 2 -> "账号至少 2 个字符"
    !usernamePattern.matches(value) -> "仅支持字母、数字、下划线或中文"
    else -> null
}

private fun passwordError(value: String): String? = when {
    value.isBlank() -> "请输入密码"
    value.length < 6 -> "密码至少 6 位"
    !value.any { it.isLetter() } || !value.any { it.isDigit() } -> "密码需同时包含字母与数字"
    else -> null
}

@Composable
fun LoginScreen(state: AppState, scope: CoroutineScope) {
    var passwordVisible by remember { mutableStateOf(false) }
    var directMode by remember { mutableStateOf(state.loginStartDirect) }
    var submitted by remember { mutableStateOf(false) }
    var usernameTouched by remember { mutableStateOf(false) }
    var passwordTouched by remember { mutableStateOf(false) }
    var serverOk by remember { mutableStateOf<Boolean?>(null) }
    val passwordFocus = remember { FocusRequester() }

    val usernameProblem = usernameError(state.username)
    val passwordProblem = passwordError(state.password)
    val showUsernameProblem = (submitted || usernameTouched) && usernameProblem != null
    val showPasswordProblem = (submitted || passwordTouched) && passwordProblem != null

    fun submit() {
        submitted = true
        if (usernameProblem == null && passwordProblem == null && !state.loginBusy) {
            state.login(scope)
        }
    }

    LaunchedEffect(state.serverUrl) {
        serverOk = null
        delay(350)
        state.client.baseUrl = state.serverUrl.trim().trimEnd('/')
        serverOk = runCatching { state.client.health() }.isSuccess
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFFE9EFFF), Paper, Paper)))
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 430.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BrandMark(size = 56.dp, corner = 17.dp)
            Spacer(Modifier.height(14.dp))
            Text("KnowHub", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink)
            Spacer(Modifier.height(2.dp))
            Text("医院知识中枢", style = MaterialTheme.typography.bodySmall, color = Muted)
            Spacer(Modifier.height(4.dp))
            Text("制度指南 · 就医流程 · 原文可溯", style = MaterialTheme.typography.labelSmall, color = Faint)
            Spacer(Modifier.height(22.dp))

            SurfaceCard(padding = 18.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFFF2F4F7))
                        .padding(3.dp),
                ) {
                    ModeTab("中台模式", !directMode) { directMode = false }
                    ModeTab("直连模式", directMode) { directMode = true }
                }
                Spacer(Modifier.height(12.dp))
                if (directMode) {
                    DirectLoginForm(state)
                } else {
                OutlinedTextField(
                    value = state.username,
                    onValueChange = {
                        state.username = it
                        usernameTouched = true
                    },
                    label = { Text("账号 / 工号", fontSize = 12.sp) },
                    singleLine = true,
                    isError = showUsernameProblem,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    supportingText = {
                        Text(
                            if (showUsernameProblem) usernameProblem ?: "" else "支持字母、数字、下划线或中文",
                            fontSize = 10.5.sp,
                            color = if (showUsernameProblem) Danger else Faint,
                        )
                    },
                )

                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.password,
                    onValueChange = {
                        state.password = it
                        passwordTouched = true
                    },
                    label = { Text("密码", fontSize = 12.sp) },
                    singleLine = true,
                    isError = showPasswordProblem,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
                    shape = MaterialTheme.shapes.small,
                    trailingIcon = {
                        IconAction(
                            glyph = if (passwordVisible) Glyph.EyeOff else Glyph.Eye,
                            onClick = { passwordVisible = !passwordVisible },
                            tint = if (showPasswordProblem) Danger else Muted,
                        )
                    },
                    supportingText = {
                        Text(
                            if (showPasswordProblem) passwordProblem ?: "" else "至少 6 位，需同时包含字母与数字",
                            fontSize = 10.5.sp,
                            color = if (showPasswordProblem) Danger else Faint,
                        )
                    },
                )

                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = state.serverUrl,
                    onValueChange = { state.serverUrl = it },
                    label = { Text("服务端地址", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    supportingText = {
                        Text("真机调试前执行 adb reverse tcp:8080 tcp:8080", fontSize = 10.5.sp, color = Faint)
                    },
                )

                ServerStatusRow(serverOk) {
                    scope.launch {
                        serverOk = null
                        state.client.baseUrl = state.serverUrl.trim().trimEnd('/')
                        serverOk = runCatching { state.client.health() }.isSuccess
                    }
                }

                state.loginError?.let { message ->
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Danger.copy(alpha = 0.06f), MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(Danger, 6.dp)
                        Spacer(Modifier.width(7.dp))
                        Text(message, color = Danger, style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { submit() },
                    enabled = !state.loginBusy,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = MaterialTheme.shapes.small,
                    colors = ButtonDefaults.buttonColors(containerColor = Brand),
                ) {
                    if (state.loginBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White,
                        )
                    } else {
                        Text("登录", fontSize = 15.sp)
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(1.dp).background(Line))
                    Text("或以演示身份快速登录", modifier = Modifier.padding(horizontal = 10.dp), fontSize = 10.5.sp, color = Faint)
                    Box(Modifier.weight(1f).height(1.dp).background(Line))
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuickLogin("患者", "patient", "patient1234", state, scope, Modifier.weight(1f)) { submit() }
                    QuickLogin("医师", "demo", "demo1234", state, scope, Modifier.weight(1f)) { submit() }
                    QuickLogin("医务处", "admin", "admin1234", state, scope, Modifier.weight(1f)) { submit() }
                    QuickLogin("人事科", "hr", "hr1234", state, scope, Modifier.weight(1f)) { submit() }
                }
                }
            }

            Spacer(Modifier.height(18.dp))
            Text(
                "演示数据仅用于院内演示环境",
                style = MaterialTheme.typography.labelSmall,
                color = Faint,
            )
        }
    }
}

@Composable
private fun ServerStatusRow(serverOk: Boolean?, onRetry: () -> Unit) {
    val color = when (serverOk) {
        true -> Color(0xFF15803D)
        false -> Danger
        null -> Faint
    }
    val text = when (serverOk) {
        true -> "服务端可达"
        false -> "无法连接服务端，请检查地址或 adb reverse"
        null -> "正在检测服务端…"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color, 6.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 10.5.sp, color = if (serverOk == false) Danger else Muted, modifier = Modifier.weight(1f))
        if (serverOk == false) {
            TextButton(onClick = onRetry, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)) {
                Text("重试", fontSize = 11.sp, color = Brand)
            }
        }
    }
}

@Composable
private fun QuickLogin(
    label: String,
    username: String,
    password: String,
    state: AppState,
    scope: CoroutineScope,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit,
) {
    TextButton(
        onClick = {
            state.username = username
            state.password = password
            onSubmit()
        },
        modifier = modifier
            .height(38.dp)
            .background(Color(0xFFF2F4F7), CircleShape),
        shape = CircleShape,
    ) {
        Text(label, fontSize = 11.5.sp, color = Ink, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RolePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (selected) BrandSoft else Color(0xFFF2F4F7))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) Brand else Muted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun RowScope.ModeTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.5.sp,
            color = if (selected) Ink else Muted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun DirectLoginForm(state: AppState) {
    var keyVisible by remember { mutableStateOf(false) }
    var touched by remember { mutableStateOf(false) }
    val keyMissing = touched && state.directApiKey.isBlank()

    Text("演示身份（决定可见的指南 / 制度）", style = MaterialTheme.typography.labelSmall, color = Muted)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(
            Roles.PATIENT to "患者",
            Roles.MEMBER to "医师",
            Roles.ADMIN to "医务处",
            Roles.HR to "人事科",
        ).forEach { (role, label) ->
            RolePill(label, state.localRole == role) { state.localRole = role }
        }
    }
    Spacer(Modifier.height(12.dp))

    OutlinedTextField(
        value = state.directApiKey,
        onValueChange = {
            state.directApiKey = it
            touched = true
        },
        label = { Text("API Key", fontSize = 12.sp) },
        singleLine = true,
        isError = keyMissing,
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconAction(
                glyph = if (keyVisible) Glyph.EyeOff else Glyph.Eye,
                onClick = { keyVisible = !keyVisible },
                tint = if (keyMissing) Danger else Muted,
            )
        },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        supportingText = {
            Text(
                if (keyMissing) "请输入 API Key" else "仅保存在本次运行，不会上传到中台",
                fontSize = 10.5.sp,
                color = if (keyMissing) Danger else Faint,
            )
        },
    )

    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = state.directBaseUrl,
        onValueChange = { state.directBaseUrl = it },
        label = { Text("接口地址", fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        supportingText = {
            Text("OpenAI 兼容地址，例如 https://api.openai.com/v1", fontSize = 10.5.sp, color = Faint)
        },
    )

    Spacer(Modifier.height(4.dp))
    OutlinedTextField(
        value = state.directModel,
        onValueChange = { state.directModel = it },
        label = { Text("模型", fontSize = 12.sp) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        supportingText = {
            Text("例如 gpt-4o-mini、qwen-plus、deepseek-chat", fontSize = 10.5.sp, color = Faint)
        },
    )

    state.loginError?.let { message ->
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Danger.copy(alpha = 0.06f), MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(Danger, 6.dp)
            Spacer(Modifier.width(7.dp))
            Text(message, color = Danger, style = MaterialTheme.typography.labelSmall)
        }
    }

    Spacer(Modifier.height(12.dp))
    Button(
        onClick = {
            touched = true
            state.startDirect()
        },
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(containerColor = Brand),
    ) {
        Text("开始对话", fontSize = 15.sp)
    }
    Spacer(Modifier.height(10.dp))
    Text(
        "本地已内置与中台一致的 36 篇知识、4 条流程与附件解析；API Key 仅保存在本次运行，可随时在设置切回中台。",
        style = MaterialTheme.typography.labelSmall,
        color = Muted,
    )
}

private fun roleLabelOf(role: String): String = role
