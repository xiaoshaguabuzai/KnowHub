package com.example.knowhub.server.support

import com.example.knowhub.core.model.ApiErrorInfo
import com.example.knowhub.core.util.newId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respond

class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String,
) : RuntimeException(message)

object Headers {
    const val TERMINAL = "X-Terminal-Type"
    const val TRACE = "X-Trace-Id"
}

fun ApplicationCall.terminal(): String = request.header(Headers.TERMINAL) ?: "unknown"

fun ApplicationCall.clientIp(): String =
    request.header("X-Forwarded-For")?.substringBefore(',')?.trim() ?: "local"

fun ApplicationCall.traceId(): String = request.header(Headers.TRACE) ?: newId("tr")

fun ApplicationCall.bearerToken(): String? =
    request.header("Authorization")?.trim()?.removePrefix("Bearer ")?.trim()?.takeIf { it.isNotEmpty() }

suspend fun ApplicationCall.respondApiError(status: HttpStatusCode, code: String, message: String, traceId: String? = null) {
    respond(status, ApiErrorInfo(code = code, message = message, traceId = traceId ?: newId("tr")))
}
