package com.example.knowhub.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.knowhub.core.api.AppDefaults
import com.example.knowhub.core.model.AttachmentDto
import com.example.knowhub.core.model.DocumentDetailDto
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.DocumentStatus
import com.example.knowhub.core.model.FlowStepInfo
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.HealthDto
import com.example.knowhub.core.model.MessageDto
import com.example.knowhub.core.model.MessageRoles
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.SessionDto
import com.example.knowhub.core.model.SourceRef
import com.example.knowhub.core.model.StatsOverview
import com.example.knowhub.core.model.StreamEventTypes
import com.example.knowhub.core.model.UserDto
import com.example.knowhub.core.model.Roles
import com.example.knowhub.core.util.newId
import com.example.knowhub.input.PickedAttachment
import com.example.knowhub.net.KnowHubClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

enum class Tab { CHAT, KNOWLEDGE, SETTINGS }

/** 接入方式：中台（Ktor，含知识库/流程）或直连（API Key 直调模型）。 */
enum class AccessMode { PLATFORM, DIRECT }

object DocumentFilter {
    const val ALL = "ALL"
    const val INDEXED = "INDEXED"
    const val PARSING = "PARSING"
    const val FAILED = "FAILED"
}

/**
 * 单例 UI 状态：登录态、会话、流式输出、知识库与管理数据。
 * 所有网络调用都在传入的 scope 中执行，UI 只读状态。
 */
class AppState {

    val client = KnowHubClient()

    var accessMode by mutableStateOf(AccessMode.PLATFORM)
    var directApiKey by mutableStateOf("")
    var directBaseUrl by mutableStateOf("https://api.openai.com/v1")
    var directModel by mutableStateOf("gpt-4o-mini")
    var loginStartDirect by mutableStateOf(false)

    val isDirect: Boolean get() = accessMode == AccessMode.DIRECT

    /** 直连模式无需登录即可进入主界面；中台模式必须有 token。 */
    val loggedIn: Boolean get() = isDirect || token != null

    var serverUrl by mutableStateOf(AppDefaults.BASE_URL)
    var token by mutableStateOf<String?>(null)
    var user by mutableStateOf<UserDto?>(null)
    var tab by mutableStateOf(Tab.CHAT)

    var username by mutableStateOf(AppDefaults.DEMO_USER)
    var password by mutableStateOf(AppDefaults.DEMO_PASSWORD)
    var loginBusy by mutableStateOf(false)
    var loginError by mutableStateOf<String?>(null)

    var sessions by mutableStateOf<List<SessionDto>>(emptyList())
    var currentSessionId by mutableStateOf<String?>(null)
    var messages by mutableStateOf<List<MessageDto>>(emptyList())

    var streaming by mutableStateOf(false)
    var streamText by mutableStateOf("")
    var streamSources by mutableStateOf<List<SourceRef>>(emptyList())
    var flowSteps by mutableStateOf<List<FlowStepInfo>>(emptyList())
    var error by mutableStateOf<String?>(null)
    var info by mutableStateOf<String?>(null)

    var pendingAttachments by mutableStateOf<List<AttachmentDto>>(emptyList())
    var attachmentBusy by mutableStateOf(false)

    var documents by mutableStateOf<List<DocumentDto>>(emptyList())
    var flows by mutableStateOf<List<FlowTemplate>>(emptyList())
    var stats by mutableStateOf<StatsOverview?>(null)
    var rag by mutableStateOf<RagConfig?>(null)
    var health by mutableStateOf<HealthDto?>(null)
    var knowledgeBusy by mutableStateOf(false)

    var docQuery by mutableStateOf("")
    var docFilter by mutableStateOf(DocumentFilter.ALL)
    var deptFilter by mutableStateOf("ALL")
    var viewingDoc by mutableStateOf<DocumentDto?>(null)
    var docDetail by mutableStateOf<DocumentDetailDto?>(null)
    var docLoading by mutableStateOf(false)
    var docViewError by mutableStateOf<String?>(null)

    var showCreateDocDialog by mutableStateOf(false)
    var newDocName by mutableStateOf("")
    var newDocContent by mutableStateOf("")
    var newDocAcl by mutableStateOf("")
    var docError by mutableStateOf<String?>(null)

    var editingRag by mutableStateOf(false)
    var ragDraft by mutableStateOf<RagConfig?>(null)

    private var streamJob: Job? = null

    val isAdmin: Boolean get() = user?.roles?.contains(Roles.ADMIN) == true
    val isPatient: Boolean get() = user?.roles?.contains(Roles.PATIENT) == true
    val isStaff: Boolean get() = user != null && !isPatient

    val currentSession: SessionDto? get() = sessions.firstOrNull { it.sessionId == currentSessionId }

