package com.example.knowhub.server.routes

import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.model.DocumentDetailDto
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.DocumentStatus
import com.example.knowhub.core.model.TextDocumentRequest
import com.example.knowhub.core.security.Acl
import com.example.knowhub.core.util.newId
import com.example.knowhub.server.AppContainer
import com.example.knowhub.server.support.ApiException
import com.example.knowhub.server.support.clientIp
import com.example.knowhub.server.support.terminal
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.utils.io.toByteArray

fun Route.documentRoutes(container: AppContainer) {
    route(ApiRoutes.DOCUMENTS) {
        get {
            val user = container.authContext.require(call)
            val dto = user.toDto()
            val documents = container.store.snapshot().documents
                .filter { Acl.canRead(it, dto) }
                .sortedByDescending { it.updatedAt }
            call.respond(documents)
        }

        post {
            val admin = container.authContext.requireAdmin(call)
            var fileName = "upload.bin"
            var bytes: ByteArray? = null

            call.receiveMultipart(formFieldLimit = container.config.maxUploadBytes).forEachPart { part ->
                if (part is PartData.FileItem) {
                    fileName = part.originalFileName?.takeIf { it.isNotBlank() } ?: fileName
                    bytes = part.provider().toByteArray()
                }
                part.dispose()
            }

            val data = bytes ?: throw ApiException(HttpStatusCode.BadRequest, "NO_FILE", "请上传文件")
            if (data.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "EMPTY_FILE", "文件内容为空")
            if (data.size > container.config.maxUploadBytes) {
                throw ApiException(HttpStatusCode.PayloadTooLarge, "FILE_TOO_LARGE", "文件超过大小限制")
            }

            val now = System.currentTimeMillis()
            val doc = DocumentDto(
                docId = newId("doc"),
                name = fileName,
                mime = mimeOf(fileName),
                sizeBytes = data.size.toLong(),
                status = DocumentStatus.PARSING,
                createdAt = now,
                updatedAt = now,
                ownerId = admin.userId,
            )
            container.store.mutate { db -> db.copy(documents = db.documents + doc) to Unit }
            container.ingest.submitFile(doc, fileName, data)
            container.audit.record(
                admin, "DOCUMENT_UPLOAD", "document", doc.docId,
                detail = "$fileName (${data.size} bytes)",
                terminal = call.terminal(), ip = call.clientIp(),
            )
            call.respond(HttpStatusCode.Accepted, doc)
        }

        post("/text") {
            val admin = container.authContext.requireAdmin(call)
            val request = call.receive<TextDocumentRequest>()
            if (request.name.isBlank() || request.content.isBlank()) {
                throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "name 与 content 不能为空")
            }
            val now = System.currentTimeMillis()
            val doc = DocumentDto(
                docId = newId("doc"),
                name = request.name.trim(),
                mime = "text/markdown",
                sizeBytes = request.content.toByteArray().size.toLong(),
                status = DocumentStatus.PARSING,
                createdAt = now,
                updatedAt = now,
                ownerId = admin.userId,
                aclRoles = request.aclRoles,
            )
            container.store.mutate { db -> db.copy(documents = db.documents + doc) to Unit }
            container.ingest.submitText(doc, request.content)
            container.audit.record(
                admin, "DOCUMENT_TEXT", "document", doc.docId,
                detail = request.name,
                terminal = call.terminal(), ip = call.clientIp(),
            )
            call.respond(HttpStatusCode.Accepted, doc)
        }

        get("{docId}") {
            val user = container.authContext.require(call)
            val docId = call.parameters["docId"] ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "缺少 docId")
            val doc = container.store.snapshot().documentById(docId)
                ?: throw ApiException(HttpStatusCode.NotFound, "DOCUMENT_NOT_FOUND", "文档不存在")
            if (!Acl.canRead(doc, user.toDto())) {
                throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "无权查看该文档")
            }
            val stored = container.documentStorage.read(docId)
            val fromCache = stored != null
            val content = stored ?: container.store.snapshot().chunks
                .filter { it.docId == docId }
                .sortedWith(compareBy({ it.page }, { it.index }))
                .joinToString("\n\n") { it.text }
            val truncated = content.length > MAX_VIEW_CHARS
            call.respond(
                DocumentDetailDto(
                    doc = doc,
                    content = if (truncated) content.take(MAX_VIEW_CHARS) else content,
                    truncated = truncated,
                    fromCache = fromCache,
                )
            )
        }

        delete("{docId}") {
            val admin = container.authContext.requireAdmin(call)
            val docId = call.parameters["docId"] ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "缺少 docId")
            val doc = container.store.snapshot().documentById(docId)
                ?: throw ApiException(HttpStatusCode.NotFound, "DOCUMENT_NOT_FOUND", "文档不存在")
            container.store.mutate { db ->
                db.copy(
                    documents = db.documents.filterNot { it.docId == docId },
                    chunks = db.chunks.filterNot { it.docId == docId },
                ) to Unit
            }
            container.documentStorage.delete(docId)
            container.audit.record(
                admin, "DOCUMENT_DELETE", "document", docId,
                detail = doc.name,
                terminal = call.terminal(), ip = call.clientIp(),
            )
            call.respond(HttpStatusCode.NoContent)
        }

        post("{docId}/reindex") {
            val admin = container.authContext.requireAdmin(call)
            val docId = call.parameters["docId"] ?: throw ApiException(HttpStatusCode.BadRequest, "BAD_REQUEST", "缺少 docId")
            val doc = container.store.snapshot().documentById(docId)
                ?: throw ApiException(HttpStatusCode.NotFound, "DOCUMENT_NOT_FOUND", "文档不存在")
            container.store.mutate { db ->
                db.copy(documents = db.documents.map {
                    if (it.docId == docId) it.copy(status = DocumentStatus.PARSING, updatedAt = System.currentTimeMillis()) else it
                }) to Unit
            }
            container.ingest.reindex(doc)
            container.audit.record(
                admin, "DOCUMENT_REINDEX", "document", docId,
                terminal = call.terminal(), ip = call.clientIp(),
            )
            call.respond(HttpStatusCode.Accepted, doc.copy(status = DocumentStatus.PARSING))
        }
    }
}

private const val MAX_VIEW_CHARS = 200_000

private fun mimeOf(fileName: String): String {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".pdf") -> "application/pdf"
        lower.endsWith(".md") -> "text/markdown"
        lower.endsWith(".csv") -> "text/csv"
        lower.endsWith(".json") -> "application/json"
        else -> "text/plain"
    }
}
