package com.example.knowhub.local

import com.example.knowhub.core.AppJson
import com.example.knowhub.core.model.AttachmentDto
import com.example.knowhub.core.model.Chunk
import com.example.knowhub.core.model.DocumentDetailDto
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.DocumentStatus
import com.example.knowhub.core.model.FlowRunState
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.MessageDto
import com.example.knowhub.core.model.MessageRoles
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.Roles
import com.example.knowhub.core.model.SessionDto
import com.example.knowhub.core.model.SourceRef
import com.example.knowhub.core.model.StatsOverview
import com.example.knowhub.core.model.TuneSample
import com.example.knowhub.core.model.UserDto
import com.example.knowhub.core.rag.HybridRetriever
import com.example.knowhub.core.security.Acl
import com.example.knowhub.core.text.Chunker
import com.example.knowhub.core.util.newId
import kotlinx.serialization.Serializable

@Serializable
private data class LocalSeedPayload(
    val documents: List<LocalSeedDocument>,
    val flows: List<FlowTemplate>,
)

@Serializable
private data class LocalSeedDocument(
    val doc: DocumentDto,
    val content: String,
)

/** 直连模式的本地附件：文本已在端侧解析。 */
data class LocalAttachment(val dto: AttachmentDto, val text: String)

data class LocalMessage(
    val messageId: String,
    val role: String,
    val content: String,
    val sources: List<SourceRef> = emptyList(),
    val rating: Int = 0,
    val createdAt: Long = 0,
) {
    fun toDto(sessionId: String): MessageDto = MessageDto(
        messageId = messageId,
        sessionId = sessionId,
        role = role,
        content = content,
        sources = sources,
        createdAt = createdAt,
        rating = rating,
    )
}

data class LocalSession(
    val sessionId: String,
    val title: String,
    val messages: List<LocalMessage>,
    val pendingFlow: FlowRunState? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    fun toDto(flows: List<FlowTemplate>): SessionDto = SessionDto(
        sessionId = sessionId,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        messageCount = messages.size,
        pendingFlowName = pendingFlow?.flowId?.let { id -> flows.firstOrNull { it.flowId == id }?.name },
    )
}

/**
 * 直连模式的本地工作区：内置与服务端一致的知识快照（36 篇 + 4 条流程），
 * 在端侧完成切块、混合检索、ACL、流程状态与调教样本，不依赖 Ktor 中台。
 */
class LocalWorkspace {

    private val seed: LocalSeedPayload =
        AppJson.decodeFromString(LocalSeedPayload.serializer(), LocalSeed.json())

    val rag: RagConfig = RagConfig()
    val documents: List<DocumentDto>
    val contents: Map<String, String>
    val chunks: List<Chunk>
    val flows: List<FlowTemplate>

    var tuneSamples: List<TuneSample> = emptyList()
        private set

    private var cachedRetriever: HybridRetriever? = null
    private var cachedKey: String = ""
    private val sessionMap = LinkedHashMap<String, LocalSession>()
    private var messageCounter = 0

    init {
        val docs = ArrayList<DocumentDto>(seed.documents.size)
        val contentMap = HashMap<String, String>(seed.documents.size)
        val allChunks = ArrayList<Chunk>()
        for (item in seed.documents) {
            val docChunks = Chunker.chunk(item.content, item.doc.docId, item.doc.name, rag, page = 1)
            docs += item.doc.copy(
                status = DocumentStatus.INDEXED,
                pageCount = 1,
                chunkCount = docChunks.size,
            )
            contentMap[item.doc.docId] = item.content
            allChunks += docChunks
        }
        documents = docs
        contents = contentMap
        chunks = allChunks
        flows = seed.flows
    }

    fun userFor(role: String): UserDto = UserDto(
        userId = "local_$role",
        username = role,
        displayName = when (role) {
            Roles.PATIENT -> "王女士（患者）"
            Roles.ADMIN -> "医务处管理员"
            Roles.HR -> "人事科"
            else -> "张医生（内科）"
        },
        roles = listOf(role),
        tenantId = "default",
    )

