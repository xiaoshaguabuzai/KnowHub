package com.example.knowhub.server.audit

import com.example.knowhub.core.model.AuditEntry
import com.example.knowhub.core.util.newId
import com.example.knowhub.server.store.DataStore
import com.example.knowhub.server.store.UserAccount

/**
 * 审计：登录、上传、删除、配置变更、工具调用、问答、反馈全部留痕。
 * 生产环境应改为 append-only 存储（WORM/只读库），MVP 先保证字段齐全。
 */
class AuditService(private val store: DataStore) {

    fun record(
        actor: UserAccount,
        action: String,
        targetType: String,
        targetId: String,
        detail: String = "",
        terminal: String = "",
        ip: String = "",
    ) {
        store.mutate { db ->
            val entry = AuditEntry(
                auditId = newId("aud"),
                actorId = actor.userId,
                actorName = actor.displayName,
                action = action,
                targetType = targetType,
                targetId = targetId,
                detail = detail.take(500),
                terminal = terminal,
                ip = ip,
                createdAt = System.currentTimeMillis(),
            )
            db.copy(audit = (db.audit + entry).takeLast(MAX_ENTRIES)) to Unit
        }
    }

    companion object {
        private const val MAX_ENTRIES = 2000
    }
}
