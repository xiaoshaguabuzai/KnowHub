package com.example.knowhub.server.routes

import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.flow.FlowValidator
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.Roles
import com.example.knowhub.core.security.Acl
import com.example.knowhub.server.AppContainer
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
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.flowRoutes(container: AppContainer) {
    route(ApiRoutes.FLOWS) {
        get {
            val user = container.authContext.require(call)
            val dto = user.toDto()
            val all = container.store.snapshot().flows
            val visible = if (Roles.ADMIN in user.roles) {
                all
            } else {
                all.filter { it.enabled && Acl.canRunFlow(it.audience, dto) }
            }
            call.respond(visible)
        }

        post {
            val admin = container.authContext.requireAdmin(call)
            val incoming = call.receive<FlowTemplate>()
            val errors = FlowValidator.validate(incoming)
            if (errors.isNotEmpty()) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, "FLOW_INVALID", errors.joinToString("；"))
            }
            if (container.store.snapshot().flowById(incoming.flowId) != null) {
                throw ApiException(HttpStatusCode.Conflict, "FLOW_EXISTS", "flowId 已存在：${incoming.flowId}")
            }
            val now = System.currentTimeMillis()
            val template = incoming.copy(version = 1, createdAt = now, updatedAt = now)
            container.store.mutate { db -> db.copy(flows = db.flows + template) to Unit }
            container.audit.record(admin, "FLOW_CREATE", "flow", template.flowId, terminal = call.terminal(), ip = call.clientIp())
            call.respond(HttpStatusCode.Created, template)
        }

        put("{flowId}") {
            val admin = container.authContext.requireAdmin(call)
            val flowId = call.parameters["flowId"] ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "缺少 flowId")
            val existing = container.store.snapshot().flowById(flowId)
                ?: throw ApiException(HttpStatusCode.NotFound, "FLOW_NOT_FOUND", "流程不存在")
            val incoming = call.receive<FlowTemplate>()
            val updated = incoming.copy(
                flowId = flowId,
                version = existing.version + 1,
                createdAt = existing.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
            val errors = FlowValidator.validate(updated)
            if (errors.isNotEmpty()) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, "FLOW_INVALID", errors.joinToString("；"))
            }
            container.store.mutate { db ->
                db.copy(flows = db.flows.map { if (it.flowId == flowId) updated else it }) to Unit
            }
            container.audit.record(admin, "FLOW_UPDATE", "flow", flowId, terminal = call.terminal(), ip = call.clientIp())
            call.respond(updated)
        }

        delete("{flowId}") {
            val admin = container.authContext.requireAdmin(call)
            val flowId = call.parameters["flowId"] ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "缺少 flowId")
            if (container.store.snapshot().flowById(flowId) == null) {
                throw ApiException(HttpStatusCode.NotFound, "FLOW_NOT_FOUND", "流程不存在")
            }
            container.store.mutate { db -> db.copy(flows = db.flows.filterNot { it.flowId == flowId }) to Unit }
            container.audit.record(admin, "FLOW_DELETE", "flow", flowId, terminal = call.terminal(), ip = call.clientIp())
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
