package com.example.knowhub.server.routes

import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.model.FeedbackDto
import com.example.knowhub.core.model.FeedbackRequest
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.StatsOverview
import com.example.knowhub.core.model.TuneSample
import com.example.knowhub.core.util.newId
import com.example.knowhub.server.AppContainer
import com.example.knowhub.server.chat.ChatService
import com.example.knowhub.server.support.ApiException
import com.example.knowhub.server.support.clientIp
import com.example.knowhub.server.support.terminal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

fun Route.adminRoutes(container: AppContainer) {
    get(ApiRoutes.RAG_CONFIG) {
        container.authContext.requireAdmin(call)
        call.respond(container.store.snapshot().rag)
    }

    put(ApiRoutes.RAG_CONFIG) {
        val admin = container.authContext.requireAdmin(call)
        val incoming = call.receive<RagConfig>()
        val errors = buildList {
            if (incoming.topK !in 1..20) add("topK 需在 1~20 之间")
            if (incoming.chunkSizeTokens !in 64..1024) add("chunkSizeTokens 需在 64~1024 之间")
            if (incoming.overlapTokens !in 0 until incoming.chunkSizeTokens) add("overlapTokens 需小于 chunkSizeTokens")
            if (incoming.minScore !in 0.0..1.0) add("minScore 需在 0~1 之间")
        }
        if (errors.isNotEmpty()) {
            throw ApiException(HttpStatusCode.UnprocessableEntity, "CONFIG_INVALID", errors.joinToString("；"))
        }
        container.store.mutate { db -> db.copy(rag = incoming) to Unit }
        container.audit.record(
            admin, "RAG_CONFIG_UPDATE", "config", "rag",
            detail = "topK=${incoming.topK} chunk=${incoming.chunkSizeTokens}",
            terminal = call.terminal(), ip = call.clientIp(),
        )
        call.respond(incoming)
    }

    get(ApiRoutes.STATS) {
        container.authContext.requireAdmin(call)
        val db = container.store.snapshot()
        val userMessages = db.messages.filter { it.role == "user" }
        val assistant = db.messages.filter { it.role == "assistant" }
        val kbAnswers = assistant.filter { it.intent == "kb" }
        val overview = StatsOverview(
            documentCount = db.documents.size,
            chunkCount = db.chunks.size,
            sessionCount = db.sessions.size,
            messageCount = db.messages.size,
            questionCount = userMessages.size,
            missCount = kbAnswers.count { it.sources.isEmpty() },
            avgLatencyMs = if (assistant.isEmpty()) 0 else assistant.map { it.latencyMs }.average().toLong(),
            positiveFeedback = db.feedback.count { it.rating == "up" },
            negativeFeedback = db.feedback.count { it.rating == "down" },
            llm = container.llm.name,
            topQuestions = userMessages
                .groupingBy { it.content.take(24) }
                .eachCount()
                .entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key },
        )
        call.respond(overview)
    }

    get(ApiRoutes.AUDIT) {
        container.authContext.requireAdmin(call)
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 500) ?: 100
        call.respond(container.store.snapshot().audit.takeLast(limit).reversed())
    }

    post(ApiRoutes.FEEDBACK) {
        val user = container.authContext.require(call)
        val request = call.receive<FeedbackRequest>()
        val db = container.store.snapshot()
        val message = db.messages.firstOrNull { it.messageId == request.messageId }
            ?: throw ApiException(HttpStatusCode.NotFound, "MESSAGE_NOT_FOUND", "消息不存在")
        val session = db.sessionById(message.sessionId)
            ?: throw ApiException(HttpStatusCode.NotFound, "SESSION_NOT_FOUND", "会话不存在")
        if (session.userId != user.userId) throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "无权评价该消息")

        val rating = when (request.rating) {
            "up" -> 1
            "down" -> -1
            else -> throw ApiException(HttpStatusCode.BadRequest, "BAD_RATING", "rating 只能是 up 或 down")
        }
        val now = System.currentTimeMillis()
        val chunkId = request.chunkId ?: message.sources.firstOrNull()?.chunkId
        val query = db.messagesOf(message.sessionId)
            .lastOrNull { it.role == "user" && it.createdAt <= message.createdAt }
            ?.content
            .orEmpty()

        val feedback = FeedbackDto(
            feedbackId = newId("fb"),
            messageId = message.messageId,
            userId = user.userId,
            rating = request.rating,
            chunkId = chunkId,
            comment = request.comment,
            createdAt = now,
        )
        val tuneSample = if (chunkId != null && query.isNotBlank()) {
            TuneSample(
                sampleId = newId("tune"),
                query = query.take(200),
                chunkId = chunkId,
                positive = rating > 0,
                createdBy = user.userId,
                createdAt = now,
            )
        } else {
            null
        }

        container.store.mutate { current ->
            current.copy(
                feedback = current.feedback + feedback,
                messages = current.messages.map {
                    if (it.messageId == message.messageId) it.copy(rating = rating) else it
                },
                tuneSamples = if (tuneSample != null) (current.tuneSamples + tuneSample).takeLast(500) else current.tuneSamples,
            ) to Unit
        }
        container.audit.record(
            user, "FEEDBACK", "message", message.messageId,
            detail = "rating=${request.rating} chunk=${chunkId ?: "-"}",
            terminal = call.terminal(), ip = call.clientIp(),
        )
        call.respond(HttpStatusCode.Created, feedback)
    }

    // 便于前端展示会话标题兜底
    get("/api/v1/meta") {
        call.respond(mapOf("app" to "knowhub", "defaultSessionTitle" to ChatService.DEFAULT_TITLE))
    }
}
