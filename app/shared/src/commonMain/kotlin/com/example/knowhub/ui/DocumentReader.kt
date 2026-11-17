package com.example.knowhub.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.knowhub.state.AppState
import kotlinx.coroutines.CoroutineScope

/**
 * 文档阅读器：直接读原文，按 Markdown 标题/条款/页码做排版，
 * 正文可长按选择复制；管理员可在此重建索引或删除。
 */
@Composable
fun DocumentReaderScreen(state: AppState, scope: CoroutineScope) {
    val doc = state.viewingDoc ?: return
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(Color.White)) {
        Surface(color = Color.White) {
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAction(Glyph.Back, onClick = { state.closeDocument() })
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            doc.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                doc.department?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                                doc.docNo?.takeIf { it.isNotBlank() }?.let { append(it).append(" · ") }
                                append("${doc.pageCount} 页 · ${doc.chunkCount} 段 · ${statusLabel(doc.status)}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = Muted,
                        )
                    }
                    if (state.isAdmin) {
                        Box {
                            IconAction(Glyph.More, onClick = { menuOpen = true })
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("重建索引") },
                                    onClick = {
                                        menuOpen = false
                                        state.reindexDocument(scope, doc.docId)
                                        state.closeDocument()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("删除文档") },
                                    onClick = {
                                        menuOpen = false
                                        confirmDelete = true
                                    },
                                )
                            }
                        }
                    }
                }
                DividerLine()
            }
        }

        when {
            state.docLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Brand, strokeWidth = 2.dp)
            }

            state.docViewError != null -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(state.docViewError ?: "加载失败", color = Danger, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                TextButton(onClick = { state.openDocument(scope, doc) }) { Text("重试", color = Brand) }
            }

            else -> {
                val detail = state.docDetail
                if (detail == null) {
                    Box(Modifier.weight(1f))
                } else {
                    val blocks = remember(detail.content) { parseDocumentBlocks(detail.content) }
                    SelectionContainer(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    MetaChip("${doc.pageCount} 页")
                                    MetaChip("${doc.chunkCount} 段索引")
                                    doc.docNo?.takeIf { it.isNotBlank() }?.let { MetaChip("文号 $it") }
                                    doc.department?.takeIf { it.isNotBlank() }?.let { MetaChip("归口 $it") }
                                    doc.effectiveDate?.takeIf { it.isNotBlank() }?.let { MetaChip("生效 $it") }
                                    MetaChip(if (doc.aclRoles.isEmpty()) "全院可见" else "仅限 ${doc.aclRoles.joinToString("/") { roleLabel(it) }}")
                                    MetaChip(if (detail.fromCache) "原文" else "由片段还原")
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                            items(blocks) { block -> BlockView(block) }
                            item {
                                Spacer(Modifier.height(18.dp))
                                DividerLine()
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    if (detail.truncated) "内容过长，仅展示前 20 万字符。" else "— 全文完 —",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Faint,
                                )
                                Spacer(Modifier.height(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Color.White,
            title = { Text("删除文档？", style = MaterialTheme.typography.titleMedium) },
            text = { Text("《${doc.name}》及其全部索引片段将被删除，且无法恢复。", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    state.deleteDocument(scope, doc.docId)
                    state.closeDocument()
                }) { Text("删除", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消", color = Muted) }
            },
        )
    }
}

@Composable
private fun MetaChip(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFF2F4F7))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text, fontSize = 10.5.sp, color = Muted)
    }
}

// ------------------------------------------------------------------ 排版

private sealed interface DocBlock {
    data class Heading(val level: Int, val text: String) : DocBlock
    data class Section(val text: String) : DocBlock
    data class Item(val marker: String, val text: String) : DocBlock
    data class Paragraph(val text: String) : DocBlock
    data class Page(val page: Int) : DocBlock
}

private val pageMarker = Regex("^── 第 (\\d+) 页 ──$")
private val headingMarker = Regex("^(#{1,6})\\s*(.+)$")
private val sectionMarker = Regex("^([一二三四五六七八九十百]+、.*)$")
private val orderedMarker = Regex("^(\\d+[.、])\\s*(.+)$")
private val bulletMarker = Regex("^([-*•])\\s*(.+)$")

private fun parseDocumentBlocks(content: String): List<DocBlock> {
    val blocks = ArrayList<DocBlock>()
    for (raw in content.split("\n")) {
        val line = raw.trim().replace("**", "").replace("__", "")
        if (line.isEmpty()) continue

        val page = pageMarker.matchEntire(line)
        if (page != null) {
            blocks.add(DocBlock.Page(page.groupValues[1].toIntOrNull() ?: 1))
            continue
        }
        val heading = headingMarker.matchEntire(line)
        if (heading != null) {
            blocks.add(DocBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            continue
        }
        val section = sectionMarker.matchEntire(line)
        if (section != null) {
            blocks.add(DocBlock.Section(section.groupValues[1]))
            continue
        }
        val ordered = orderedMarker.matchEntire(line)
        if (ordered != null) {
            blocks.add(DocBlock.Item(ordered.groupValues[1], ordered.groupValues[2]))
            continue
        }
        val bullet = bulletMarker.matchEntire(line)
        if (bullet != null) {
            blocks.add(DocBlock.Item("•", bullet.groupValues[2]))
            continue
        }
        blocks.add(DocBlock.Paragraph(line))
    }
    return blocks
}

@Composable
private fun BlockView(block: DocBlock) {
    when (block) {
        is DocBlock.Page -> Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(Line))
            Text(
                "第 ${block.page} 页",
                modifier = Modifier.padding(horizontal = 10.dp),
                fontSize = 10.sp,
                color = Faint,
            )
            Box(Modifier.weight(1f).height(1.dp).background(Line))
        }

        is DocBlock.Heading -> Text(
            text = block.text,
            modifier = Modifier.padding(top = if (block.level <= 1) 10.dp else 14.dp, bottom = 4.dp),
            fontSize = when (block.level) {
                1 -> 22.sp
                2 -> 18.sp
                else -> 16.sp
            },
            lineHeight = when (block.level) {
                1 -> 30.sp
                else -> 25.sp
            },
            fontWeight = FontWeight.Bold,
            color = Ink,
        )

        is DocBlock.Section -> Text(
            text = block.text,
            modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
            fontSize = 16.5.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            color = BrandDeep,
        )

        is DocBlock.Item -> Row(modifier = Modifier.padding(bottom = 5.dp)) {
            Text(
                block.marker,
                modifier = Modifier.width(24.dp),
                fontSize = 14.sp,
                lineHeight = 25.sp,
                color = Brand,
                fontWeight = FontWeight.Medium,
            )
            Text(
                block.text,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
                lineHeight = 26.sp,
            )
        }

        is DocBlock.Paragraph -> Text(
            text = block.text,
            modifier = Modifier.padding(bottom = 7.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Ink,
            lineHeight = 26.sp,
        )
    }
}
