package com.example.knowhub.net

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * 直连模式模型客户端，支持两种协议：
 * - OpenAI 兼容：`{base}/chat/completions`（OpenAI、DeepSeek /v1、Qwen、Kimi 等）
 * - Anthropic 兼容：`{base}/v1/messages`（Claude、DeepSeek /anthropic）
 *
 * 通过接口地址自动识别：包含 `/anthropic` 即走 Anthropic Messages 协议。
 */
class DirectLlmClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) {
    private val anthropic: Boolean = baseUrl.contains("/anthropic", ignoreCase = true)

    fun stream(history: List<Pair<String, String>>): Flow<String> = flow {
        if (anthropic) streamAnthropic(history) else streamOpenAi(history)
    }

    private suspend fun FlowCollector<String>.streamOpenAi(history: List<Pair<String, String>>) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        val base = baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val payload = buildJsonObject {
            put("model", model)
            put("stream", true)
            putJsonArray("messages") {
                history.forEach { (role, content) ->
                    addJsonObject {
                        put("role", role)
                        put("content", content)
                    }
                }
            }
        }

        http.preparePost(endpoint) {
            header(HttpHeaders.Authorization, "Bearer ${apiKey.trim()}")
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody(payload.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val detail = runCatching { response.bodyAsText() }.getOrDefault("")
                throw IllegalStateException("直连模型失败（HTTP ${response.status.value}）：${detail.take(240)}")
            }
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = runCatching {
                    Json.parseToJsonElement(data).jsonObject["choices"]
                        ?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("delta")
                        ?.jsonObject?.get("content")
                        ?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (!delta.isNullOrEmpty()) emit(delta)
            }
        }
    }

    private suspend fun FlowCollector<String>.streamAnthropic(history: List<Pair<String, String>>) {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        val base = baseUrl.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/v1/messages")) base else "$base/v1/messages"
        val system = history.firstOrNull { it.first == "system" }?.second
        val messages = history.filter { it.first == "user" || it.first == "assistant" }

        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", 4096)
            put("stream", true)
            if (!system.isNullOrBlank()) put("system", system)
            putJsonArray("messages") {
                messages.forEach { (role, content) ->
                    addJsonObject {
                        put("role", role)
                        put("content", content)
                    }
                }
            }
        }

        http.preparePost(endpoint) {
            header("x-api-key", apiKey.trim())
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            accept(ContentType.Text.EventStream)
            setBody(payload.toString())
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val detail = runCatching { response.bodyAsText() }.getOrDefault("")
                throw IllegalStateException("直连模型失败（HTTP ${response.status.value}）：${detail.take(240)}")
            }
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val element = runCatching { Json.parseToJsonElement(data).jsonObject }.getOrNull() ?: continue
                when (element["type"]?.jsonPrimitive?.contentOrNull) {
                    "message_stop" -> break
                    "error" -> {
                        val message = element["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: "模型返回错误"
                        throw IllegalStateException("直连模型失败：$message")
                    }
                    "content_block_delta" -> {
                        val delta = element["delta"]?.jsonObject
                        if (delta?.get("type")?.jsonPrimitive?.contentOrNull == "text_delta") {
                            delta["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { emit(it) }
                        }
                    }
                }
            }
        }
    }
}
