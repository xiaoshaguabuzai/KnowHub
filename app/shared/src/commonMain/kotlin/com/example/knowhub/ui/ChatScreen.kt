package com.example.knowhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.knowhub.core.model.FlowStepInfo
import com.example.knowhub.core.model.MessageDto
import com.example.knowhub.core.model.MessageRoles
import com.example.knowhub.core.model.SourceRef
import com.example.knowhub.input.rememberPlatformInput
import com.example.knowhub.state.AppState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val staffSuggestions = listOf(
    "住院患者出院带药有什么要求？",
    "危急值要在多久内上报？",
    "我要办理住院登记",
)

private val patientSuggestions = listOf(
    "抽血前能吃饭吗？",
    "医保住院怎么报销？",
    "我要预约门诊",
)

private val directSuggestions = listOf(
    "帮我解释一下什么是高血压？",
    "体检报告里的血脂指标怎么看？",
    "用通俗的话讲讲糖尿病饮食注意事项",
)

@Composable
fun ChatScreen(state: AppState, scope: CoroutineScope) {
    val scrollState = rememberScrollState()
    var sheetMessage by remember { mutableStateOf<MessageDto?>(null) }

    LaunchedEffect(state.messages.size, state.streaming, state.streamText.length) {
        if (state.messages.isEmpty() && !state.streaming) {
            // 切到新会话时回到顶部，避免继承上一个会话的滚动位置
            scrollState.scrollTo(0)
            return@LaunchedEffect
        }
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SessionStrip(state, scope)
        state.currentSession?.pendingFlowName?.let { flowName ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Amber.copy(alpha = 0.08f))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(Amber, 6.dp)
                Spacer(Modifier.width(7.dp))
                Text("流程进行中：$flowName · 直接回复即可继续", fontSize = 11.5.sp, color = Amber)
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (state.messages.isEmpty() && !state.streaming) {
                EmptyChatHero(isPatient = state.isPatient, isDirect = state.isDirect, onPick = { state.send(scope, it) })
            }
            state.messages.forEach { message ->
                if (message.role == MessageRoles.USER) {
                    UserMessage(message)
                } else {
                    AssistantMessage(
                        message = message,
                        onShowSources = { sheetMessage = message },
                        onFeedback = { rating, chunkId ->
                            state.submitFeedback(scope, message.messageId, rating, chunkId)
                        },
                    )
                }
            }
            if (state.streaming) {
                StreamingMessage(
                    text = state.streamText,
                    sources = state.streamSources,
                    steps = state.flowSteps,
                    onShowSources = {
                        if (state.streamSources.isNotEmpty()) {
                            sheetMessage = MessageDto(
                                messageId = "streaming",
                                sessionId = state.currentSessionId.orEmpty(),
                                role = MessageRoles.ASSISTANT,
                                content = state.streamText,
                                sources = state.streamSources,
                            )
                        }
                    },
                )
            }
        }

        Composer(state, scope)
    }

    sheetMessage?.let { message ->
        SourceSheet(
            message = message,
            rating = state.messages.firstOrNull { it.messageId == message.messageId }?.rating ?: message.rating,
            canRate = !message.messageId.startsWith("local") && message.messageId != "streaming",
            onDismiss = { sheetMessage = null },
            onFeedback = { rating, chunkId ->
                if (!message.messageId.startsWith("local") && message.messageId != "streaming") {
                    state.submitFeedback(scope, message.messageId, rating, chunkId)
                }
            },
        )
    }
}

// ------------------------------------------------------------------ 会话条

@Composable
private fun SessionStrip(state: AppState, scope: CoroutineScope) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.sessions.forEach { session ->
                Pill(
                    text = session.title,
                    selected = session.sessionId == state.currentSessionId,
                    onClick = { state.openSession(scope, session.sessionId) },
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        IconAction(
            glyph = Glyph.Plus,
            onClick = { state.createSession(scope) },
            tint = Brand,
            background = BrandSoft,
            modifier = Modifier.size(34.dp),
        )
    }
}

// ------------------------------------------------------------------ 空态

