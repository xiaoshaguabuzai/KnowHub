package com.example.knowhub.core.text

import com.example.knowhub.core.model.Chunk
import com.example.knowhub.core.model.RagConfig

/**
 * 结构化切块：先按空行/标题分块，再按句子聚合到目标 token 数，块间保留重叠。
 * 每块携带页码与章节，保证溯源能定位到原文。
 */
object Chunker {

    private val headingRegex = Regex("^(#{1,6}\\s+|[一二三四五六七八九十]+[、.]\\s*|第[一二三四五六七八九十百]+[章节条]\\s*)")

    fun chunk(
        text: String,
        docId: String,
        docName: String,
        config: RagConfig = RagConfig(),
        page: Int = 1,
    ): List<Chunk> {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        val blocks = normalized.split(Regex("\n\\s*\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val result = ArrayList<Chunk>()
        var section: String? = null
        val buffer = ArrayList<String>()
        var bufferTokens = 0
        var chunkIndex = 0

        fun flush(withOverlap: Boolean) {
            if (buffer.isEmpty()) return
            val content = buffer.joinToString("\n").trim()
            if (content.isNotEmpty()) {
                result.add(
                    Chunk(
                        chunkId = "$docId-p$page-$chunkIndex",
                        docId = docId,
                        docName = docName,
                        page = page,
                        section = section,
                        index = chunkIndex,
                        text = content,
                    )
                )
                chunkIndex++
            }
            if (withOverlap && config.overlapTokens > 0 && buffer.size > 1) {
                val tail = ArrayList<String>()
                var tailTokens = 0
                for (i in buffer.indices.reversed()) {
                    val t = TextProcessor.estimateTokens(buffer[i])
                    if (tailTokens + t > config.overlapTokens && tail.isNotEmpty()) break
                    tail.add(0, buffer[i])
                    tailTokens += t
                }
                buffer.clear()
                buffer.addAll(tail)
                bufferTokens = tailTokens
            } else {
                buffer.clear()
                bufferTokens = 0
            }
        }

        for (block in blocks) {
            val firstLine = block.lineSequence().firstOrNull()?.trim().orEmpty()
            val isHeading = headingRegex.containsMatchIn(firstLine) || (firstLine.length <= 24 && firstLine.endsWith("：") && !firstLine.contains("。"))
            var blockContent = block
            if (isHeading) {
                section = firstLine.trimStart('#', ' ').trim()
                // 纯标题块只作为章节标记；标题与正文同块时，去掉标题行，避免标题进入引用片段
                val blockLines = block.lineSequence().toList()
                if (blockLines.size <= 1) continue
                blockContent = blockLines.drop(1).joinToString("\n")
            }
            val cleanBlock = TextProcessor.cleanMarkdown(blockContent)
            val sentences = TextProcessor.sentences(cleanBlock)
            for (sentence in sentences) {
                val tokens = TextProcessor.estimateTokens(sentence)
                if (tokens > config.chunkSizeTokens) {
                    // 超长句：按字符硬切，避免单块过大
                    var rest = sentence
                    while (rest.isNotEmpty()) {
                        val piece = TextProcessor.truncateTokens(rest, config.chunkSizeTokens).removeSuffix("…")
                        buffer.add(piece)
                        bufferTokens += TextProcessor.estimateTokens(piece)
                        flush(withOverlap = true)
                        rest = rest.removePrefix(piece).trim()
                    }
                    continue
                }
                if (bufferTokens + tokens > config.chunkSizeTokens && buffer.isNotEmpty()) {
                    flush(withOverlap = true)
                }
                buffer.add(sentence)
                bufferTokens += tokens
            }
            // 段落之间视为自然边界，避免跨段粘连
            flush(withOverlap = false)
        }
        flush(withOverlap = false)
        return result
    }
}
