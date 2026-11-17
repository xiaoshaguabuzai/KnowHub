package com.example.knowhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.StatsOverview
import com.example.knowhub.state.AppState
import com.example.knowhub.state.DocumentFilter
import kotlinx.coroutines.CoroutineScope

@Composable
fun KnowledgeScreen(state: AppState, scope: CoroutineScope) {
    LaunchedEffect(Unit) { state.loadKnowledge(scope) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { SearchBar(state) }
        item { FilterRow(state) }
        item { DepartmentFilterRow(state) }

        if (state.filteredDocuments.isEmpty()) {
            item {
                EmptyHint(
                    if (state.documents.isEmpty()) "指南库还没有内容，点击右上角「＋」导入第一篇指南。"
                    else "没有匹配的指南，换个关键词或筛选条件试试。"
                )
            }
        } else {
            item {
                Text(
                    if (state.isPatient) "共 ${state.filteredDocuments.size} 篇指南" else "共 ${state.filteredDocuments.size} 篇制度",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            items(state.filteredDocuments, key = { it.docId }) { doc ->
                DocumentRow(doc) { state.openDocument(scope, doc) }
            }
        }

        item { Spacer(Modifier.height(4.dp)); SectionHeader(if (state.isPatient) "便民服务" else "业务流程", "${state.flows.size} 条 · 由 Agent 逐步收集信息") }
        if (state.flows.isEmpty()) {
            item { EmptyHint("暂无可用流程") }
        } else {
            items(state.flows, key = { it.flowId }) { flow -> FlowCard(flow) }
        }

        if (state.isAdmin) {
            item { Spacer(Modifier.height(4.dp)); SectionHeader("运营概览", "管理员可见") }
            item { StatsGrid(state.stats) }
            item { Spacer(Modifier.height(2.dp)); SectionHeader("检索配置", "修改后立即生效") }
            item { RagCard(state.rag) { state.startEditRag() } }
        }

        item {
            Text(
                if (state.isPatient) {
                    "这里只展示对患者开放的就医指南与便民服务；院内管理文件不会出现在患者端，检索阶段同样按身份过滤。"
                } else {
                    "文档在入库时完成 ACL 绑定；问答检索阶段由服务端强制按权限过滤，不依赖前端隐藏。"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }

    if (state.showCreateDocDialog) CreateDocumentDialog(state, scope)
    if (state.editingRag) RagConfigDialog(state, scope)
}

@Composable
private fun SearchBar(state: AppState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(Glyph.Search, size = 16.dp, tint = Faint)
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = state.docQuery,
                onValueChange = { state.docQuery = it },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                cursorBrush = SolidColor(Brand),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    Box {
                        if (state.docQuery.isEmpty()) {
                            Text(if (state.isPatient) "搜索指南，如 挂号 / 医保 / 检查" else "搜索制度名称 / 文号 / 归口科室", style = MaterialTheme.typography.bodyMedium, color = Faint)
                        }
                        inner()
                    }
                },
            )
        }
        if (state.docQuery.isNotEmpty()) {
            IconAction(Glyph.Close, onClick = { state.docQuery = "" }, modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun FilterRow(state: AppState) {
    val counts = remember(state.documents) {
        mapOf(
            DocumentFilter.ALL to state.documents.size,
            DocumentFilter.INDEXED to state.documents.count { it.status == "INDEXED" },
            DocumentFilter.PARSING to state.documents.count { it.status == "PARSING" },
            DocumentFilter.FAILED to state.documents.count { it.status == "FAILED" },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill("全部", counts[DocumentFilter.ALL] ?: 0, state.docFilter == DocumentFilter.ALL) { state.docFilter = DocumentFilter.ALL }
        FilterPill("已索引", counts[DocumentFilter.INDEXED] ?: 0, state.docFilter == DocumentFilter.INDEXED) { state.docFilter = DocumentFilter.INDEXED }
        FilterPill("解析中", counts[DocumentFilter.PARSING] ?: 0, state.docFilter == DocumentFilter.PARSING) { state.docFilter = DocumentFilter.PARSING }
        FilterPill("失败", counts[DocumentFilter.FAILED] ?: 0, state.docFilter == DocumentFilter.FAILED) { state.docFilter = DocumentFilter.FAILED }
    }
}

@Composable
private fun FilterPill(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Ink else Color.White)
            .border(1.dp, if (selected) Color.Transparent else Line, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.sp, color = if (selected) Color.White else Ink, fontWeight = FontWeight.Medium)
        Spacer(Modifier.width(5.dp))
        Text(
            count.toString(),
            fontSize = 10.5.sp,
            color = if (selected) Color.White.copy(alpha = 0.75f) else Faint,
        )
    }
}

@Composable
private fun DepartmentFilterRow(state: AppState) {
    val departments = remember(state.documents) { state.departments }
    if (departments.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DeptPill("全部科室", state.deptFilter == "ALL") { state.deptFilter = "ALL" }
        departments.forEach { dept ->
            DeptPill(dept, state.deptFilter == dept) { state.deptFilter = dept }
        }
    }
}

@Composable
private fun DeptPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrandSoft else Color.Transparent)
            .border(1.dp, if (selected) Brand.copy(alpha = 0.35f) else Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 11.sp,
            color = if (selected) BrandDeep else Muted,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun DocumentRow(doc: DocumentDto, onClick: () -> Unit) {
    val extension = doc.name.substringAfterLast('.', "MD").uppercase().take(4)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White)
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(statusColor(doc.status).copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                extension,
                fontSize = 9.5.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor(doc.status),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                doc.name,
                style = MaterialTheme.typography.titleSmall,
                color = Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    doc.department?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                    doc.docNo?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                    append("${doc.chunkCount} 段")
                    if (doc.aclRoles.isNotEmpty()) {
                        append(" · 仅限 ")
                        append(doc.aclRoles.joinToString("/") { roleLabel(it) })
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            StatusPill(statusLabel(doc.status), statusColor(doc.status))
            Spacer(Modifier.height(5.dp))
            GlyphIcon(Glyph.ArrowRight, size = 13.dp, tint = Faint)
        }
    }
}

@Composable
private fun FlowCard(flow: FlowTemplate) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(TealSoft),
                contentAlignment = Alignment.Center,
            ) {
                GlyphIcon(Glyph.Chat, size = 16.dp, tint = Teal)
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(flow.name, style = MaterialTheme.typography.titleSmall, color = Ink)
                Text(
                    flow.triggerKeywords.joinToString(" / "),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("v${flow.version}", fontSize = 10.5.sp, color = Faint)
        }
        if (flow.description.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(flow.description, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

@Composable
private fun StatsGrid(stats: StatsOverview?) {
    if (stats == null) {
        EmptyHint("暂无统计数据")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("文档", stats.documentCount.toString(), Glyph.Doc, Modifier.weight(1f))
            MetricCard("片段", stats.chunkCount.toString(), Glyph.Book, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("问答", stats.questionCount.toString(), Glyph.Chat, Modifier.weight(1f))
            MetricCard("未命中", stats.missCount.toString(), Glyph.Search, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MetricCard("平均时延", "${stats.avgLatencyMs}ms", Glyph.Refresh, Modifier.weight(1f))
            MetricCard("正负样本", "${stats.positiveFeedback}/${stats.negativeFeedback}", Glyph.Sparkle, Modifier.weight(1f))
        }
        Text(
            "推理模式：${if (stats.llm.startsWith("openai")) "在线大模型 · ${stats.llm.removePrefix("openai-compatible:")}" else "本地检索式（未配置大模型）"}",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
        )
    }
}

@Composable
private fun RagCard(rag: RagConfig?, onEdit: () -> Unit) {
    SurfaceCard {
        if (rag == null) {
            Text("暂无配置（需要管理员权限）", style = MaterialTheme.typography.bodySmall, color = Muted)
            return@SurfaceCard
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("TopK ${rag.topK} · 切片 ${rag.chunkSizeTokens} · 重叠 ${rag.overlapTokens}", fontSize = 13.sp, color = Ink)
                Spacer(Modifier.height(2.dp))
                Text(
                    "强制溯源 ${if (rag.forceCitation) "开" else "关"} · 调教加权 ${if (rag.tuningEnabled) "开" else "关"} · 最低分 ${rag.minScore}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            TextButton(onClick = onEdit) { Text("调整", fontSize = 12.sp, color = Brand) }
        }
    }
}

// ------------------------------------------------------------------ 对话框

@Composable
private fun CreateDocumentDialog(state: AppState, scope: CoroutineScope) {
    AlertDialog(
        onDismissRequest = { state.showCreateDocDialog = false },
        containerColor = Color.White,
        title = { Text("导入知识文档", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = state.newDocName,
                    onValueChange = { state.newDocName = it },
                    label = { Text("文档名称") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = state.newDocContent,
                    onValueChange = { state.newDocContent = it },
                    label = { Text("正文（支持 Markdown）") },
                    minLines = 5,
                    maxLines = 10,
                )
                OutlinedTextField(
                    value = state.newDocAcl,
                    onValueChange = { state.newDocAcl = it },
                    label = { Text("可见角色（逗号分隔，留空=全员）") },
                    placeholder = { Text("例如 hr") },
                    singleLine = true,
                )
                state.docError?.let {
                    Text(it, color = Danger, style = MaterialTheme.typography.labelSmall)
                }
                Text(
                    "保存后异步解析切块，状态会从「解析中」变为「已索引」。",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { state.createTextDocument(scope) }) { Text("导入", color = Brand) }
        },
        dismissButton = {
            TextButton(onClick = { state.showCreateDocDialog = false }) { Text("取消", color = Muted) }
        },
    )
}

@Composable
private fun RagConfigDialog(state: AppState, scope: CoroutineScope) {
    val initial = state.ragDraft ?: RagConfig()
    var topK by remember { mutableStateOf(initial.topK.toString()) }
    var chunkSize by remember { mutableStateOf(initial.chunkSizeTokens.toString()) }
    var overlap by remember { mutableStateOf(initial.overlapTokens.toString()) }
    var minScore by remember { mutableStateOf(initial.minScore.toString()) }
    var forceCitation by remember { mutableStateOf(initial.forceCitation) }
    var tuning by remember { mutableStateOf(initial.tuningEnabled) }

    AlertDialog(
        onDismissRequest = { state.editingRag = false },
        containerColor = Color.White,
        title = { Text("检索配置", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("TopK", topK, { topK = it }, Modifier.weight(1f))
                    NumberField("切片 tokens", chunkSize, { chunkSize = it }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("重叠 tokens", overlap, { overlap = it }, Modifier.weight(1f))
                    NumberField("最低分", minScore, { minScore = it }, Modifier.weight(1f))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("强制溯源", modifier = Modifier.weight(1f), fontSize = 13.sp, color = Ink)
                    Switch(checked = forceCitation, onCheckedChange = { forceCitation = it })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("调教加权", modifier = Modifier.weight(1f), fontSize = 13.sp, color = Ink)
                    Switch(checked = tuning, onCheckedChange = { tuning = it })
                }
                Text(
                    "范围：TopK 1~20，切片 64~1024，重叠 < 切片，最低分 0~1",
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                state.ragDraft = RagConfig(
                    topK = topK.toIntOrNull() ?: initial.topK,
                    chunkSizeTokens = chunkSize.toIntOrNull() ?: initial.chunkSizeTokens,
                    overlapTokens = overlap.toIntOrNull() ?: initial.overlapTokens,
                    minScore = minScore.toDoubleOrNull() ?: initial.minScore,
                    forceCitation = forceCitation,
                    tuningEnabled = tuning,
                )
                state.saveRag(scope)
            }) { Text("保存", color = Brand) }
        },
        dismissButton = {
            TextButton(onClick = { state.editingRag = false }) { Text("取消", color = Muted) }
        },
    )
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label, fontSize = 11.sp) },
        singleLine = true,
        modifier = modifier,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
