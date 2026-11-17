package com.example.knowhub.core

import kotlinx.serialization.json.Json

/**
 * 全端统一 JSON 编解码配置：服务端、Android、桌面、Web 共用同一份契约。
 */
val AppJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
}