    // ------------------------------------------------------------ 登录 / 会话

    fun login(scope: CoroutineScope) {
        if (loginBusy) return
        loginBusy = true
        loginError = null
        scope.launch {
            try {
                client.baseUrl = serverUrl
                val response = client.login(username, password)
                accessMode = AccessMode.PLATFORM
                loginStartDirect = false
                token = response.token
                user = response.user
                refreshSessions()
                val first = sessions.firstOrNull()
                if (first != null) {
                    openSession(scope, first.sessionId)
                } else {
                    createSession(scope)
                }
            } catch (e: Exception) {
                loginError = e.message ?: "登录失败，请检查服务端地址与网络"
                client.clearSession()
                token = null
                user = null
            } finally {
                loginBusy = false
            }
        }
    }

    /** 直连模式：用用户自己的 API Key 直接调模型，不进中台。 */
    fun startDirect() {
        if (directApiKey.isBlank()) {
            loginError = "请先填写 API Key"
            return
        }
        streamJob?.cancel()
        accessMode = AccessMode.DIRECT
        loginStartDirect = false
        token = null
        user = null
        sessions = emptyList()
        currentSessionId = "direct"
        messages = emptyList()
        streamText = ""
        streamSources = emptyList()
        flowSteps = emptyList()
        streaming = false
        error = null
        loginError = null
        tab = Tab.CHAT
        info = "直连模式：直接调用你配置的模型，不含院内知识库；可在设置切回中台"
    }

    /** 从中台切到直连登录页（保留已填的 Key）。 */
    fun switchToDirectLogin() {
        streamJob?.cancel()
        token = null
        user = null
        sessions = emptyList()
        currentSessionId = null
        messages = emptyList()
        streamText = ""
        streamSources = emptyList()
        flowSteps = emptyList()
        streaming = false
        tab = Tab.CHAT
        loginStartDirect = true
        info = null
        error = null
    }

    /** 从直连模式切回中台登录页。 */
    fun switchToPlatformLogin() {
        streamJob?.cancel()
        accessMode = AccessMode.PLATFORM
        token = null
        user = null
        sessions = emptyList()
        currentSessionId = null
        messages = emptyList()
        streamText = ""
        streamSources = emptyList()
        flowSteps = emptyList()
        streaming = false
        tab = Tab.CHAT
        info = null
        error = null
    }

    fun logout() {
        streamJob?.cancel()
        client.clearSession()
        accessMode = AccessMode.PLATFORM
        token = null
        user = null
        sessions = emptyList()
        currentSessionId = null
        messages = emptyList()
        streamText = ""
        streamSources = emptyList()
        flowSteps = emptyList()
        streaming = false
        documents = emptyList()
        flows = emptyList()
        stats = null
        rag = null
        health = null
        info = null
        error = null
    }

    suspend fun refreshSessions() {
        sessions = client.sessions()
    }

