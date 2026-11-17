package com.example.knowhub.server.routes

import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.model.ToolInvokeRequest
import com.example.knowhub.server.AppContainer
import com.example.knowhub.server.support.clientIp
import com.example.knowhub.server.support.terminal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

fun Route.toolRoutes(container: AppContainer) {
    get(ApiRoutes.TOOLS) {
        container.authContext.require(call)
        call.respond(container.tools.definitions)
    }

    post("${ApiRoutes.TOOLS}/{toolId}/invoke") {
        val user = container.authContext.require(call)
        val toolId = call.parameters["toolId"].orEmpty()
        val request = call.receive<ToolInvokeRequest>()
        val result = container.tools.invoke(toolId, request.args, user)
        container.audit.record(
            user, "TOOL_INVOKE", "tool", toolId,
            detail = if (result.ok) "ok" else result.message,
            terminal = call.terminal(), ip = call.clientIp(),
        )
        call.respond(result)
    }
}
