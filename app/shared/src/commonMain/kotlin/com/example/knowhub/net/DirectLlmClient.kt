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
 * 直连模式：客户端直接用 API Key 调 OpenAI 兼容的 /chat/completions（SSE 流式）。
 * 不经过 Ktor 中台，因此没有院内知识库、流程与附件的服务端能力。
 */
class DirectLlmClient(
    private val http: HttpClient,
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
) {
    fun stream(history: List<Pair<String, String>>): Flow<String> = flow {
        require(apiKey.isNotBlank()) { "API Key 不能为空" }
        val endpoint = baseUrl.trim().trimEnd('/') + "/chat/completions"
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
}
