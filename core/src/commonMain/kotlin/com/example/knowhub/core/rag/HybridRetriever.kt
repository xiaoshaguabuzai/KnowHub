package com.example.knowhub.core.rag

import com.example.knowhub.core.model.Chunk
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.TuneSample
import com.example.knowhub.core.model.UserDto
import com.example.knowhub.core.security.Acl
import com.example.knowhub.core.text.TextProcessor

data class ScoredChunk(
    val chunk: Chunk,
    val bm25Score: Double,
    val vectorScore: Double,
    val score: Double,
    val snippet: String,
)

/**
 * 混合检索：BM25 稀疏 + 哈希向量稠密，加权融合后按 ACL 过滤。
 * 调教样本对同一 chunk 做加减权，形成"可调教"闭环。
 */
class HybridRetriever(
    chunks: List<Chunk>,
    private val documents: Map<String, DocumentDto>,
    private val rag: RagConfig,
    private val tuneSamples: List<TuneSample> = emptyList(),
    private val embedder: Embedder = HashingEmbedder(),
) {
    private val chunkList: List<Chunk> = chunks
    private val tokenized: List<List<String>> = chunks.map { TextProcessor.tokens(it.text) }
    private val bm25 = Bm25Index(tokenized)
    private val vectors: List<FloatArray> =
        if (chunks.size <= MAX_VECTOR_CHUNKS) chunks.map { embedder.embed(it.text) } else emptyList()

    fun retrieve(query: String, user: UserDto, limit: Int = rag.topK): List<ScoredChunk> {
        if (chunkList.isEmpty()) return emptyList()
        val queryTokens = TextProcessor.tokens(query)
        if (queryTokens.isEmpty()) return emptyList()

        val bm25Raw = bm25.scores(queryTokens)
        val maxBm25 = bm25Raw.maxOrNull() ?: 0.0
        val queryVector = if (vectors.isEmpty()) null else embedder.embed(query)

        val scored = ArrayList<ScoredChunk>(chunkList.size)
        for (index in chunkList.indices) {
            val chunk = chunkList[index]
            val doc = documents[chunk.docId]
            if (!Acl.canRead(doc, user)) continue

            val sparse = if (maxBm25 > 0.0) bm25Raw[index] / maxBm25 else 0.0
            val dense = if (queryVector == null) 0.0 else (embedder.cosine(queryVector, vectors[index]) + 1.0) / 2.0

            var score = SPARSE_WEIGHT * sparse + DENSE_WEIGHT * dense
            if (rag.tuningEnabled) score += tuneBoost(query, chunk.chunkId)
            if (score < rag.minScore) continue

            scored.add(
                ScoredChunk(
                    chunk = chunk,
                    bm25Score = sparse,
                    vectorScore = dense,
                    score = score,
                    snippet = TextProcessor.bestSnippet(chunk.text, query),
                )
            )
        }
        scored.sortByDescending { it.score }
        return if (scored.size > limit) scored.subList(0, limit).toList() else scored
    }

    private fun tuneBoost(query: String, chunkId: String): Double {
        if (tuneSamples.isEmpty()) return 0.0
        var delta = 0.0
        for (sample in tuneSamples) {
            if (sample.chunkId != chunkId) continue
            if (TextProcessor.similarity(query, sample.query) < TUNE_MATCH_THRESHOLD) continue
            delta += if (sample.positive) POSITIVE_BOOST else NEGATIVE_BOOST
        }
        return delta
    }

    companion object {
        private const val SPARSE_WEIGHT = 0.55
        private const val DENSE_WEIGHT = 0.45
        private const val POSITIVE_BOOST = 0.25
        private const val NEGATIVE_BOOST = -0.35
        private const val TUNE_MATCH_THRESHOLD = 0.45
        private const val MAX_VECTOR_CHUNKS = 20_000
    }
}
