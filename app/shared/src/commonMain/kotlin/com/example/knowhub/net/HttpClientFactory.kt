package com.example.knowhub.net

import com.example.knowhub.core.AppJson
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/** 各平台注入具体引擎（Android/JVM=OkHttp，iOS=Darwin，Web=Js）。 */
expect fun createPlatformHttpClient(): HttpClient

fun HttpClientConfig<*>.installKnowHubDefaults() {
    expectSuccess = false
    install(ContentNegotiation) {
        json(AppJson)
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 10_000
        requestTimeoutMillis = 120_000
        socketTimeoutMillis = 120_000
    }
}
