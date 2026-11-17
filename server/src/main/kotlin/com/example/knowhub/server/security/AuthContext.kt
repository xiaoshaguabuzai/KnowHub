package com.example.knowhub.server.security

import com.example.knowhub.core.model.Roles
import com.example.knowhub.server.store.DataStore
import com.example.knowhub.server.store.UserAccount
import com.example.knowhub.server.support.ApiException
import com.example.knowhub.server.support.bearerToken
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

class AuthContext(
    private val auth: AuthService,
    private val store: DataStore,
) {
    fun require(call: ApplicationCall): UserAccount {
        val token = call.bearerToken()
            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "缺少 Authorization: Bearer 令牌")
        return auth.authenticate(token)
            ?: throw ApiException(HttpStatusCode.Unauthorized, "INVALID_TOKEN", "登录已过期，请重新登录")
    }

    fun requireAdmin(call: ApplicationCall): UserAccount {
        val user = require(call)
        if (Roles.ADMIN !in user.roles) {
            throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "需要管理员权限")
        }
        return user
    }

    fun userById(userId: String): UserAccount? = store.snapshot().userById(userId)
}