    fun createSession(scope: CoroutineScope) {
        if (isDirect) {
            currentSessionId = "direct"
            messages = emptyList()
            streamText = ""
            streamSources = emptyList()
            flowSteps = emptyList()
            error = null
            info = null
            return
        }
        scope.launch {
            try {
                val created = client.createSession()
                sessions = listOf(created) + sessions.filterNot { it.sessionId == created.sessionId }
                currentSessionId = created.sessionId
                messages = emptyList()
                streamText = ""
                streamSources = emptyList()
                flowSteps = emptyList()
                error = null
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun openSession(scope: CoroutineScope, sessionId: String) {
        currentSessionId = sessionId
        error = null
        scope.launch {
            try {
                messages = client.messages(sessionId)
                streamText = ""
                streamSources = emptyList()
                flowSteps = emptyList()
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun deleteSession(scope: CoroutineScope, sessionId: String) {
        scope.launch {
            try {
                client.deleteSession(sessionId)
                sessions = sessions.filterNot { it.sessionId == sessionId }
                if (currentSessionId == sessionId) {
                    val next = sessions.firstOrNull()
                    if (next != null) openSession(scope, next.sessionId) else createSession(scope)
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    // ------------------------------------------------------------ 对话

    fun send(scope: CoroutineScope, text: String) {
        if (isDirect) {
            directSend(scope, text)
            return
        }
        val sessionId = currentSessionId ?: return
        val attachmentsToSend = pendingAttachments
        val base = text.trim()
        if ((base.isEmpty() && attachmentsToSend.isEmpty()) || streaming) return

        val content = base
        val displayContent = if (attachmentsToSend.isEmpty()) {
            content
        } else {
            (content.ifEmpty { "请解读我上传的附件。" }) + "\n\n📎 " + attachmentsToSend.joinToString("、") { it.name }
        }

        error = null
        info = null
        val localUser = MessageDto(
            messageId = newId("local"),
            sessionId = sessionId,
            role = MessageRoles.USER,
            content = displayContent,
        )
        messages = messages + localUser
        pendingAttachments = emptyList()
        streaming = true
        streamText = ""
        streamSources = emptyList()
        flowSteps = emptyList()

        streamJob = scope.launch {
            var serverMessageId: String? = null
            try {
                client.streamChat(
                    sessionId = sessionId,
                    message = content,
                    attachmentIds = attachmentsToSend.map { it.attachmentId },
                ).collect { event ->
                    when (event.type) {
                        StreamEventTypes.TEXT -> streamText += event.content.orEmpty()
                        StreamEventTypes.SOURCE -> streamSources = event.sources ?: emptyList()
                        StreamEventTypes.FLOW -> event.flow?.let { flowSteps = flowSteps + it }
                        StreamEventTypes.DONE -> {
                            event.sources?.let { streamSources = it }
                            serverMessageId = event.messageId
                            finishStream(sessionId, serverMessageId)
                        }
                        StreamEventTypes.ERROR -> error = event.error?.message ?: "服务端返回错误"
                    }
                }
            } catch (e: CancellationException) {
                if (streamText.isNotBlank()) finishStream(sessionId, null)
            } catch (e: Exception) {
                error = e.message ?: "网络异常，请确认服务端已启动"
                if (streamText.isNotBlank()) finishStream(sessionId, null)
            } finally {
                streaming = false
                runCatching { refreshSessions() }
            }
        }
    }

    private fun directSend(scope: CoroutineScope, text: String) {
        val content = text.trim()
        if (content.isEmpty() || streaming) return
        error = null
        info = null
        messages = messages + MessageDto(
            messageId = newId("direct"),
            sessionId = "direct",
            role = MessageRoles.USER,
            content = content,
        )
        streaming = true
        streamText = ""
        streamSources = emptyList()
        flowSteps = emptyList()

        streamJob = scope.launch {
            try {
                val system =
                    "你是 KnowHub 医院助手（直连模式）。用简体中文回答；不做诊断、不推荐处方；涉及具体病情建议及时就医；不要编造院内制度与流程。"
                val history = buildList {
                    add("system" to system)
                    addAll(
                        messages
                            .filter { it.role == MessageRoles.USER || it.role == MessageRoles.ASSISTANT }
                            .takeLast(12)
                            .map { it.role to it.content }
                    )
                }
                client.streamDirect(directApiKey, directBaseUrl, directModel, history).collect { delta ->
                    streamText += delta
                }
                finishDirect()
            } catch (e: CancellationException) {
                finishDirect()
            } catch (e: Exception) {
                error = e.message ?: "直连模型请求失败，请检查 API Key 与模型地址"
                finishDirect()
            } finally {
                streaming = false
            }
        }
    }

    private fun finishDirect() {
        val content = streamText.trim()
        if (content.isEmpty()) return
        messages = messages + MessageDto(
            messageId = newId("direct"),
            sessionId = "direct",
            role = MessageRoles.ASSISTANT,
            content = content,
        )
        streamText = ""
    }

    private fun finishStream(sessionId: String, messageId: String?) {
        if (streamText.isBlank() && streamSources.isEmpty()) return
        messages = messages + MessageDto(
            messageId = messageId ?: newId("local"),
            sessionId = sessionId,
            role = MessageRoles.ASSISTANT,
            content = streamText,
            sources = streamSources,
            flowId = flowSteps.lastOrNull()?.flowId,
        )
        streamText = ""
        flowSteps = emptyList()
    }

    fun stopStream() {
        streamJob?.cancel()
        streamJob = null
        streaming = false
        currentSessionId?.let { sessionId ->
            if (streamText.isNotBlank()) finishStream(sessionId, null)
        }
    }

    fun submitFeedback(scope: CoroutineScope, messageId: String, rating: String, chunkId: String?) {
        scope.launch {
            try {
                client.feedback(messageId, rating, chunkId)
                messages = messages.map {
                    if (it.messageId == messageId) {
                        it.copy(rating = if (rating == "up") 1 else -1)
                    } else it
                }
                info = if (rating == "up") "已记录正向样本，后续召回会优先参考" else "已记录负向样本，后续召回会降权"
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    /** 上传文件/照片：服务端抽取正文，成功后挂在待发送区。 */
    fun uploadPickedAttachment(scope: CoroutineScope, picked: PickedAttachment) {
        if (attachmentBusy) return
        attachmentBusy = true
        error = null
        scope.launch {
            try {
                val dto = client.uploadAttachment(picked.name, picked.mime, picked.bytes)
                pendingAttachments = pendingAttachments + dto
                info = if (dto.hasText) {
                    "已解析《${dto.name}》共 ${dto.chars} 字，发送后一并作为回答依据"
                } else {
                    "已上传《${dto.name}》（图片），已尝试识别文字"
                }
            } catch (e: Exception) {
                error = e.message ?: "附件上传失败"
            } finally {
                attachmentBusy = false
            }
        }
    }

    fun removeAttachment(attachmentId: String) {
        pendingAttachments = pendingAttachments.filterNot { it.attachmentId == attachmentId }
    }

    fun clearAttachments() {
        pendingAttachments = emptyList()
    }

    // ------------------------------------------------------------ 知识库 / 管理

    fun loadKnowledge(scope: CoroutineScope) {
        if (knowledgeBusy) return
        knowledgeBusy = true
        scope.launch {
            try {
                documents = client.documents()
                flows = runCatching { client.flows() }.getOrDefault(emptyList())
                if (isAdmin) {
                    stats = runCatching { client.stats() }.getOrNull()
                    rag = runCatching { client.ragConfig() }.getOrNull()
                }
            } catch (e: Exception) {
                error = e.message
            } finally {
                knowledgeBusy = false
            }
        }
    }

    fun createTextDocument(scope: CoroutineScope) {
        if (newDocName.isBlank() || newDocContent.isBlank()) {
            docError = "文档名称与正文不能为空"
            return
        }
        docError = null
        scope.launch {
            try {
                val acl = newDocAcl.split(',', '，', ' ')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                client.uploadTextDocument(newDocName.trim(), newDocContent, acl)
                showCreateDocDialog = false
                newDocName = ""
                newDocContent = ""
                newDocAcl = ""
                info = "文档已提交入库，稍候可在列表中看到 INDEXED 状态"
                documents = client.documents()
            } catch (e: Exception) {
                docError = e.message
            }
        }
    }

    fun deleteDocument(scope: CoroutineScope, docId: String) {
        scope.launch {
            try {
                client.deleteDocument(docId)
                documents = client.documents()
                info = "文档已删除"
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun reindexDocument(scope: CoroutineScope, docId: String) {
        scope.launch {
            try {
                client.reindexDocument(docId)
                documents = client.documents()
                info = "已触发重建索引"
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    /** 打开文档阅读器：拉取原文（服务端已做 ACL 校验）。 */
    fun openDocument(scope: CoroutineScope, doc: DocumentDto) {
        viewingDoc = doc
        docDetail = null
        docViewError = null
        docLoading = true
        scope.launch {
            try {
                docDetail = client.documentDetail(doc.docId)
            } catch (e: Exception) {
                docViewError = e.message ?: "文档加载失败"
            } finally {
                docLoading = false
            }
        }
    }

    fun closeDocument() {
        viewingDoc = null
        docDetail = null
        docViewError = null
        docLoading = false
    }

    /** 制度库中出现的归口科室（用于筛选）。 */
    val departments: List<String>
        get() = documents.mapNotNull { it.department?.takeIf { d -> d.isNotBlank() } }.distinct().sorted()

    val filteredDocuments: List<DocumentDto>
        get() {
            val query = docQuery.trim()
            return documents.filter { doc ->
                val statusOk = when (docFilter) {
                    DocumentFilter.INDEXED -> doc.status == DocumentStatus.INDEXED
                    DocumentFilter.PARSING -> doc.status == DocumentStatus.PARSING
                    DocumentFilter.FAILED -> doc.status == DocumentStatus.FAILED
                    else -> true
                }
                val deptOk = deptFilter == "ALL" || doc.department == deptFilter
                val queryOk = query.isEmpty() ||
                    doc.name.contains(query, ignoreCase = true) ||
                    doc.docNo?.contains(query, ignoreCase = true) == true ||
                    doc.department?.contains(query, ignoreCase = true) == true
                statusOk && deptOk && queryOk
            }
        }

    fun checkHealth(scope: CoroutineScope) {
        scope.launch {
            try {
                client.baseUrl = serverUrl
                health = client.health()
                info = "服务端连接正常"
            } catch (e: Exception) {
                health = null
                error = "无法连接服务端：${e.message}"
            }
        }
    }

    fun startEditRag() {
        ragDraft = rag ?: RagConfig()
        editingRag = true
    }

    fun saveRag(scope: CoroutineScope) {
        val draft = ragDraft ?: return
        scope.launch {
            try {
                rag = client.updateRagConfig(draft)
                editingRag = false
                info = "检索配置已更新并立即生效"
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun clearMessages() {
        error = null
        info = null
    }

    fun dismissInfo() {
        info = null
    }

    fun dismissError() {
        error = null
    }

    fun currentUser(): UserDto? = user
}
