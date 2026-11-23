package com.example.knowhub.local

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

actual fun parseLocalAttachmentText(name: String, mime: String, bytes: ByteArray): String {
    if (isPdf(name, mime)) {
        val text = runCatching {
            Loader.loadPDF(bytes).use { document -> PDFTextStripper().getText(document) }
        }.getOrDefault("")
        if (text.isNotBlank()) return text.trim()
    }
    if (mime.startsWith("image/")) return ""
    return decodeAttachmentText(bytes)
}
