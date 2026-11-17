package com.example.knowhub.server.ingest

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.nio.charset.Charset

data class ParsedDocument(
    val pages: List<PageText>,
    val mime: String,
)

data class PageText(val page: Int, val text: String)

class UnsupportedDocumentException(message: String) : RuntimeException(message)

/**
 * 文档解析：PDF 按页解析（保证溯源页码准确），纯文本按单页处理。
 * 解析失败会抛出可读异常，由入库服务落为 FAILED 状态。
 */
object DocumentParser {

    private val utf8 = Charsets.UTF_8
    private val fallbackCharsets = listOf(Charset.forName("GB18030"), Charset.forName("UTF-16"))

    fun parse(fileName: String, bytes: ByteArray): ParsedDocument {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".pdf") -> parsePdf(bytes)
            lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".csv") ||
                lower.endsWith(".json") || lower.endsWith(".log") ->
                ParsedDocument(listOf(PageText(1, decodeText(bytes))), mimeOf(lower))
            else -> throw UnsupportedDocumentException("暂不支持该格式：$fileName（当前支持 PDF / TXT / MD / CSV / JSON）")
        }
    }

    private fun parsePdf(bytes: ByteArray): ParsedDocument {
        Loader.loadPDF(bytes).use { document ->
            val pages = ArrayList<PageText>()
            for (page in 1..document.numberOfPages) {
                val stripper = PDFTextStripper().apply {
                    startPage = page
                    endPage = page
                }
                val text = stripper.getText(document).replace("\u0000", "").trim()
                if (text.isNotEmpty()) pages.add(PageText(page, text))
            }
            if (pages.isEmpty()) {
                throw UnsupportedDocumentException("PDF 未解析出文本，可能是扫描件（MVP 暂未接入 OCR）")
            }
            return ParsedDocument(pages, "application/pdf")
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        var text = String(bytes, utf8)
        val replacementRatio = text.count { it == '\uFFFD' }.toDouble() / maxOf(1, text.length)
        if (replacementRatio > 0.01) {
            for (charset in fallbackCharsets) {
                val candidate = runCatching { String(bytes, charset) }.getOrNull() ?: continue
                if (candidate.count { it == '\uFFFD' }.toDouble() / maxOf(1, candidate.length) < replacementRatio) {
                    text = candidate
                    break
                }
            }
        }
        return text.removePrefix("\uFEFF").replace("\u0000", "").trim()
    }

    private fun mimeOf(lowerName: String): String = when {
        lowerName.endsWith(".pdf") -> "application/pdf"
        lowerName.endsWith(".md") -> "text/markdown"
        lowerName.endsWith(".csv") -> "text/csv"
        lowerName.endsWith(".json") -> "application/json"
        else -> "text/plain"
    }
}
