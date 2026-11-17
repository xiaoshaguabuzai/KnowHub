package com.example.knowhub.server.rag

import com.example.knowhub.core.rag.HybridRetriever
import com.example.knowhub.server.store.DataStore

/**
 * 检索索引缓存：知识库（文档/片段/配置/调教样本）变化时才重建，
 * 避免每次问答都重新分词与向量化。
 */
class IndexService(private val store: DataStore) {

    private var cachedKey: String = ""
    private var cached: HybridRetriever? = null

    fun retriever(): HybridRetriever = synchronized(this) {
        val db = store.snapshot()
        val key = buildKey(db)
        val current = cached
        if (current == null || key != cachedKey) {
            cached = HybridRetriever(
                chunks = db.chunks,
                documents = db.documents.associateBy { it.docId },
                rag = db.rag,
                tuneSamples = db.tuneSamples,
            )
            cachedKey = key
        }
        cached!!
    }

    private fun buildKey(db: com.example.knowhub.server.store.Db): String = buildString {
        append(db.chunks.size).append('|')
        append(db.documents.joinToString(",") { "${it.docId}:${it.version}:${it.status}" }).append('|')
        append(db.rag.hashCode()).append('|')
        append(db.tuneSamples.size)
    }
}
