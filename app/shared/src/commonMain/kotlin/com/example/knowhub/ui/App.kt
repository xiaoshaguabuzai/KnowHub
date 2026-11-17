package com.example.knowhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.knowhub.state.AppState
import com.example.knowhub.state.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun App() {
    val state = remember { AppState() }
    val scope = rememberCoroutineScope()

    KnowHubTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (!state.loggedIn) {
                LoginScreen(state, scope)
            } else if (state.viewingDoc != null) {
                DocumentReaderScreen(state, scope)
            } else {
                MainScreen(state, scope)
            }
        }
    }
}

@Composable
private fun MainScreen(state: AppState, scope: CoroutineScope) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        AppHeader(state, scope, menuOpen) { menuOpen = it }

        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            state.error?.let {
                InlineBanner(it, isError = true) { state.dismissError() }
                Spacer(Modifier.size(6.dp))
            }
            state.info?.let {
                InlineBanner(it, isError = false) { state.dismissInfo() }
                Spacer(Modifier.size(6.dp))
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (state.tab) {
                Tab.CHAT -> ChatScreen(state, scope)
                Tab.KNOWLEDGE -> KnowledgeScreen(state, scope)
                Tab.SETTINGS -> SettingsScreen(state, scope)
            }
        }

        BottomNav(state)
    }
}

@Composable
private fun AppHeader(
    state: AppState,
    scope: CoroutineScope,
    menuOpen: Boolean,
    onMenu: (Boolean) -> Unit,
) {
    Surface(color = Color.White) {
        Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.tab == Tab.CHAT) {
                    BrandMark(size = 38.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("KnowHub", fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = Ink)
                        Text(
                            when {
                                state.isDirect -> "直连模式 · 不含院内知识库"
                                state.isPatient -> "就医指南 · 挂号检查住院"
                                else -> "医院知识中枢 · 引原文为据"
                            },
                            fontSize = 11.sp,
                            color = Muted,
                        )
                    }
                    IconAction(
                        glyph = Glyph.Plus,
                        onClick = { state.createSession(scope) },
                        background = BrandSoft,
                        tint = Brand,
                    )
                } else {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            when {
                                state.tab != Tab.KNOWLEDGE -> "设置"
                                state.isPatient -> "就医指南"
                                else -> "知识库"
                            },
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 20.sp,
                            color = Ink,
                        )
                        Text(
                            when {
                                state.tab != Tab.KNOWLEDGE -> "账号、连接与检索配置"
                                state.isPatient -> "${state.documents.size} 篇指南 · 挂号 / 检查 / 住院 / 报销"
                                else -> "${state.documents.size} 篇制度 · ${state.documents.sumOf { it.chunkCount }} 个片段"
                            },
                            fontSize = 11.sp,
                            color = Muted,
                        )
                    }
                    if (state.tab == Tab.KNOWLEDGE && state.isAdmin) {
                        IconAction(
                            glyph = Glyph.Plus,
                            onClick = {
                                state.newDocName = ""
                                state.newDocContent = ""
                                state.newDocAcl = ""
                                state.docError = null
                                state.showCreateDocDialog = true
                            },
                            background = BrandSoft,
                            tint = Brand,
                        )
                    }
                    if (state.tab == Tab.KNOWLEDGE) {
                        IconAction(
                            glyph = Glyph.Refresh,
                            onClick = { state.loadKnowledge(scope) },
                        )
                    }
                }

                Box {
                    IconAction(glyph = Glyph.More, onClick = { onMenu(true) })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenu(false) }) {
                        DropdownMenuItem(
                            text = { Text("刷新会话") },
                            onClick = {
                                onMenu(false)
                                scope.launch { runCatching { state.refreshSessions() } }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("新建会话") },
                            onClick = {
                                onMenu(false)
                                state.tab = Tab.CHAT
                                state.createSession(scope)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("刷新文档") },
                            onClick = {
                                onMenu(false)
                                state.loadKnowledge(scope)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("退出登录") },
                            onClick = {
                                onMenu(false)
                                state.logout()
                            },
                        )
                    }
                }
            }
            DividerLine()
        }
    }
}

@Composable
private fun BottomNav(state: AppState) {
    Surface(color = Color.White) {
        Column {
            DividerLine()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val tabs = if (state.isDirect) listOf(Tab.CHAT, Tab.SETTINGS) else Tab.entries
                tabs.forEach { tab ->
                    val selected = state.tab == tab
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { state.tab = tab }
                            .padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (selected) BrandSoft else Color.Transparent)
                                .padding(horizontal = 18.dp, vertical = 4.dp),
                        ) {
                            GlyphIcon(
                                glyph = when (tab) {
                                    Tab.CHAT -> Glyph.Chat
                                    Tab.KNOWLEDGE -> Glyph.Book
                                    Tab.SETTINGS -> Glyph.Sliders
                                },
                                size = 19.dp,
                                tint = if (selected) Brand else Faint,
                            )
                        }
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = when (tab) {
                                Tab.CHAT -> "对话"
                                Tab.KNOWLEDGE -> if (state.isPatient) "指南" else "知识库"
                                Tab.SETTINGS -> "设置"
                            },
                            fontSize = 10.5.sp,
                            color = if (selected) Brand else Muted,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}
