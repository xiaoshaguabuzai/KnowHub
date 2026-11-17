package com.example.knowhub.server.routes

import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.model.ChatRequest
import com.example.knowhub.core.sse.Sse
import com.example.knowhub.server.AppContainer
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException

fun Route.chatRoutes(container: AppContainer) {
    post(ApiRoutes.CHAT_STREAM) {
        val user = container.authContext.require(call)
        val request = call.receive<ChatRequest>()

        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.Connection, "keep-alive")
        call.response.headers.append("X-Accel-Buffering", "no")

        call.respondTextWriter(contentType = ContentType.Text.EventStream, status = HttpStatusCode.OK) {
            try {
                container.chat.stream(user, request) { event ->
                    write(Sse.encode(event))
                    flush()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 客户端断开属于常态：不再尝试写回，直接结束帧循环
                runCatching { write(Sse.heartbeat()); flush() }
            }
        }
    }
}
