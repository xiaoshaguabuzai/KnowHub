package com.example.knowhub.core.text

/**
 * 面向中文场景的轻量文本处理：
 * - 归一化（全角空格、大小写、换行）
 * - 分词：CJK 单字 + 相邻二元组，拉丁字母/数字按词切分
 * - 句子切分、token 估算、截断
 *
 * 不依赖任何第三方分词器，保证多端（Android/iOS/Web/服务端）行为一致。
 */
object TextProcessor {

    private val stopWords = setOf(
        "的", "了", "和", "是", "在", "我", "有", "就", "不", "人", "都", "一", "上", "也", "很",
        "到", "说", "要", "去", "你", "会", "着", "看", "好", "这", "那", "吗", "呢", "吧", "啊",
        "与", "及", "或", "对", "为", "被", "把", "给", "从", "向", "中", "内", "外", "后", "前",
        "个", "些", "等", "其", "而", "但", "并", "让", "使", "之", "与", "于", "以", "此",
    )

    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            when {
                ch == '\u3000' || ch == '\t' -> sb.append(' ')
                ch == '\r' -> Unit
                else -> sb.append(ch.lowercaseChar())
            }
        }
        return sb.toString().replace(Regex("[ \u00A0]+"), " ")
    }

    fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF
    }

    fun tokens(text: String, keepStopWords: Boolean = false): List<String> {
        val out = ArrayList<String>(text.length)
        val word = StringBuilder()
        var prevCjk: Char? = null

        fun flushWord() {
            if (word.isNotEmpty()) {
                out.add(word.toString())
                word.setLength(0)
            }
        }

        for (raw in normalize(text)) {
            val ch = raw
            if (isCjk(ch)) {
                flushWord()
                out.add(ch.toString())
                prevCjk?.let { out.add(it.toString() + ch) }
                prevCjk = ch
            } else if (ch.isLetterOrDigit()) {
                prevCjk = null
                word.append(ch)
            } else {
                flushWord()
                prevCjk = null
            }
        }
        flushWord()
        return if (keepStopWords) out else out.filter { it.length > 1 || it !in stopWords }
    }

    fun sentences(text: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in text) {
            sb.append(ch)
            if (ch == '。' || ch == '！' || ch == '？' || ch == '!' || ch == '?' || ch == '；' || ch == ';' || ch == '\n') {
                val s = sb.toString().trim().trimEnd('。', '！', '？', '!', '?', '；', ';')
                if (s.isNotBlank()) out.add(s)
                sb.setLength(0)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotBlank()) out.add(tail)
        return out
    }

    /** 粗略 token 估算：CJK 每字 1 token，拉丁词/数字串按 1 token 计。 */
    fun estimateTokens(text: String): Int {
        var count = 0
        var inWord = false
        for (ch in text) {
            if (isCjk(ch)) {
                count++
                inWord = false
            } else if (ch.isLetterOrDigit()) {
                if (!inWord) {
                    count++
                    inWord = true
                }
            } else {
                inWord = false
            }
        }
        return count
    }

    fun truncateTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        if (estimateTokens(text) <= maxTokens) return text
        val sb = StringBuilder()
        var count = 0
        var inWord = false
        for (ch in text) {
            if (isCjk(ch)) {
                count++
                inWord = false
            } else if (ch.isLetterOrDigit()) {
                if (!inWord) {
                    count++
                    inWord = true
                }
            } else {
                inWord = false
            }
            if (count > maxTokens) break
            sb.append(ch)
        }
        return sb.toString().trimEnd() + "…"
    }

    /** 去掉 Markdown 标记，避免标题符号、列表符号进入引用片段。 */
    fun cleanMarkdown(text: String): String {
        var t = text
        t = t.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s*"), "")
        t = t.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")
        t = t.replace("**", "").replace("__", "").replace("`", "")
        t = t.replace(Regex("[ \\t]+"), " ")
        return t.trim()
    }

    /** 词集合 Jaccard 相似度，用于调教样本匹配与去重。 */
    fun similarity(a: String, b: String): Double {
        val ta = tokens(a).toSet()
        val tb = tokens(b).toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.count { it in tb }
        val union = ta.size + tb.size - inter
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    /**
     * 抽取与问题最相关的若干句（保持原文顺序），用于引用卡片与检索式回答。
     * 多句组合能覆盖"护理分级按小时巡视"这类分档条款，避免只摘到其中一档。
     */
    fun bestSnippet(chunkText: String, query: String, maxChars: Int = 240, maxSentences: Int = 3): String {
        val q = tokens(query).toSet()
        val sentences = sentences(chunkText).filter { it.length >= 8 }
        if (sentences.isEmpty()) return truncateChars(chunkText, maxChars)
        if (q.isEmpty()) return truncateChars(sentences.take(maxSentences).joinToString("。") + "。", maxChars)

        val ranked = sentences.mapIndexed { index, sentence ->
            val st = tokens(sentence).toSet()
            Triple(index, sentence, st.count { it in q })
        }.filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenByDescending { it.second.length })

        if (ranked.isEmpty()) {
            return truncateChars(sentences.maxByOrNull { it.length } ?: chunkText, maxChars)
        }
        val chosen = ranked.take(maxSentences).sortedBy { it.first }.joinToString("。") { it.second } + "。"
        return truncateChars(chosen, maxChars)
    }

    fun truncateChars(text: String, maxChars: Int): String {
        val t = text.trim()
        return if (t.length <= maxChars) t else t.substring(0, maxChars).trimEnd() + "…"
    }
}
