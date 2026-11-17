package com.example.knowhub.server.seed

import com.example.knowhub.core.api.AppDefaults
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.DocumentStatus
import com.example.knowhub.core.model.FlowNode
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.FlowVariable
import com.example.knowhub.core.model.NodeConfig
import com.example.knowhub.core.model.NodeTypes
import com.example.knowhub.core.model.RagConfig
import com.example.knowhub.core.model.Roles
import com.example.knowhub.core.text.Chunker
import com.example.knowhub.server.security.AuthService
import com.example.knowhub.server.store.DataStore
import com.example.knowhub.server.store.DocumentStorage
import com.example.knowhub.server.store.UserAccount

/**
 * 首次启动种子数据：医院演示账号、三甲医院制度库（见 [HOSPITAL_POLICIES]）、3 条院内业务流程。
 *
 * 访问控制：制度可绑定角色（人事科 / 医务处），检索与查看阶段均强制过滤。
 */
object SeedData {

    fun seedIfEmpty(store: DataStore, auth: AuthService, documents: DocumentStorage) {
        if (store.snapshot().users.isNotEmpty()) return
        val now = System.currentTimeMillis()
        val rag = RagConfig()

        val users = listOf(
            account(auth, "u_admin", AppDefaults.ADMIN_USER, "医务处管理员", listOf(Roles.ADMIN), "admin1234", now),
            account(auth, "u_demo", AppDefaults.DEMO_USER, "张医生（内科）", listOf(Roles.MEMBER), "demo1234", now),
            account(auth, "u_hr", "hr", "人事科", listOf(Roles.MEMBER, Roles.HR), "hr1234", now),
            account(
                auth,
                "u_patient",
                AppDefaults.PATIENT_USER,
                "王女士（患者）",
                listOf(Roles.PATIENT),
                AppDefaults.PATIENT_PASSWORD,
                now,
            ),
        )

        val docs = ArrayList<DocumentDto>()
        val chunks = ArrayList<com.example.knowhub.core.model.Chunk>()
        for (policy in HOSPITAL_POLICIES + PATIENT_GUIDES) {
            val docChunks = Chunker.chunk(policy.text, policy.id, policy.name, rag, page = 1)
            docs.add(
                DocumentDto(
                    docId = policy.id,
                    name = policy.name,
                    mime = "text/markdown",
                    sizeBytes = policy.text.toByteArray().size.toLong(),
                    status = DocumentStatus.INDEXED,
                    pageCount = 1,
                    chunkCount = docChunks.size,
                    createdAt = now,
                    updatedAt = now,
                    version = 1,
                    tenantId = "default",
                    ownerId = "u_admin",
                    aclRoles = policy.aclRoles,
                    audience = policy.audience,
                    docNo = policy.docNo,
                    department = policy.department,
                    effectiveDate = policy.effectiveDate,
                )
            )
            chunks.addAll(docChunks)
            // 保存原文，供「查看文件」按原文阅读（切块会丢标题层级）
            documents.save(policy.id, policy.text)
        }

        val flows = listOf(admissionFlow(now), criticalValueFlow(now), repairFlow(now), appointmentFlow(now))

        store.mutate { db ->
            db.copy(
                users = users,
                documents = docs,
                chunks = chunks,
                flows = flows,
                rag = rag,
            ) to Unit
        }
    }

    private fun account(
        auth: AuthService,
        id: String,
        username: String,
        displayName: String,
        roles: List<String>,
        password: String,
        now: Long,
    ): UserAccount {
        val (hash, salt) = auth.hashPassword(password)
        return UserAccount(
            userId = id,
            username = username,
            displayName = displayName,
            roles = roles,
            tenantId = "default",
            passwordHash = hash,
            salt = salt,
            active = true,
            createdAt = now,
        )
    }

    private fun admissionFlow(now: Long): FlowTemplate = FlowTemplate(
        flowId = "flow_admission",
        name = "住院登记协助",
        description = "收集患者姓名、住院证号与收治科室，提交住院处分配床位",
        triggerKeywords = listOf("住院登记", "办理住院", "收治入院", "开住院证"),
        version = 1,
        startNodeId = "n1",
        createdAt = now,
        updatedAt = now,
        variables = mapOf(
            "patientName" to FlowVariable("string", "患者姓名"),
            "admissionNo" to FlowVariable("string", "住院证号"),
            "dept" to FlowVariable("string", "收治科室"),
        ),
        nodes = listOf(
            FlowNode(
                nodeId = "n1",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "患者姓名",
                config = NodeConfig(prompt = "好的，我来协助办理住院登记。请提供患者姓名：", variable = "patientName"),
                nextNodeId = "n2",
            ),
            FlowNode(
                nodeId = "n2",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "住院证号",
                config = NodeConfig(prompt = "请提供住院证号（例如 ZY20261117001）：", variable = "admissionNo"),
                nextNodeId = "n3",
            ),
            FlowNode(
                nodeId = "n3",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "收治科室",
                config = NodeConfig(prompt = "请提供收治科室（例如 心内科）：", variable = "dept"),
                nextNodeId = "n4",
            ),
            FlowNode(
                nodeId = "n4",
                nodeType = NodeTypes.TOOL_INVOKE,
                name = "提交住院处",
                config = NodeConfig(
                    toolId = "create_admission_request",
                    args = mapOf(
                        "patientName" to "\${patientName}",
                        "admissionNo" to "\${admissionNo}",
                        "dept" to "\${dept}",
                    ),
                    resultVariable = "admission",
                ),
                nextNodeId = "n5",
            ),
            FlowNode(
                nodeId = "n5",
                nodeType = NodeTypes.MESSAGE,
                name = "登记回执",
                config = NodeConfig(text = "住院登记已提交：\${admission}\n请提醒患者 30 分钟内到住院处办理手续。"),
            ),
        ),
    )

