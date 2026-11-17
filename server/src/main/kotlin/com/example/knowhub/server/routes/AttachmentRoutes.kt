package com.example.knowhub.server.routes

import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.server.AppContainer
import com.example.knowhub.server.support.ApiException
import com.example.knowhub.server.support.clientIp
import com.example.knowhub.server.support.terminal
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.toByteArray

private const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024

/**
 * 会话附件上传：用于"把检验单 / 报告 / 文件带进对话"。
 * 文本类与 PDF 会抽取正文；图片当前只保存并标记为未识别（端侧可先 OCR 再输入）。
 */
fun Route.attachmentRoutes(container: AppContainer) {
    post(ApiRoutes.ATTACHMENTS) {
        val user = container.authContext.require(call)
        var fileName = "attachment.bin"
        var mime = "application/octet-stream"
        var bytes: ByteArray? = null

        call.receiveMultipart(formFieldLimit = MAX_ATTACHMENT_BYTES).forEachPart { part ->
            if (part is PartData.FileItem) {
                fileName = part.originalFileName?.takeIf { it.isNotBlank() } ?: fileName
                mime = part.contentType?.toString() ?: mime
                bytes = part.provider().toByteArray()
            }
            part.dispose()
        }

        val data = bytes ?: throw ApiException(HttpStatusCode.BadRequest, "NO_FILE", "请上传文件")
        if (data.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "EMPTY_FILE", "文件内容为空")
        if (data.size > MAX_ATTACHMENT_BYTES) {
            throw ApiException(HttpStatusCode.PayloadTooLarge, "FILE_TOO_LARGE", "附件不能超过 10MB")
        }

        val dto = container.attachments.register(user.userId, fileName, mime, data)
        container.audit.record(
            actor = user,
            action = "ATTACHMENT_UPLOAD",
            targetType = "attachment",
            targetId = dto.attachmentId,
            detail = "${dto.name} (${dto.sizeBytes} bytes, text=${dto.hasText})",
            terminal = call.terminal(),
            ip = call.clientIp(),
        )
        call.respond(HttpStatusCode.Created, dto)
    }
}
