package com.example.knowhub.core.util

import kotlin.random.Random

private const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

fun newId(prefix: String, length: Int = 12): String {
    val sb = StringBuilder(prefix.length + 1 + length)
    sb.append(prefix).append('_')
    repeat(length) { sb.append(ALPHABET[Random.nextInt(ALPHABET.length)]) }
    return sb.toString()
}

fun shortHash(text: String): String {
    var h = 1125899906842597L
    for (ch in text) {
        h = 31 * h + ch.code
    }
    return h.toULong().toString(16)
}
