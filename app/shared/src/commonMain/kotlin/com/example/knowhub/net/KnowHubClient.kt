package com.example.knowhub.net

import com.example.knowhub.core.AppJson
import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.api.AppDefaults
import com.example.knowhub.core.model.ChatRequest
import com.example.knowhub.core.model.DocumentDetailDto
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.AttachmentDto
import com.example.knowhub.core.model.FeedbackRequest
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.HealthDto
import com.example.knowhub.core.model.LoginRequest
import com.example.knowhub.core.model.LoginResponse
import com.example.knowhub.core.model.MessageDto
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.SessionDto
import com.example.knowhub.core.model.StatsOverview
import com.example.knowhub.core.model.StreamEvent
import com.example.knowhub.core.model.TextDocumentRequest
import com.example.knowhub.core.model.ToolInfo
import com.example.knowhub.core.model.UserDto
import com.example.knowhub.core.sse.SseParser
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
) : RuntimeException(message)

/**
 * 客户端 API：登录态用 Bearer 令牌，聊天走 SSE（POST + text/event-stream）。
 * 所有路径常量来自 core 的 ApiRoutes，保证与服务端一致。
 */
class KnowHubClient {

    var baseUrl: String = AppDefaults.BASE_URL
        set(value) {
            field = value.trimEnd('/')
        }

    private val http: HttpClient = createPlatformHttpClient()
    private val json = AppJson

    var token: String? = null
        private set
    var user: UserDto? = null
        private set

    fun isLoggedIn(): Boolean = token != null

    fun clearSession() {
        token = null
        user = null
    }

    private fun url(path: String): String = baseUrl + path

    private fun authHeader(): String = "Bearer " + (token ?: throw ApiException(401, "UNAUTHORIZED", "请先登录"))

