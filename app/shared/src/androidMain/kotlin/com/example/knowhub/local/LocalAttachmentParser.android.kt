package com.example.knowhub.local

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

actual fun parseLocalAttachmentText(name: String, mime: String, bytes: ByteArray): String {
    if (isPdf(name, mime)) {
        val text = runCatching {
            PDDocument.load(bytes).use { document -> PDFTextStripper().getText(document) }
        }.getOrDefault("")
        if (text.isNotBlank()) return text.trim()
    }
    if (mime.startsWith("image/")) return ""
    return decodeAttachmentText(bytes)
}
