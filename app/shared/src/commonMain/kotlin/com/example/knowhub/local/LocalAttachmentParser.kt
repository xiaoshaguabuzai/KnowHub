package com.example.knowhub.local

/**
 * 直连模式的本地附件解析：文本类按 UTF-8 读取；图片交给端侧 OCR；
 * PDF 由平台实现抽取文本（Android: pdfbox-android，JVM: PDFBox）。
 */
expect fun parseLocalAttachmentText(name: String, mime: String, bytes: ByteArray): String

internal fun decodeAttachmentText(bytes: ByteArray): String =
    bytes.decodeToString().removePrefix("\uFEFF").replace("\u0000", "").trim()

internal fun isPdf(name: String, mime: String): Boolean =
    mime.equals("application/pdf", ignoreCase = true) || name.lowercase().endsWith(".pdf")
