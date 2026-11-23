package com.example.knowhub.local

import com.example.knowhub.core.model.ToolResult
import com.example.knowhub.core.util.newId
import kotlin.random.Random

/** 直连模式的本地工具实现：与中台演示工具保持同一套入参、校验与回执格式。 */
object LocalTools {

    fun invoke(toolId: String, args: Map<String, String>): ToolResult = when (toolId) {
        "create_admission_request" -> createAdmission(args)
        "report_critical_value" -> reportCriticalValue(args)
        "create_repair_ticket" -> createRepairTicket(args)
        "create_appointment" -> createAppointment(args)
        else -> ToolResult(ok = false, message = "工具未注册：$toolId")
    }

    private fun code(prefix: String): String =
        prefix + "-" + buildString {
            repeat(6) { append("0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"[Random.nextInt(34)]) }
        }

    private fun createAdmission(args: Map<String, String>): ToolResult {
        val patientName = args["patientName"].orEmpty().trim()
        val admissionNo = args["admissionNo"].orEmpty().trim()
        val dept = args["dept"].orEmpty().trim()
        if (patientName.length < 2) return ToolResult(false, message = "患者姓名不完整：$patientName")
        if (!Regex("^[A-Za-z0-9-]{6,24}$").matches(admissionNo)) {
            return ToolResult(false, message = "住院证号格式不正确（示例：ZY20261117001）：$admissionNo")
        }
        if (dept.isBlank()) return ToolResult(false, message = "收治科室不能为空")
        val requestId = code("ADM")
        return ToolResult(
            ok = true,
            data = mapOf(
                "requestId" to requestId,
                "patientName" to patientName,
                "admissionNo" to admissionNo,
                "dept" to dept,
                "status" to "WAITING_BED",
            ),
            message = "住院登记 $requestId 已提交：$patientName（$admissionNo）收入 $dept，状态「待床位分配」，住院处将在 30 分钟内联系病区。",
        )
    }

    private fun reportCriticalValue(args: Map<String, String>): ToolResult {
        val patientId = args["patientId"].orEmpty().trim()
        val item = args["item"].orEmpty().trim()
        val value = args["value"].orEmpty().trim()
        if (patientId.isBlank()) return ToolResult(false, message = "住院号不能为空")
        if (item.isBlank()) return ToolResult(false, message = "检验项目不能为空")
        if (value.isBlank()) return ToolResult(false, message = "危急值结果不能为空")
        val reportId = code("CV")
        return ToolResult(
            ok = true,
            data = mapOf("reportId" to reportId, "patientId" to patientId, "item" to item, "value" to value),
            message = "危急值 $reportId 已上报：$patientId $item = $value，已通知主管医师、检验科与值班护士，请 10 分钟内完成电话确认并记录处置。",
        )
    }

    private fun createRepairTicket(args: Map<String, String>): ToolResult {
        val deviceId = args["deviceId"].orEmpty().trim()
        val issue = args["issue"].orEmpty().trim()
        if (deviceId.isBlank()) return ToolResult(false, message = "设备编号不能为空")
        if (issue.isBlank()) return ToolResult(false, message = "故障现象不能为空")
        val ticketId = code("RP")
        return ToolResult(
            ok = true,
            data = mapOf("ticketId" to ticketId, "deviceId" to deviceId, "status" to "OPEN"),
            message = "设备报修工单 $ticketId 已创建（$deviceId：$issue），设备科将在 2 小时内响应。",
        )
    }

    private fun createAppointment(args: Map<String, String>): ToolResult {
        val patientName = args["patientName"].orEmpty().trim()
        val dept = args["dept"].orEmpty().trim()
        val date = args["date"].orEmpty().trim()
        val phone = args["phone"].orEmpty().trim()
        if (patientName.length < 2) return ToolResult(false, message = "就诊人姓名不完整：$patientName")
        if (dept.isBlank()) return ToolResult(false, message = "就诊科室不能为空")
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date)) {
            return ToolResult(false, message = "就诊日期格式应为 YYYY-MM-DD：$date")
        }
        if (!Regex("^1\\d{10}$").matches(phone)) {
            return ToolResult(false, message = "手机号格式不正确：$phone")
        }
        val appointmentId = code("APT")
        return ToolResult(
            ok = true,
            data = mapOf(
                "appointmentId" to appointmentId,
                "patientName" to patientName,
                "dept" to dept,
                "date" to date,
                "phone" to phone,
            ),
            message = "门诊预约 $appointmentId 已登记：$patientName，$date $dept，手机 $phone。请提前 15 分钟到门诊自助机取号；如需取消请提前一天在公众号操作。",
        )
    }
}
