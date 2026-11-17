package com.example.knowhub.core.rag

import com.example.knowhub.core.text.TextProcessor
import kotlin.math.sqrt

/**
 * 无模型依赖的确定性向量化（hashed bag-of-features）。
 *
 * 设计取舍：MVP 阶段不引入 BGE/Milvus，先用哈希向量保证"召回链路可跑通、
 * 可测试、可离线"，接口保持 [Embedder] 抽象，后续可无缝替换为远程
 * Embedding 服务（BGE-large-zh 等）而不改调用方。
 */
interface Embedder {
    val dimensions: Int
    fun embed(text: String): FloatArray
    fun cosine(a: FloatArray, b: FloatArray): Double
}

class HashingEmbedder(override val dimensions: Int = 384) : Embedder {

    override fun embed(text: String): FloatArray {
        val vector = FloatArray(dimensions)
        val tokens = TextProcessor.tokens(text)
        for (token in tokens) {
            val hash = token.hashCode()
            val index = ((hash % dimensions) + dimensions) % dimensions
            val sign = if ((hash ushr 15) and 1 == 0) 1f else -1f
            vector[index] += sign
        }
        // 让向量单位化，余弦相似度等价于点积
        var norm = 0.0
        for (v in vector) norm += v.toDouble() * v
        if (norm > 0.0) {
            val inv = (1.0 / sqrt(norm)).toFloat()
            for (i in vector.indices) vector[i] *= inv
        }
        return vector
    }

    override fun cosine(a: FloatArray, b: FloatArray): Double {
        if (a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
        var dot = 0.0
        for (i in a.indices) dot += a[i].toDouble() * b[i]
        return dot.coerceIn(-1.0, 1.0)
    }
}