    fun visibleDocuments(user: UserDto): List<DocumentDto> =
        documents.filter { Acl.canRead(it, user) }

    fun visibleFlows(user: UserDto): List<FlowTemplate> =
        flows.filter { it.enabled && Acl.canRunFlow(it.audience, user) }

    fun retriever(): HybridRetriever {
        val key = "${chunks.size}|${tuneSamples.size}"
        val current = cachedRetriever
        if (current == null || key != cachedKey) {
            cachedRetriever = HybridRetriever(
                chunks = chunks,
                documents = documents.associateBy { it.docId },
                rag = rag,
                tuneSamples = tuneSamples,
            )
            cachedKey = key
        }
        return cachedRetriever!!
    }

    fun documentDetail(docId: String): DocumentDetailDto? {
        val doc = documents.firstOrNull { it.docId == docId } ?: return null
        return DocumentDetailDto(doc = doc, content = contents[docId].orEmpty(), fromCache = true)
    }

    // ---------------------------------------------------------------- 会话

    fun newSession(): LocalSession {
        val now = localNow()
        val session = LocalSession(
            sessionId = newId("local"),
            title = "新会话",
            messages = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        sessionMap[session.sessionId] = session
        return session
    }

    fun sessions(): List<LocalSession> = sessionMap.values.sortedByDescending { it.updatedAt }

    fun session(sessionId: String): LocalSession? = sessionMap[sessionId]

    fun ensureSession(sessionId: String?): LocalSession =
        sessionId?.let { sessionMap[it] } ?: newSession()

    fun appendMessage(
        sessionId: String,
        role: String,
        content: String,
        sources: List<SourceRef> = emptyList(),
        rating: Int = 0,
    ): LocalMessage {
        messageCounter++
        val message = LocalMessage(
            messageId = "lm_${localNow()}_$messageCounter",
            role = role,
            content = content,
            sources = sources,
            rating = rating,
            createdAt = localNow(),
        )
        val session = sessionMap[sessionId] ?: return message
        val title = if (role == MessageRoles.USER && session.title == "新会话") {
            content.replace("\n", " ").take(18)
        } else {
            session.title
        }
        sessionMap[sessionId] = session.copy(
            title = title,
            messages = session.messages + message,
            updatedAt = localNow(),
        )
        return message
    }

    fun setPending(sessionId: String, state: FlowRunState?) {
        val session = sessionMap[sessionId] ?: return
        sessionMap[sessionId] = session.copy(pendingFlow = state, updatedAt = localNow())
    }

    fun deleteSession(sessionId: String) {
        sessionMap.remove(sessionId)
    }

    fun rate(messageId: String, rating: Int) {
        for (key in sessionMap.keys.toList()) {
            val session = sessionMap[key] ?: continue
            sessionMap[key] = session.copy(
                messages = session.messages.map {
                    if (it.messageId == messageId) it.copy(rating = rating) else it
                },
            )
        }
    }

    fun addTuneSample(query: String, chunkId: String, positive: Boolean) {
        tuneSamples = tuneSamples + TuneSample(
            sampleId = newId("tune"),
            query = query,
            chunkId = chunkId,
            positive = positive,
            createdBy = "local",
            createdAt = localNow(),
        )
    }

    fun stats(): StatsOverview {
        val all = sessionMap.values
        return StatsOverview(
            documentCount = documents.size,
            chunkCount = chunks.size,
            sessionCount = all.size,
            messageCount = all.sumOf { it.messages.size },
            positiveFeedback = all.sumOf { s -> s.messages.count { it.rating > 0 } },
            negativeFeedback = all.sumOf { s -> s.messages.count { it.rating < 0 } },
            llm = "direct",
        )
    }
}
