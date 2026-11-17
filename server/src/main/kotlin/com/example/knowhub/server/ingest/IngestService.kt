package com.example.knowhub.server.ingest

import com.example.knowhub.core.model.Chunk
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.DocumentStatus
import com.example.knowhub.core.text.Chunker
import com.example.knowhub.server.store.DataStore
import com.example.knowhub.server.store.DocumentStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * 文档入库：解析 → 落盘原文 → 切块 → 更新元数据。
 * 解析在线程池异步执行，接口立即返回 PARSING，客户端轮询文档列表即可看到 INDEXED/FAILED。
 */
class IngestService(
    private val store: DataStore,
    private val documents: DocumentStorage,
    private val scope: CoroutineScope,
) {
    private val log = LoggerFactory.getLogger(IngestService::class.java)

    fun submitFile(doc: DocumentDto, fileName: String, bytes: ByteArray) {
        scope.launch {
            try {
                val parsed = DocumentParser.parse(fileName, bytes)
                documents.save(doc.docId, joinPages(parsed))
                applyChunks(doc, parsed)
            } catch (e: Exception) {
                log.warn("文档解析失败 docId={} name={}", doc.docId, fileName, e)
                markFailed(doc.docId, e.message ?: "解析失败")
            }
        }
    }

    fun submitText(doc: DocumentDto, text: String) {
        scope.launch {
            try {
                documents.save(doc.docId, text)
                applyChunks(doc, ParsedDocument(listOf(PageText(1, text)), doc.mime))
            } catch (e: Exception) {
                log.warn("文本入库失败 docId={}", doc.docId, e)
                markFailed(doc.docId, e.message ?: "入库失败")
            }
        }
    }

    fun reindex(doc: DocumentDto) {
        scope.launch {
            val text = documents.read(doc.docId)
            if (text.isNullOrBlank()) {
                markFailed(doc.docId, "原始文本不存在，无法重建索引，请重新上传")
                return@launch
            }
            try {
                applyChunks(doc, ParsedDocument(listOf(PageText(1, text)), doc.mime), bumpVersion = true)
            } catch (e: Exception) {
                markFailed(doc.docId, e.message ?: "重建索引失败")
            }
        }
    }

    private fun applyChunks(doc: DocumentDto, parsed: ParsedDocument, bumpVersion: Boolean = false) {
        val now = System.currentTimeMillis()
        val db = store.snapshot()
        val rag = db.rag
        val chunks: List<Chunk> = parsed.pages.flatMap { page ->
            Chunker.chunk(
                text = page.text,
                docId = doc.docId,
                docName = doc.name,
                config = rag,
                page = page.page,
            )
        }
        store.mutate { current ->
            val updatedDocs = current.documents.map { existing ->
                if (existing.docId != doc.docId) existing
                else existing.copy(
                    status = DocumentStatus.INDEXED,
                    pageCount = parsed.pages.size,
                    chunkCount = chunks.size,
                    updatedAt = now,
                    version = if (bumpVersion) existing.version + 1 else existing.version,
                    error = null,
                )
            }
            current.copy(
                documents = updatedDocs,
                chunks = current.chunks.filterNot { it.docId == doc.docId } + chunks,
            ) to Unit
        }
        log.info("文档入库完成 docId={} pages={} chunks={}", doc.docId, parsed.pages.size, chunks.size)
    }

    private fun markFailed(docId: String, message: String) {
        store.mutate { current ->
            current.copy(
                documents = current.documents.map {
                    if (it.docId == docId) it.copy(status = DocumentStatus.FAILED, error = message, updatedAt = System.currentTimeMillis())
                    else it
                },
            ) to Unit
        }
    }

    /** 原文按页保存，阅读器可据「── 第 N 页 ──」渲染分页。 */
    private fun joinPages(parsed: ParsedDocument): String =
        parsed.pages.joinToString("\n\n") { page -> "── 第 ${page.page} 页 ──\n\n${page.text}" }
}
