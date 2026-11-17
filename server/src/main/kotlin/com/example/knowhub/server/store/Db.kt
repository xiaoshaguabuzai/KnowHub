package com.example.knowhub.server.store

import com.example.knowhub.core.model.AuditEntry
import com.example.knowhub.core.model.Chunk
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.FeedbackDto
import com.example.knowhub.core.model.FlowRunState
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.MessageDto
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.SourceRef
import com.example.knowhub.core.model.TuneSample
import com.example.knowhub.core.model.UserDto
import kotlinx.serialization.Serializable

/**
 * 持久化账号记录：密码只以 PBKDF2 派生值保存，绝不落明文。
 */
@Serializable
data class UserAccount(
    val userId: String,
    val username: String,
    val displayName: String,
    val roles: List<String>,
    val tenantId: String = "default",
    val passwordHash: String,
    val salt: String,
    val active: Boolean = true,
    val createdAt: Long = 0,
) {
    fun toDto(): UserDto = UserDto(
        userId = userId,
        username = username,
        displayName = displayName,
        roles = roles,
        tenantId = tenantId,
        createdAt = createdAt,
    )
}

@Serializable
data class PendingFlow(
    val flowId: String,
    val state: FlowRunState,
)

@Serializable
data class AttachmentRecord(
    val attachmentId: String,
    val ownerId: String,
    val name: String,
    val mime: String = "",
    val sizeBytes: Long = 0,
    val chars: Int = 0,
    val hasText: Boolean = false,
    val preview: String = "",
    val createdAt: Long = 0,
) {
    fun toDto(): com.example.knowhub.core.model.AttachmentDto = com.example.knowhub.core.model.AttachmentDto(
        attachmentId = attachmentId,
        name = name,
        mime = mime,
        sizeBytes = sizeBytes,
        chars = chars,
        preview = preview,
        hasText = hasText,
        createdAt = createdAt,
    )
}

@Serializable
data class SessionRecord(
    val sessionId: String,
    val userId: String,
    val title: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val pendingFlow: PendingFlow? = null,
) {
    fun toDto(messageCount: Int, pendingFlowName: String?): com.example.knowhub.core.model.SessionDto =
        com.example.knowhub.core.model.SessionDto(
            sessionId = sessionId,
            title = title,
            createdAt = createdAt,
            updatedAt = updatedAt,
            messageCount = messageCount,
            pendingFlowName = pendingFlowName,
        )
}

@Serializable
data class MessageRecord(
    val messageId: String,
    val sessionId: String,
    val userId: String,
    val role: String,
    val content: String,
    val sources: List<SourceRef> = emptyList(),
    val createdAt: Long = 0,
    val intent: String? = null,
    val flowId: String? = null,
    val traceId: String? = null,
    val latencyMs: Long = 0,
    val model: String? = null,
    val rating: Int = 0,
) {
    fun toDto(): MessageDto = MessageDto(
        messageId = messageId,
        sessionId = sessionId,
        role = role,
        content = content,
        sources = sources,
        createdAt = createdAt,
        intent = intent,
        flowId = flowId,
        traceId = traceId,
        latencyMs = latencyMs,
        rating = rating,
    )
}

/**
 * 单文件 JSON 快照库：MVP 阶段用最低依赖换最快的可运行性，
 * 通过 [DataStore] 的同步写与原子替换保证一致性。
 */
@Serializable
data class Db(
    val revision: Long = 0,
    val users: List<UserAccount> = emptyList(),
    val sessions: List<SessionRecord> = emptyList(),
    val messages: List<MessageRecord> = emptyList(),
    val attachments: List<AttachmentRecord> = emptyList(),
    val documents: List<DocumentDto> = emptyList(),
    val chunks: List<Chunk> = emptyList(),
    val flows: List<FlowTemplate> = emptyList(),
    val feedback: List<FeedbackDto> = emptyList(),
    val tuneSamples: List<TuneSample> = emptyList(),
    val audit: List<AuditEntry> = emptyList(),
    val rag: RagConfig = RagConfig(),
    val counters: Map<String, Long> = emptyMap(),
) {
    fun userById(id: String): UserAccount? = users.firstOrNull { it.userId == id }
    fun sessionById(id: String): SessionRecord? = sessions.firstOrNull { it.sessionId == id }
    fun documentById(id: String): DocumentDto? = documents.firstOrNull { it.docId == id }
    fun flowById(id: String): FlowTemplate? = flows.firstOrNull { it.flowId == id }
    fun messagesOf(sessionId: String): List<MessageRecord> = messages.filter { it.sessionId == sessionId }.sortedBy { it.createdAt }
    fun attachmentById(id: String): AttachmentRecord? = attachments.firstOrNull { it.attachmentId == id }
}
