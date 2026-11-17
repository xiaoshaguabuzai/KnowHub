package com.example.knowhub.server.tools

import com.example.knowhub.core.model.ToolInfo
import com.example.knowhub.core.model.ToolParam
import com.example.knowhub.core.model.ToolResult
import com.example.knowhub.core.util.newId
import com.example.knowhub.server.store.UserAccount

/**
 * 院内工具注册表：白名单 + 参数校验 + 确定性返回。
 * 生产环境可替换为 HIS / 集成平台 / MCP，接口保持不变。
 */
class ToolRegistry {

    val definitions: List<ToolInfo> = listOf(
        ToolInfo(
            toolId = "create_admission_request",
            name = "提交住院登记",
            description = "向住院处提交住院登记，等待床位分配（演示工具）",
            params = listOf(
                ToolParam("patientName", "string", description = "患者姓名"),
                ToolParam("admissionNo", "string", description = "住院证号"),
                ToolParam("dept", "string", description = "收治科室"),
            ),
        ),
        ToolInfo(
            toolId = "report_critical_value",
            name = "上报危急值",
            description = "将检验/检查危急值上报主管医师与检验科（演示工具）",
            params = listOf(
                ToolParam("patientId", "string", description = "住院号"),
                ToolParam("item", "string", description = "检验/检查项目"),
                ToolParam("value", "string", description = "危急值结果"),
            ),
        ),
        ToolInfo(
            toolId = "create_repair_ticket",
            name = "设备报修工单",
            description = "为医疗设备创建报修工单，设备科响应（演示工具）",
            params = listOf(
                ToolParam("deviceId", "string", description = "设备编号或名称"),
                ToolParam("issue", "string", description = "故障现象"),
            ),
        ),
        ToolInfo(
            toolId = "create_appointment",
            name = "门诊预约登记",
            description = "为患者登记门诊预约，返回预约号与取号提示（演示工具）",
            params = listOf(
                ToolParam("patientName", "string", description = "就诊人姓名"),
                ToolParam("dept", "string", description = "就诊科室"),
                ToolParam("date", "string", description = "就诊日期（YYYY-MM-DD）"),
                ToolParam("phone", "string", description = "手机号"),
            ),
        ),
    )

    fun invoke(toolId: String, args: Map<String, String>, user: UserAccount): ToolResult {
        if (definitions.none { it.toolId == toolId }) {
            return ToolResult(ok = false, message = "工具未注册：$toolId")
        }
        return when (toolId) {
            "create_admission_request" -> createAdmission(args, user)
            "report_critical_value" -> reportCriticalValue(args, user)
            "create_repair_ticket" -> createRepairTicket(args)
            "create_appointment" -> createAppointment(args, user)
            else -> ToolResult(ok = false, message = "工具未实现：$toolId")
        }
    }

    private fun createAdmission(args: Map<String, String>, user: UserAccount): ToolResult {
        val patientName = args["patientName"].orEmpty().trim()
        val admissionNo = args["admissionNo"].orEmpty().trim()
        val dept = args["dept"].orEmpty().trim()
        if (patientName.length < 2) return ToolResult(ok = false, message = "患者姓名不完整：$patientName")
        if (!Regex("^[A-Za-z0-9-]{6,24}$").matches(admissionNo)) {
            return ToolResult(ok = false, message = "住院证号格式不正确（示例：ZY20261117001）：$admissionNo")
        }
        if (dept.isBlank()) return ToolResult(ok = false, message = "收治科室不能为空")

        val requestId = "ADM-" + newId("", 6).removePrefix("_").uppercase()
        val data = mapOf(
            "requestId" to requestId,
            "patientName" to patientName,
            "admissionNo" to admissionNo,
            "dept" to dept,
            "status" to "WAITING_BED",
            "operator" to user.displayName,
        )
        return ToolResult(
            ok = true,
            data = data,
            message = "住院登记 $requestId 已提交：$patientName（$admissionNo）收入 $dept，状态「待床位分配」，住院处将在 30 分钟内联系病区。",
        )
    }

    private fun reportCriticalValue(args: Map<String, String>, user: UserAccount): ToolResult {
        val patientId = args["patientId"].orEmpty().trim()
        val item = args["item"].orEmpty().trim()
        val value = args["value"].orEmpty().trim()
        if (patientId.isBlank()) return ToolResult(ok = false, message = "住院号不能为空")
        if (item.isBlank()) return ToolResult(ok = false, message = "检验项目不能为空")
        if (value.isBlank()) return ToolResult(ok = false, message = "危急值结果不能为空")

        val reportId = "CV-" + newId("", 6).removePrefix("_").uppercase()
        val data = mapOf(
            "reportId" to reportId,
            "patientId" to patientId,
            "item" to item,
            "value" to value,
            "notified" to "主管医师 / 检验科 / 值班护士",
            "reporter" to user.displayName,
        )
        return ToolResult(
            ok = true,
            data = data,
            message = "危急值 $reportId 已上报：$patientId $item = $value，已通知主管医师、检验科与值班护士，请 10 分钟内完成电话确认并记录处置。",
        )
    }

    private fun createAppointment(args: Map<String, String>, user: UserAccount): ToolResult {
        val patientName = args["patientName"].orEmpty().trim()
        val dept = args["dept"].orEmpty().trim()
        val date = args["date"].orEmpty().trim()
        val phone = args["phone"].orEmpty().trim()
        if (patientName.length < 2) return ToolResult(ok = false, message = "就诊人姓名不完整：$patientName")
        if (dept.isBlank()) return ToolResult(ok = false, message = "就诊科室不能为空")
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) {
            return ToolResult(ok = false, message = "就诊日期格式应为 YYYY-MM-DD：$date")
        }
        if (!Regex("^1\\d{10}$").matches(phone)) {
            return ToolResult(ok = false, message = "手机号格式不正确：$phone")
        }

        val appointmentId = "APT-" + newId("", 6).removePrefix("_").uppercase()
        val data = mapOf(
            "appointmentId" to appointmentId,
            "patientName" to patientName,
            "dept" to dept,
            "date" to date,
            "phone" to phone,
            "status" to "RESERVED",
            "operator" to user.displayName,
        )
        return ToolResult(
            ok = true,
            data = data,
            message = "门诊预约 $appointmentId 已登记：$patientName，$date $dept，手机 $phone。请提前 15 分钟到门诊自助机取号；如需取消请提前一天在公众号操作。",
        )
    }

    private fun createRepairTicket(args: Map<String, String>): ToolResult {
        val deviceId = args["deviceId"].orEmpty().trim()
        val issue = args["issue"].orEmpty().trim()
        if (deviceId.isBlank()) return ToolResult(ok = false, message = "设备编号不能为空")
        if (issue.isBlank()) return ToolResult(ok = false, message = "故障现象不能为空")

        val ticketId = "RP-" + newId("", 6).removePrefix("_").uppercase()
        return ToolResult(
            ok = true,
            data = mapOf("ticketId" to ticketId, "deviceId" to deviceId, "status" to "OPEN"),
            message = "设备报修工单 $ticketId 已创建（$deviceId：$issue），设备科将在 2 小时内响应。",
        )
    }
}