    private fun criticalValueFlow(now: Long): FlowTemplate = FlowTemplate(
        flowId = "flow_critical_value",
        name = "检验危急值上报",
        description = "登记住院号、检验项目与危急值，自动上报主管医师与检验科",
        triggerKeywords = listOf("上报危急值", "危急值登记"),
        version = 1,
        startNodeId = "n1",
        createdAt = now,
        updatedAt = now,
        nodes = listOf(
            FlowNode(
                nodeId = "n1",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "住院号",
                config = NodeConfig(prompt = "请提供患者住院号：", variable = "patientId"),
                nextNodeId = "n2",
            ),
            FlowNode(
                nodeId = "n2",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "检验项目",
                config = NodeConfig(prompt = "请提供检验或检查项目（例如 血钾）：", variable = "item"),
                nextNodeId = "n3",
            ),
            FlowNode(
                nodeId = "n3",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "危急值",
                config = NodeConfig(prompt = "请提供危急值结果（例如 6.8 mmol/L）：", variable = "value"),
                nextNodeId = "n4",
            ),
            FlowNode(
                nodeId = "n4",
                nodeType = NodeTypes.TOOL_INVOKE,
                name = "上报",
                config = NodeConfig(
                    toolId = "report_critical_value",
                    args = mapOf(
                        "patientId" to "\${patientId}",
                        "item" to "\${item}",
                        "value" to "\${value}",
                    ),
                    resultVariable = "report",
                ),
                nextNodeId = "n5",
            ),
            FlowNode(
                nodeId = "n5",
                nodeType = NodeTypes.MESSAGE,
                name = "上报回执",
                config = NodeConfig(text = "危急值已上报：\${report}\n请 10 分钟内电话通知主管医师，并在病程记录中登记处置。"),
            ),
        ),
    )

    private fun repairFlow(now: Long): FlowTemplate = FlowTemplate(
        flowId = "flow_repair",
        name = "医疗设备报修",
        description = "登记设备编号与故障现象，自动创建设备科工单",
        triggerKeywords = listOf("设备报修", "设备故障", "报修"),
        version = 1,
        startNodeId = "n1",
        createdAt = now,
        updatedAt = now,
        nodes = listOf(
            FlowNode(
                nodeId = "n1",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "设备编号",
                config = NodeConfig(prompt = "请提供设备编号或名称（例如 监护仪 ICU-03）：", variable = "deviceId"),
                nextNodeId = "n2",
            ),
            FlowNode(
                nodeId = "n2",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "故障现象",
                config = NodeConfig(prompt = "请描述故障现象：", variable = "issue"),
                nextNodeId = "n3",
            ),
            FlowNode(
                nodeId = "n3",
                nodeType = NodeTypes.TOOL_INVOKE,
                name = "创建工单",
                config = NodeConfig(
                    toolId = "create_repair_ticket",
                    args = mapOf("deviceId" to "\${deviceId}", "issue" to "\${issue}"),
                    resultVariable = "repair",
                ),
                nextNodeId = "n4",
            ),
            FlowNode(
                nodeId = "n4",
                nodeType = NodeTypes.MESSAGE,
                name = "报修回执",
                config = NodeConfig(text = "\${repair}"),
            ),
        ),
    )

    /** 患者可用的门诊预约流程：科室 → 日期 → 手机号 → 预约号。 */
    private fun appointmentFlow(now: Long): FlowTemplate = FlowTemplate(
        flowId = "flow_appointment",
        name = "门诊预约登记",
        description = "收集就诊人、科室、日期与手机号，生成门诊预约号",
        triggerKeywords = listOf("预约门诊", "门诊预约", "挂号", "预约挂号"),
        audience = "all",
        version = 1,
        startNodeId = "n1",
        createdAt = now,
        updatedAt = now,
        nodes = listOf(
            FlowNode(
                nodeId = "n1",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "就诊人",
                config = NodeConfig(prompt = "好的，我来帮你预约门诊。请问就诊人姓名：", variable = "patientName"),
                nextNodeId = "n2",
            ),
            FlowNode(
                nodeId = "n2",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "就诊科室",
                config = NodeConfig(prompt = "想看哪个科室？（例如 心内科、骨科、皮肤科）：", variable = "dept"),
                nextNodeId = "n3",
            ),
            FlowNode(
                nodeId = "n3",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "就诊日期",
                config = NodeConfig(prompt = "计划哪天就诊？请按 2026-11-20 的格式输入：", variable = "date"),
                nextNodeId = "n4",
            ),
            FlowNode(
                nodeId = "n4",
                nodeType = NodeTypes.COLLECT_PARAM,
                name = "手机号",
                config = NodeConfig(prompt = "请留一个手机号，用于接收预约通知：", variable = "phone"),
                nextNodeId = "n5",
            ),
            FlowNode(
                nodeId = "n5",
                nodeType = NodeTypes.TOOL_INVOKE,
                name = "提交预约",
                config = NodeConfig(
                    toolId = "create_appointment",
                    args = mapOf(
                        "patientName" to "\${patientName}",
                        "dept" to "\${dept}",
                        "date" to "\${date}",
                        "phone" to "\${phone}",
                    ),
                    resultVariable = "appointment",
                ),
                nextNodeId = "n6",
            ),
            FlowNode(
                nodeId = "n6",
                nodeType = NodeTypes.MESSAGE,
                name = "预约回执",
                config = NodeConfig(text = "\${appointment}"),
            ),
        ),
    )
}