    suspend fun login(username: String, password: String): LoginResponse {
        val response = http.post(url(ApiRoutes.LOGIN)) {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(username.trim(), password))
        }
        val body = decodeOrThrow<LoginResponse>(response)
        token = body.token
        user = body.user
        return body
    }

    suspend fun health(): HealthDto = decodeOrThrow(http.get(url(ApiRoutes.HEALTH)))

    suspend fun sessions(): List<SessionDto> =
        decodeOrThrow(http.get(url(ApiRoutes.SESSIONS)) { header(HttpHeaders.Authorization, authHeader()) })

    suspend fun createSession(title: String? = null): SessionDto =
        decodeOrThrow(
            http.post(url(ApiRoutes.SESSIONS)) {
                header(HttpHeaders.Authorization, authHeader())
                contentType(ContentType.Application.Json)
                setBody(TitleRequest(title))
            }
        )

    suspend fun deleteSession(sessionId: String) {
        val response = http.delete(url(ApiRoutes.session(sessionId))) {
            header(HttpHeaders.Authorization, authHeader())
        }
        ensureSuccess(response)
    }

    suspend fun messages(sessionId: String): List<MessageDto> =
        decodeOrThrow(http.get(url(ApiRoutes.sessionMessages(sessionId))) {
            header(HttpHeaders.Authorization, authHeader())
        })

    suspend fun documents(): List<DocumentDto> =
        decodeOrThrow(http.get(url(ApiRoutes.DOCUMENTS)) { header(HttpHeaders.Authorization, authHeader()) })

    suspend fun documentDetail(docId: String): DocumentDetailDto =
        decodeOrThrow(http.get(url(ApiRoutes.document(docId))) { header(HttpHeaders.Authorization, authHeader()) })

    /** 直连模式：不经过中台，直接以 API Key 调用 OpenAI 兼容模型。 */
    fun streamDirect(
        apiKey: String,
        baseUrl: String,
        model: String,
        history: List<Pair<String, String>>,
    ): Flow<String> = DirectLlmClient(http, apiKey, baseUrl, model).stream(history)

    /** 上传会话附件（文件 / 照片），服务端抽取文本后返回元信息。 */
    suspend fun uploadAttachment(name: String, mime: String, bytes: ByteArray): AttachmentDto {
        val safeName = name.replace("\"", "")
        val response = http.post(url(ApiRoutes.ATTACHMENTS)) {
            header(HttpHeaders.Authorization, authHeader())
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, mime)
                                append(HttpHeaders.ContentDisposition, "filename=\"$safeName\"")
                            },
                        )
                    }
                )
            )
        }
        return decodeOrThrow(response)
    }

    suspend fun uploadTextDocument(name: String, content: String, aclRoles: List<String> = emptyList()): DocumentDto =
        decodeOrThrow(
            http.post(url(ApiRoutes.DOCUMENTS_TEXT)) {
                header(HttpHeaders.Authorization, authHeader())
                contentType(ContentType.Application.Json)
                setBody(TextDocumentRequest(name = name, content = content, aclRoles = aclRoles))
            }
        )

    suspend fun deleteDocument(docId: String) {
        val response = http.delete(url(ApiRoutes.document(docId))) {
            header(HttpHeaders.Authorization, authHeader())
        }
        ensureSuccess(response)
    }

    suspend fun reindexDocument(docId: String) {
        val response = http.post(url(ApiRoutes.documentReindex(docId))) {
            header(HttpHeaders.Authorization, authHeader())
        }
        ensureSuccess(response)
    }

    suspend fun flows(): List<FlowTemplate> =
        decodeOrThrow(http.get(url(ApiRoutes.FLOWS)) { header(HttpHeaders.Authorization, authHeader()) })

    suspend fun tools(): List<ToolInfo> =
        decodeOrThrow(http.get(url(ApiRoutes.TOOLS)) { header(HttpHeaders.Authorization, authHeader()) })

    suspend fun ragConfig(): RagConfig =
        decodeOrThrow(http.get(url(ApiRoutes.RAG_CONFIG)) { header(HttpHeaders.Authorization, authHeader()) })

    suspend fun updateRagConfig(config: RagConfig): RagConfig =
        decodeOrThrow(
            http.put(url(ApiRoutes.RAG_CONFIG)) {
                header(HttpHeaders.Authorization, authHeader())
                contentType(ContentType.Application.Json)
                setBody(config)
            }
        )

    suspend fun stats(): StatsOverview =
        decodeOrThrow(http.get(url(ApiRoutes.STATS)) { header(HttpHeaders.Authorization, authHeader()) })

    suspend fun feedback(messageId: String, rating: String, chunkId: String? = null) {
        val response = http.post(url(ApiRoutes.FEEDBACK)) {
            header(HttpHeaders.Authorization, authHeader())
            contentType(ContentType.Application.Json)
            setBody(FeedbackRequest(messageId = messageId, rating = rating, chunkId = chunkId))
        }
        ensureSuccess(response)
    }

    /**
     * SSE 流式问答。每收到一个完整帧就 emit 一个 [StreamEvent]，
     * 调用方可据此渲染增量文本、来源与流程步骤。
     */
    fun streamChat(
        sessionId: String,
        message: String,
        attachmentIds: List<String> = emptyList(),
    ): Flow<StreamEvent> = flow {
        val statement = http.preparePost(url(ApiRoutes.CHAT_STREAM)) {
            header(HttpHeaders.Authorization, authHeader())
            header(HttpHeaders.Accept, ContentType.Text.EventStream.toString())
            contentType(ContentType.Application.Json)
            setBody(
                ChatRequest(
                    sessionId = sessionId,
                    message = message,
                    attachmentIds = attachmentIds,
                )
            )
        }
        statement.execute { response ->
            if (!response.status.isSuccess()) {
                val text = response.bodyAsText()
                throw parseError(response.status.value, text)
            }
            val parser = SseParser()
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                parser.feed(line.trimEnd('\r'))?.let { event -> emit(event) }
            }
        }
    }

    private suspend inline fun <reified T> decodeOrThrow(response: HttpResponse): T {
        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            throw parseError(response.status.value, text)
        }
        return response.body()
    }

    private suspend fun ensureSuccess(response: HttpResponse) {
        if (!response.status.isSuccess()) {
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            throw parseError(response.status.value, text)
        }
    }

    private fun parseError(status: Int, body: String): ApiException {
        val info = runCatching {
            json.decodeFromString(com.example.knowhub.core.model.ApiErrorInfo.serializer(), body)
        }.getOrNull()
        return ApiException(status, info?.code ?: "HTTP_$status", info?.message ?: "请求失败（HTTP $status）")
    }
}

@kotlinx.serialization.Serializable
private data class TitleRequest(val title: String? = null)
