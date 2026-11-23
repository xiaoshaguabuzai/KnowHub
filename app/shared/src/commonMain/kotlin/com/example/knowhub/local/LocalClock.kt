package com.example.knowhub.local

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 跨平台时间戳（Android/JVM/iOS/JS/Wasm 共用）。 */
@OptIn(ExperimentalTime::class)
internal fun localNow(): Long = Clock.System.now().toEpochMilliseconds()
