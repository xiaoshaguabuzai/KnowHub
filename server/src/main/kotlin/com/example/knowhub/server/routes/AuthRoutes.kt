package com.example.knowhub.server.routes

import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.model.LoginRequest
import com.example.knowhub.core.model.LoginResponse
import com.example.knowhub.server.AppContainer
import com.example.knowhub.server.support.ApiException
import com.example.knowhub.server.support.clientIp
import com.example.knowhub.server.support.terminal
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.authRoutes(container: AppContainer) {
    post(ApiRoutes.LOGIN) {
        val request = call.receive<LoginRequest>()
        val user = container.store.snapshot().users.firstOrNull { it.username == request.username.trim() }
        if (user == null || !container.auth.verifyPassword(request.password, user.salt, user.passwordHash)) {
            throw ApiException(HttpStatusCode.Unauthorized, "BAD_CREDENTIALS", "账号或密码错误")
        }
        if (!user.active) {
            throw ApiException(HttpStatusCode.Forbidden, "ACCOUNT_DISABLED", "账号已停用")
        }
        val (token, expiresAt) = container.auth.issueToken(user)
        container.audit.record(
            actor = user,
            action = "LOGIN",
            targetType = "user",
            targetId = user.userId,
            terminal = call.terminal(),
            ip = call.clientIp(),
        )
        call.respond(LoginResponse(token = token, expiresAt = expiresAt, user = user.toDto()))
    }

    get(ApiRoutes.ME) {
        call.respond(container.authContext.require(call).toDto())
    }
}
