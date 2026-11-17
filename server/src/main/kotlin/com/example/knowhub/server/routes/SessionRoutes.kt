package com.example.knowhub.server.routes

import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.model.CreateSessionRequest
import com.example.knowhub.core.model.SessionDto
import com.example.knowhub.core.util.newId
import com.example.knowhub.server.AppContainer
import com.example.knowhub.server.chat.ChatService
import com.example.knowhub.server.store.SessionRecord
import com.example.knowhub.server.support.ApiException
import com.example.knowhub.server.support.clientIp
import com.example.knowhub.server.support.terminal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

fun Route.sessionRoutes(container: AppContainer) {
    route(ApiRoutes.SESSIONS) {
        get {
            val user = container.authContext.require(call)
            val db = container.store.snapshot()
            val sessions = db.sessions
                .filter { it.userId == user.userId }
                .sortedByDescending { it.updatedAt }
                .map { session ->
                    val count = db.messages.count { it.sessionId == session.sessionId }
                    val flowName = session.pendingFlow?.let { db.flowById(it.flowId)?.name }
                    session.toDto(messageCount = count, pendingFlowName = flowName)
                }
            call.respond(sessions)
        }

        post {
            val user = container.authContext.require(call)
            val request = runCatching { call.receive<CreateSessionRequest>() }.getOrDefault(CreateSessionRequest())
            val now = System.currentTimeMillis()
            val record = SessionRecord(
                sessionId = newId("ses"),
                userId = user.userId,
                title = request.title?.takeIf { it.isNotBlank() } ?: ChatService.DEFAULT_TITLE,
                createdAt = now,
                updatedAt = now,
            )
            container.store.mutate { db -> db.copy(sessions = db.sessions + record) to Unit }
            container.audit.record(user, "SESSION_CREATE", "session", record.sessionId, terminal = call.terminal(), ip = call.clientIp())
            call.respond(HttpStatusCode.Created, record.toDto(messageCount = 0, pendingFlowName = null))
        }

        delete("{sessionId}") {
            val user = container.authContext.require(call)
            val sessionId = call.parameters["sessionId"] ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "缺少 sessionId")
            val db = container.store.snapshot()
            val session = db.sessionById(sessionId) ?: throw ApiException(HttpStatusCode.NotFound, "SESSION_NOT_FOUND", "会话不存在")
            if (session.userId != user.userId) throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "无权删除该会话")
            container.store.mutate { current ->
                current.copy(
                    sessions = current.sessions.filterNot { it.sessionId == sessionId },
                    messages = current.messages.filterNot { it.sessionId == sessionId },
                ) to Unit
            }
            container.audit.record(user, "SESSION_DELETE", "session", sessionId, terminal = call.terminal(), ip = call.clientIp())
            call.respond(HttpStatusCode.NoContent)
        }

        get("{sessionId}/messages") {
            val user = container.authContext.require(call)
            val sessionId = call.parameters["sessionId"] ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "缺少 sessionId")
            val db = container.store.snapshot()
            val session = db.sessionById(sessionId) ?: throw ApiException(HttpStatusCode.NotFound, "SESSION_NOT_FOUND", "会话不存在")
            if (session.userId != user.userId) throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "无权访问该会话")
            call.respond(db.messagesOf(sessionId).map { it.toDto() })
        }
    }
}
