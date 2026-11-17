package com.example.knowhub.core.rag

import kotlin.math.ln

/**
 * BM25（k1=1.2, b=0.75）稀疏检索，针对中文分词结果构建倒排。
 */
class Bm25Index(
    private val docs: List<List<String>>,
    private val k1: Double = 1.2,
    private val b: Double = 0.75,
) {
    private val docFreq = HashMap<String, Int>()
    private val postings = HashMap<String, ArrayList<IntArray>>()
    private val docLengths = IntArray(docs.size)
    private val avgLength: Double

    init {
        var total = 0
        for (i in docs.indices) {
            val tokens = docs[i]
            docLengths[i] = tokens.size
            total += tokens.size
            val counts = HashMap<String, Int>()
            for (t in tokens) counts[t] = (counts[t] ?: 0) + 1
            for ((term, tf) in counts) {
                docFreq[term] = (docFreq[term] ?: 0) + 1
                postings.getOrPut(term) { ArrayList() }.add(intArrayOf(i, tf))
            }
        }
        avgLength = if (docs.isEmpty()) 0.0 else total.toDouble() / docs.size
    }

    fun scores(queryTokens: List<String>): DoubleArray {
        val result = DoubleArray(docs.size)
        if (docs.isEmpty() || avgLength == 0.0) return result
        val n = docs.size.toDouble()
        for (term in queryTokens.distinct()) {
            val df = docFreq[term] ?: continue
            val idf = ln(1.0 + (n - df + 0.5) / (df + 0.5))
            val list = postings[term] ?: continue
            for (entry in list) {
                val docIndex = entry[0]
                val tf = entry[1].toDouble()
                val dl = docLengths[docIndex].toDouble()
                val denom = tf + k1 * (1 - b + b * dl / avgLength)
                result[docIndex] += idf * (tf * (k1 + 1)) / denom
            }
        }
        return result
    }
}
