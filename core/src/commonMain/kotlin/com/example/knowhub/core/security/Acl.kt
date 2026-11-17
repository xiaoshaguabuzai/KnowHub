package com.example.knowhub.core.security

import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.Roles
import com.example.knowhub.core.model.UserDto

/**
 * 文档 / 流程访问控制。
 *
 * 判定顺序：
 * 1. 租户隔离；
 * 2. 管理员可见全部（运维与质控需要）；
 * 3. 显式角色白名单（如薪酬仅人事科）；
 * 4. audience=all 的患者指南对患者开放；
 * 5. 其余内部制度仅职工可见。
 */
object Acl {

    const val AUDIENCE_STAFF = "staff"
    const val AUDIENCE_ALL = "all"

    fun isPatient(user: UserDto): Boolean = user.roles.contains(Roles.PATIENT)

    fun canRead(doc: DocumentDto?, user: UserDto): Boolean {
        if (doc == null) return false
        if (doc.tenantId != user.tenantId) return false
        if (user.roles.contains(Roles.ADMIN)) return true
        if (doc.aclRoles.isNotEmpty()) return user.roles.any { doc.aclRoles.contains(it) }
        if (doc.audience == AUDIENCE_ALL) return true
        return !isPatient(user)
    }

    fun canRunFlow(audience: String, user: UserDto): Boolean =
        audience == AUDIENCE_ALL || !isPatient(user)

    /** 文档可见范围的中文描述，用于管理端展示。 */
    fun describe(doc: DocumentDto): String = when {
        doc.aclRoles.isNotEmpty() -> "仅限：" + doc.aclRoles.joinToString("/")
        doc.audience == AUDIENCE_ALL -> "患者与职工可见"
        else -> "仅职工可见"
    }
}
