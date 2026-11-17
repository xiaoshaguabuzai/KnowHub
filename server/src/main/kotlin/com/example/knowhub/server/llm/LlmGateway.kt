package com.example.knowhub.server.llm

import com.example.knowhub.core.rag.ChatTurn
import com.example.knowhub.server.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class LlmResult(val promptTokens: Int = 0, val completionTokens: Int = 0)

interface LlmGateway {
    val name: String
    val remote: Boolean
    suspend fun streamChat(messages: List<ChatTurn>, onDelta: suspend (String) -> Unit): LlmResult
}

/** 未配置大模型时的本地实现：由 ChatService 走检索式回答，这里只提供元信息。 */
class LocalLlm : LlmGateway {
    override val name: String = "retrieval"
    override val remote: Boolean = false
    override suspend fun streamChat(messages: List<ChatTurn>, onDelta: suspend (String) -> Unit): LlmResult =
        LlmResult()
}

/**
 * OpenAI 兼容的流式大模型网关（DeepSeek / Qwen / vLLM 均可直接对接）。
 * 只做 SSE 行解析，不引入额外 SDK，便于后续替换。
 */
class OpenAiCompatibleLlm(private val config: AppConfig) : LlmGateway {

    override val name: String = "openai-compatible:${config.llmModel}"
    override val remote: Boolean = true

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override suspend fun streamChat(messages: List<ChatTurn>, onDelta: suspend (String) -> Unit): LlmResult =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("model", config.llmModel)
                put("stream", true)
                put("temperature", 0.2)
                put("messages", buildJsonArray {
                    for (turn in messages) {
                        add(buildJsonObject {
                            put("role", turn.role)
                            put("content", turn.content)
                        })
                    }
                })
            }.toString()

            val requestBuilder = HttpRequest.newBuilder(
                URI.create(config.llmBaseUrl!!.trimEnd('/') + "/chat/completions")
            )
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, Charsets.UTF_8))
            config.llmApiKey?.takeIf { it.isNotBlank() }?.let {
                requestBuilder.header("Authorization", "Bearer $it")
            }

            val response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofLines())
            if (response.statusCode() !in 200..299) {
                throw IllegalStateException("大模型接口返回 HTTP ${response.statusCode()}")
            }

            var completionChars = 0
            response.body().use { lines ->
                val iterator = lines.iterator()
                while (iterator.hasNext()) {
                    val line = iterator.next()
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = extractDelta(payload) ?: continue
                    if (delta.isEmpty()) continue
                    completionChars += delta.length
                    onDelta(delta)
                }
            }
            LlmResult(promptTokens = 0, completionTokens = completionChars / 2)
        }

    private fun extractDelta(payload: String): String? = runCatching {
        val root = com.example.knowhub.core.AppJson.parseToJsonElement(payload).jsonObject
        val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        val delta: JsonObject = choice["delta"]?.jsonObject ?: return null
        delta["content"]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    companion object {
        fun create(config: AppConfig): LlmGateway =
            if (config.llmEnabled) OpenAiCompatibleLlm(config) else LocalLlm()
    }
}
