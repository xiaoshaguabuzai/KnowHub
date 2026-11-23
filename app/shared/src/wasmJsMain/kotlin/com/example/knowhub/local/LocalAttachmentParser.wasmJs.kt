package com.example.knowhub.local

actual fun parseLocalAttachmentText(name: String, mime: String, bytes: ByteArray): String {
    if (mime.startsWith("image/")) return ""
    return decodeAttachmentText(bytes)
}