@Composable
private fun EmptyChatHero(isPatient: Boolean, isDirect: Boolean, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(size = 42.dp, corner = 13.dp)
            Spacer(Modifier.width(11.dp))
            Column {
                Text(
                    if (isPatient) "把就医流程问明白" else "把医院制度问明白",
                    style = MaterialTheme.typography.titleLarge,
                    color = Ink,
                )
                Text(
                    when {
                        isDirect -> "直连模式 · 本地知识库 + 你的模型"
                        isPatient -> "挂号 · 检查 · 住院 · 报销"
                        else -> "查制度 · 走流程 · 看原文，答必有据"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityCard(
                glyph = Glyph.Book,
                title = if (isPatient) "查指南" else "查制度",
                desc = if (isPatient) "挂号 · 检查 · 医保" else "门诊 · 住院 · 核心制度",
                modifier = Modifier.weight(1f),
            )
            CapabilityCard(
                glyph = Glyph.Chat,
                title = if (isPatient) "约门诊" else "走流程",
                desc = if (isPatient) "科室 · 日期 · 手机号" else "住院登记 · 危急值",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CapabilityCard(
                glyph = Glyph.Doc,
                title = "看报告",
                desc = if (isPatient) "拍检验单问含义" else "引用可打开原文",
                modifier = Modifier.weight(1f),
            )
            CapabilityCard(
                glyph = Glyph.Sparkle,
                title = if (isPatient) "可追问" else "可调教",
                desc = if (isPatient) "费用 · 复诊 · 投诉" else "反馈影响召回",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("试试这样问", style = MaterialTheme.typography.labelMedium, color = Muted)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (isPatient) patientSuggestions else staffSuggestions).forEach { text ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(1.dp, Line, RoundedCornerShape(12.dp))
                        .clickable { onPick(text) }
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlyphIcon(Glyph.Search, size = 15.dp, tint = Faint)
                    Spacer(Modifier.width(8.dp))
                    Text(text, fontSize = 14.sp, color = Ink, modifier = Modifier.weight(1f))
                    GlyphIcon(Glyph.ArrowRight, size = 14.dp, tint = Faint)
                }
            }
        }
    }
}

@Composable
private fun CapabilityCard(glyph: Glyph, title: String, desc: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .height(110.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(13.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BrandSoft),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, size = 16.dp, tint = Brand)
        }
        Spacer(Modifier.height(8.dp))
        Text(title, fontSize = 13.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
        Spacer(Modifier.height(2.dp))
        Text(
            desc,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ 消息

@Composable
private fun UserMessage(message: MessageDto) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(18.dp, 18.dp, 5.dp, 18.dp))
                .background(Brand)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(message.content, style = MaterialTheme.typography.bodyLarge, color = Color.White)
        }
    }
}

@Composable
private fun AssistantMessage(
    message: MessageDto,
    onShowSources: () -> Unit,
    onFeedback: (String, String?) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        AssistantAvatar()
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("KnowHub 助手", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                if (message.intent == "flow") {
                    Spacer(Modifier.width(6.dp))
                    StatusPill("流程", Teal)
                }
                Spacer(Modifier.weight(1f))
                if (message.latencyMs > 0) {
                    Text("${message.latencyMs} ms", fontSize = 10.sp, color = Faint)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(message.content, style = MaterialTheme.typography.bodyLarge, color = Ink)

            if (message.sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                CitationChips(message.sources, onShowSources)
                if (!message.messageId.startsWith("local")) {
                    Spacer(Modifier.height(4.dp))
                    FeedbackRow(message.rating, onFeedback)
                }
            }
        }
    }
}

@Composable
private fun StreamingMessage(
    text: String,
    sources: List<SourceRef>,
    steps: List<FlowStepInfo>,
    onShowSources: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        AssistantAvatar()
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("KnowHub 助手", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.width(6.dp))
                CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = Brand)
            }
            if (steps.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                FlowTimeline(steps)
            }
            Spacer(Modifier.height(8.dp))
            if (text.isBlank()) {
                Text("正在检索知识库…", style = MaterialTheme.typography.bodyLarge, color = Muted)
            } else {
                Text(text + "▍", style = MaterialTheme.typography.bodyLarge, color = Ink)
            }
            if (sources.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                CitationChips(sources, onShowSources)
            }
        }
    }
}

@Composable
private fun AssistantAvatar() {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(TealSoft),
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(Glyph.Sparkle, size = 15.dp, tint = Teal)
    }
}

