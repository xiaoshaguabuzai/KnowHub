package com.example.knowhub.server

import com.example.knowhub.core.AppJson
import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.model.HealthDto
import com.example.knowhub.server.config.AppConfig
import com.example.knowhub.server.routes.adminRoutes
import com.example.knowhub.server.routes.attachmentRoutes
import com.example.knowhub.server.routes.authRoutes
import com.example.knowhub.server.routes.chatRoutes
import com.example.knowhub.server.routes.documentRoutes
import com.example.knowhub.server.routes.flowRoutes
import com.example.knowhub.server.routes.sessionRoutes
import com.example.knowhub.server.routes.toolRoutes
import com.example.knowhub.server.support.Headers
import com.example.knowhub.server.support.respondApiError
import com.example.knowhub.server.support.traceId
import com.example.knowhub.server.support.ApiException
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val log = LoggerFactory.getLogger("com.example.knowhub.server.Application")

fun main() {
    val config = AppConfig()
    embeddedServer(Netty, port = config.port, host = config.host) { module(config) }.start(wait = true)
}

fun Application.module(config: AppConfig = AppConfig()) {
    val container = AppContainer(config)
    monitor.subscribe(ApplicationStopped) { container.close() }

    install(ContentNegotiation) {
        json(AppJson)
    }
    install(CallLogging) {
        level = Level.INFO
        filter { call -> call.request.path().startsWith("/api") }
    }
    install(CORS) {
        anyHost()
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(Headers.TERMINAL)
        allowHeader(Headers.TRACE)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
    }
    install(StatusPages) {
        exception<ApiException> { call, cause ->
            call.respondApiError(cause.status, cause.code, cause.message, call.traceId())
        }
        exception<Throwable> { call, cause ->
            log.error("未处理异常", cause)
            call.respondApiError(
                HttpStatusCode.InternalServerError,
                "INTERNAL_ERROR",
                cause.message ?: "服务器内部错误",
                call.traceId(),
            )
        }
    }

    routing {
        get("/") {
            call.respondText("掌故 KnowHub API · ${container.llm.name}")
        }
        get(ApiRoutes.HEALTH) {
            val db = container.store.snapshot()
            call.respond(
                HealthDto(
                    status = "ok",
                    version = "1.0.0",
                    llm = container.llm.name,
                    documents = db.documents.size,
                    chunks = db.chunks.size,
                    serverTime = System.currentTimeMillis(),
                )
            )
        }
        authRoutes(container)
        sessionRoutes(container)
        chatRoutes(container)
        attachmentRoutes(container)
        documentRoutes(container)
        flowRoutes(container)
        toolRoutes(container)
        adminRoutes(container)
    }
}
