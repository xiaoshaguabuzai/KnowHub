package com.example.knowhub.server

import com.example.knowhub.server.audit.AuditService
import com.example.knowhub.server.chat.ChatService
import com.example.knowhub.server.config.AppConfig
import com.example.knowhub.server.ingest.AttachmentService
import com.example.knowhub.server.ingest.IngestService
import com.example.knowhub.server.llm.LlmGateway
import com.example.knowhub.server.llm.OpenAiCompatibleLlm
import com.example.knowhub.server.rag.IndexService
import com.example.knowhub.server.security.AuthContext
import com.example.knowhub.server.security.AuthService
import com.example.knowhub.server.seed.SeedData
import com.example.knowhub.server.store.DataStore
import com.example.knowhub.server.store.DocumentStorage
import com.example.knowhub.server.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 依赖装配：所有单例在此创建，路由只依赖容器。
 */
class AppContainer(val config: AppConfig) {

    val store = DataStore(config.dataDirectory())
    val documentStorage = DocumentStorage(config.dataDirectory())
    val auth = AuthService(config, store)
    val authContext = AuthContext(auth, store)
    val audit = AuditService(store)
    val tools = ToolRegistry()
    val index = IndexService(store)
    val llm: LlmGateway = OpenAiCompatibleLlm.create(config)

    private val ingestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val ingest = IngestService(store, documentStorage, ingestScope)
    val attachments = AttachmentService(store, config.dataDirectory())
    val chat = ChatService(store, index, tools, llm, audit, attachments)

    init {
        SeedData.seedIfEmpty(store, auth, documentStorage)
        backfillDocumentTexts()
    }

    /** 老数据（或种子）缺少原文文件时，用已切块的片段重建一份可读文本，保证「查看文件」可用。 */
    private fun backfillDocumentTexts() {
        val db = store.snapshot()
        for (doc in db.documents) {
            if (documentStorage.read(doc.docId) != null) continue
            val content = db.chunks
                .filter { it.docId == doc.docId }
                .sortedWith(compareBy({ it.page }, { it.index }))
                .joinToString("\n\n") { it.text }
            if (content.isNotBlank()) documentStorage.save(doc.docId, content)
        }
    }

    fun close() {
        ingestScope.cancel()
    }
}
