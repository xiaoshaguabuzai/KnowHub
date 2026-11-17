package com.example.knowhub.server.ingest

import com.example.knowhub.core.model.AttachmentDto
import com.example.knowhub.core.util.newId
import com.example.knowhub.server.store.AttachmentRecord
import com.example.knowhub.server.store.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 会话附件：解析文件文本（PDF / 文本），图片仅保存元信息。
 * 文本落盘 data/attachments/{id}.txt，聊天时按 id 读取并注入提示词。
 */
class AttachmentService(
    private val store: DataStore,
    dataDir: File,
) {
    private val dir = File(dataDir, "attachments").apply { mkdirs() }

    suspend fun register(
        ownerId: String,
        fileName: String,
        mime: String,
        bytes: ByteArray,
    ): AttachmentDto = withContext(Dispatchers.IO) {
        val text = extractText(fileName, mime, bytes)
        val id = newId("att")
        if (text.isNotBlank()) {
            File(dir, "$id.txt").writeText(text, Charsets.UTF_8)
        }
        val record = AttachmentRecord(
            attachmentId = id,
            ownerId = ownerId,
            name = fileName,
            mime = mime,
            sizeBytes = bytes.size.toLong(),
            chars = text.length,
            hasText = text.isNotBlank(),
            preview = text.take(160),
            createdAt = System.currentTimeMillis(),
        )
        store.mutate { db -> db.copy(attachments = (db.attachments + record).takeLast(500)) to Unit }
        record.toDto()
    }

    fun load(ownerId: String, ids: List<String>): List<Pair<AttachmentRecord, String>> {
        if (ids.isEmpty()) return emptyList()
        val db = store.snapshot()
        return ids.distinct().mapNotNull { id ->
            val record = db.attachmentById(id) ?: return@mapNotNull null
            if (record.ownerId != ownerId) return@mapNotNull null
            record to readText(id).orEmpty()
        }
    }

    fun readText(id: String): String? {
        val file = File(dir, "$id.txt")
        return if (file.exists()) file.readText(Charsets.UTF_8) else null
    }

    private fun extractText(fileName: String, mime: String, bytes: ByteArray): String {
        val lower = fileName.lowercase()
        return when {
            mime.startsWith("image/") -> ""
            lower.endsWith(".pdf") -> runCatching {
                DocumentParser.parse(fileName, bytes).pages
                    .joinToString("\n\n") { "── 第 ${it.page} 页 ──\n\n${it.text}" }
            }.getOrDefault("")
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") ||
                lower.endsWith(".json") || lower.endsWith(".log") || mime.startsWith("text/") ->
                String(bytes, Charsets.UTF_8).removePrefix("\uFEFF").replace("\u0000", "").trim()
            else -> runCatching {
                DocumentParser.parse(fileName, bytes).pages.joinToString("\n\n") { it.text }
            }.getOrDefault("")
        }
    }
}