@Composable
private fun FlowTimeline(steps: List<FlowStepInfo>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEach { step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(if (step.status == "FAILED") Danger else Teal, 5.dp)
                Spacer(Modifier.width(7.dp))
                Text(
                    "${nodeTypeLabel(step.nodeType)} · ${step.desc}",
                    fontSize = 11.sp,
                    color = Muted,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun nodeTypeLabel(nodeType: String): String = when (nodeType) {
    "collect_param" -> "收集参数"
    "tool_invoke" -> "调用工具"
    "branch" -> "条件分支"
    "source_ref" -> "引用知识"
    "message" -> "输出结果"
    else -> nodeType
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CitationChips(sources: List<SourceRef>, onShowSources: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        sources.take(3).forEachIndexed { index, source ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF2F4F7))
                    .clickable { onShowSources() }
                    .padding(horizontal = 7.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(16.dp).clip(CircleShape).background(BrandSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        fontSize = 9.sp,
                        lineHeight = 9.sp,
                        color = Brand,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    shortDocName(source.docName),
                    fontSize = 11.sp,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(" · P${source.page}", fontSize = 10.5.sp, color = Muted)
            }
        }
        if (sources.size > 3) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrandSoft)
                    .clickable { onShowSources() }
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("+${sources.size - 3} 条来源", fontSize = 10.5.sp, color = Brand, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun FeedbackRow(rating: Int, onFeedback: (String, String?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("这份回答", fontSize = 10.5.sp, color = Faint)
        TextButton(onClick = { onFeedback("up", null) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text(
                if (rating > 0) "👍 已标记有用" else "👍 有用",
                fontSize = 10.5.sp,
                color = if (rating > 0) Brand else Muted,
            )
        }
        TextButton(onClick = { onFeedback("down", null) }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text(
                if (rating < 0) "👎 已标记不准" else "👎 不准",
                fontSize = 10.5.sp,
                color = if (rating < 0) Danger else Muted,
            )
        }
    }
}

private fun shortDocName(name: String): String {
    val base = name.substringBeforeLast('.')
    return if (base.length <= 9) base else base.take(9) + "…"
}

// ------------------------------------------------------------------ 引用来源面板

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceSheet(
    message: MessageDto,
    rating: Int,
    canRate: Boolean,
    onDismiss: () -> Unit,
    onFeedback: (String, String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("引用来源", style = MaterialTheme.typography.titleMedium, color = Ink)
            Text(
                "共 ${message.sources.size} 条 · 每条都可在知识库中打开原文",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
            message.sources.forEachIndexed { index, source ->
                SurfaceCard(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(20.dp).clip(CircleShape).background(BrandSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("${index + 1}", fontSize = 10.sp, color = Brand, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "《${source.docName}》",
                            style = MaterialTheme.typography.titleSmall,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text("第 ${source.page} 页", fontSize = 10.5.sp, color = Muted)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(source.snippet, style = MaterialTheme.typography.bodySmall, color = Ink)
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        source.section?.takeIf { it.isNotBlank() }?.let {
                            Text(it, fontSize = 10.sp, color = Muted)
                            Spacer(Modifier.width(8.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Text("匹配度 ${formatScore(source.score)}", fontSize = 10.sp, color = Faint)
                    }
                }
            }
            if (canRate && message.sources.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("这条回答有帮助吗？", fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onFeedback("up", message.sources.first().chunkId) }) {
                        Text(if (rating > 0) "👍 已记" else "👍 有用", fontSize = 12.sp, color = if (rating > 0) Brand else Muted)
                    }
                    TextButton(onClick = { onFeedback("down", message.sources.first().chunkId) }) {
                        Text(if (rating < 0) "👎 已记" else "👎 不准", fontSize = 12.sp, color = if (rating < 0) Danger else Muted)
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 输入框

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Composer(state: AppState, scope: CoroutineScope) {
    var draft by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    val platform = rememberPlatformInput()

    fun appendToDraft(text: String) {
        if (text.isBlank()) return
        draft = if (draft.isBlank()) text.trim() else draft.trimEnd() + " " + text.trim()
    }

    fun submit() {
        val text = draft.trim()
        if ((text.isNotEmpty() || state.pendingAttachments.isNotEmpty()) && !state.streaming) {
            state.send(scope, text)
            draft = ""
        }
    }

    Surface(color = Color.White) {
        Column(modifier = Modifier.fillMaxWidth().imePadding()) {
            DividerLine()
            AttachmentStrip(state)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                IconAction(
                    glyph = Glyph.Plus,
                    onClick = { showAttachSheet = true },
                    tint = Ink,
                    background = Color(0xFFF2F4F7),
                    modifier = Modifier.size(38.dp),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Color(0xFFF2F4F7))
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = Ink),
                        cursorBrush = SolidColor(Brand),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { submit() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                    if (event.isShiftPressed) false else { submit(); true }
                                } else {
                                    false
                                }
                            },
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) {
                                    Text(
                                        if (state.isPatient) "问就医流程，或传报告" else "问制度，或传文件",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = Faint,
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
                if (platform.voiceSupported) {
                    Spacer(Modifier.width(8.dp))
                    IconAction(
                        glyph = Glyph.Mic,
                        onClick = {
                            platform.startVoice(
                                onResult = { appendToDraft(it) },
                                onError = { state.error = it },
                            )
                        },
                        tint = Muted,
                        background = Color(0xFFF2F4F7),
                        modifier = Modifier.size(38.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                val active = state.streaming || draft.isNotBlank() || state.pendingAttachments.isNotEmpty()
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(if (active) Brand else Color(0xFFE4E7EC))
                        .clickable(enabled = active) {
                            if (state.streaming) state.stopStream() else submit()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.streaming) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                    } else {
                        GlyphIcon(Glyph.Send, size = 19.dp, tint = if (active) Color.White else Faint)
                    }
                }
            }
        }
    }

    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = Color.White,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("添加内容", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    "拍照或文件会先解析/识别，再随问题一起发送",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
                Spacer(Modifier.height(4.dp))
                AttachOption(
                    glyph = Glyph.Camera,
                    title = "拍照识别",
                    desc = if (platform.cameraSupported) "拍摄检验单 / 报告，识别文字填入输入框" else "当前端暂不支持拍照",
                    enabled = platform.cameraSupported,
                ) {
                    showAttachSheet = false
                    platform.takePhoto(
                        onPicked = { picked ->
                            state.uploadPickedAttachment(scope, picked)
                            scope.launch {
                                val text = cleanOcrText(platform.recognizeText(picked))
                                if (text.isNotBlank()) appendToDraft(text)
                            }
                        },
                        onError = { state.error = it },
                    )
                }
                AttachOption(
                    glyph = Glyph.Paperclip,
                    title = "上传文件",
                    desc = if (platform.fileSupported) "PDF / 文本 / 图片，解析后作为回答依据" else "当前端暂不支持文件上传",
                    enabled = platform.fileSupported,
                ) {
                    showAttachSheet = false
                    platform.pickFile(
                        onPicked = { picked ->
                            state.uploadPickedAttachment(scope, picked)
                            // 图片文件同样走端侧 OCR，把文字填进输入框
                            if (picked.mime.startsWith("image/")) {
                                scope.launch {
                                    val text = cleanOcrText(platform.recognizeText(picked))
                                    if (text.isNotBlank()) appendToDraft(text)
                                }
                            }
                        },
                        onError = { state.error = it },
                    )
                }
                AttachOption(
                    glyph = Glyph.Mic,
                    title = "语音输入",
                    desc = if (platform.voiceSupported) "用系统语音识别转成文字" else "当前端暂不支持语音",
                    enabled = platform.voiceSupported,
                ) {
                    showAttachSheet = false
                    platform.startVoice(
                        onResult = { appendToDraft(it) },
                        onError = { state.error = it },
                    )
                }
            }
        }
    }
}

/** 去掉 OCR 噪声（单字符行、空行），压缩长度，避免糊满输入框。 */
private fun cleanOcrText(raw: String): String {
    val lines = raw.split('\n').map { it.trim() }
        .filter { it.length >= 2 || it.all { ch -> ch.isDigit() } }
    return lines.joinToString(" ").replace(Regex("\\s{2,}"), " ").trim().take(600)
}

@Composable
private fun AttachmentStrip(state: AppState) {
    if (state.pendingAttachments.isEmpty() && !state.attachmentBusy) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        state.pendingAttachments.forEach { attachment ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFF2F4F7))
                    .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlyphIcon(Glyph.Paperclip, size = 12.dp, tint = Muted)
                Spacer(Modifier.width(5.dp))
                Text(
                    attachment.name,
                    fontSize = 11.sp,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 150.dp),
                )
                Text(
                    if (attachment.hasText) " · ${attachment.chars}字" else " · 图片",
                    fontSize = 9.5.sp,
                    color = Faint,
                )
                IconAction(
                    glyph = Glyph.Close,
                    onClick = { state.removeAttachment(attachment.attachmentId) },
                    tint = Faint,
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        if (state.attachmentBusy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = Brand)
                Spacer(Modifier.width(6.dp))
                Text("上传中…", fontSize = 10.5.sp, color = Muted)
            }
        }
    }
}

@Composable
private fun AttachOption(
    glyph: Glyph,
    title: String,
    desc: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (enabled) Color(0xFFF7F8FC) else Color(0xFFF2F4F7))
            .clickable(enabled = enabled) { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(if (enabled) BrandSoft else Color.White),
            contentAlignment = Alignment.Center,
        ) {
            GlyphIcon(glyph, size = 18.dp, tint = if (enabled) Brand else Faint)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Ink else Faint)
            Text(desc, fontSize = 11.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (enabled) GlyphIcon(Glyph.ArrowRight, size = 14.dp, tint = Faint)
    }
}
