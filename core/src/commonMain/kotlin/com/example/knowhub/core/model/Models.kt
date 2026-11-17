package com.example.knowhub.core.model

import kotlinx.serialization.Serializable

// ============================ 身份与鉴权 ============================

@Serializable
data class UserDto(
    val userId: String,
    val username: String,
    val displayName: String,
    val roles: List<String> = listOf(Roles.MEMBER),
    val tenantId: String = "default",
    val createdAt: Long = 0,
)

object Roles {
    const val ADMIN = "admin"
    const val MEMBER = "member"
    const val HR = "hr"
    const val PATIENT = "patient"
}

@Serializable
data class LoginRequest(val username: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val expiresAt: Long, val user: UserDto)

@Serializable
data class ApiErrorInfo(val code: String, val message: String, val traceId: String? = null)

// ============================ 会话与消息 ============================

@Serializable
data class SessionDto(
    val sessionId: String,
    val title: String,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val messageCount: Int = 0,
    val pendingFlowName: String? = null,
)

@Serializable
data class CreateSessionRequest(val title: String? = null)

@Serializable
data class SourceRef(
    val docId: String,
    val docName: String,
    val chunkId: String,
    val page: Int = 1,
    val section: String? = null,
    val chunkIndex: Int = 0,
    val score: Double = 0.0,
    val snippet: String = "",
)

@Serializable
data class FlowStepInfo(
    val flowId: String,
    val nodeId: String,
    val nodeType: String,
    val desc: String,
    val status: String = "RUNNING",
)

@Serializable
data class UsageInfo(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val llm: String = "local",
    val latencyMs: Long = 0,
)

/**
 * SSE 流事件。type 取值见 [StreamEventTypes]。
 *
 * 相比初版方案，这里补齐了 id/seq、sessionId、messageId、traceId：
 * 客户端断线后可用 Last-Event-ID + seq 做去重与续接。
 */
@Serializable
data class StreamEvent(
    val type: String,
    val seq: Long = 0,
    val sessionId: String? = null,
    val messageId: String? = null,
    val traceId: String? = null,
    val content: String? = null,
    val sources: List<SourceRef>? = null,
    val flow: FlowStepInfo? = null,
    val error: ApiErrorInfo? = null,
    val usage: UsageInfo? = null,
)

object StreamEventTypes {
    const val META = "meta"
    const val TEXT = "text"
    const val SOURCE = "source"
    const val FLOW = "flow_step"
    const val DONE = "done"
    const val ERROR = "error"
}

@Serializable
data class ChatRequest(
    val sessionId: String,
    val message: String,
    val flowId: String? = null,
    val attachmentIds: List<String> = emptyList(),
)

@Serializable
data class AttachmentDto(
    val attachmentId: String,
    val name: String,
    val mime: String = "",
    val sizeBytes: Long = 0,
    val chars: Int = 0,
    val preview: String = "",
    val hasText: Boolean = false,
    val createdAt: Long = 0,
)

@Serializable
data class MessageDto(
    val messageId: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val sources: List<SourceRef> = emptyList(),
    val createdAt: Long = 0,
    val intent: String? = null,
    val flowId: String? = null,
    val traceId: String? = null,
    val latencyMs: Long = 0,
    val rating: Int = 0,
)

object MessageRoles {
    const val USER = "user"
    const val ASSISTANT = "assistant"
    const val SYSTEM = "system"
}

// ============================ 知识库 ============================

@Serializable
data class DocumentDto(
    val docId: String,
    val name: String,
    val mime: String = "text/plain",
    val sizeBytes: Long = 0,
    val status: String = DocumentStatus.INDEXED,
    val pageCount: Int = 0,
    val chunkCount: Int = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val version: Int = 1,
    val tenantId: String = "default",
    val ownerId: String = "",
    val aclRoles: List<String> = emptyList(),
    /** staff=仅职工可见；all=患者与职工都可见（就医指南类）。 */
    val audience: String = "staff",
    val docNo: String? = null,
    val department: String? = null,
    val effectiveDate: String? = null,
    val error: String? = null,
)

object DocumentStatus {
    const val PARSING = "PARSING"
    const val INDEXED = "INDEXED"
    const val FAILED = "FAILED"
}

@Serializable
data class Chunk(
    val chunkId: String,
    val docId: String,
    val docName: String,
    val page: Int = 1,
    val section: String? = null,
    val index: Int = 0,
    val text: String,
)

@Serializable
data class TextDocumentRequest(
    val name: String,
    val content: String,
    val aclRoles: List<String> = emptyList(),
)

@Serializable
data class DocumentDetailDto(
    val doc: DocumentDto,
    val content: String,
    val truncated: Boolean = false,
    val fromCache: Boolean = true,
)

// ============================ 调教与配置 ============================

@Serializable
data class RagConfig(
    val chunkSizeTokens: Int = 220,
    val overlapTokens: Int = 40,
    val topK: Int = 6,
    val rerankCandidates: Int = 12,
    val forceCitation: Boolean = true,
    val minScore: Double = 0.02,
    val tuningEnabled: Boolean = true,
)

@Serializable
data class FeedbackRequest(
    val messageId: String,
    val rating: String, // up / down
    val chunkId: String? = null,
    val comment: String? = null,
)

@Serializable
data class FeedbackDto(
    val feedbackId: String,
    val messageId: String,
    val userId: String,
    val rating: String,
    val chunkId: String? = null,
    val comment: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class TuneSample(
    val sampleId: String,
    val query: String,
    val chunkId: String,
    val positive: Boolean,
    val createdBy: String,
    val createdAt: Long = 0,
)

// ============================ 审计与统计 ============================

@Serializable
data class AuditEntry(
    val auditId: String,
    val actorId: String,
    val actorName: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val detail: String = "",
    val terminal: String = "",
    val ip: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class StatsOverview(
    val documentCount: Int = 0,
    val chunkCount: Int = 0,
    val sessionCount: Int = 0,
    val messageCount: Int = 0,
    val questionCount: Int = 0,
    val missCount: Int = 0,
    val avgLatencyMs: Long = 0,
    val positiveFeedback: Int = 0,
    val negativeFeedback: Int = 0,
    val llm: String = "local",
    val topQuestions: List<String> = emptyList(),
)

@Serializable
data class HealthDto(
    val status: String = "ok",
    val version: String = "1.0.0",
    val llm: String = "local",
    val documents: Int = 0,
    val chunks: Int = 0,
    val serverTime: Long = 0,
)
