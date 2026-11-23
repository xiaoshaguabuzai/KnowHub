package com.example.knowhub.local

import com.example.knowhub.core.model.AttachmentDto
import com.example.knowhub.core.model.MessageRoles
import com.example.knowhub.core.model.Roles
import com.example.knowhub.core.model.SourceRef
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalAgentTest {

    private fun agent(workspace: LocalWorkspace, answer: String = "本地模型回答") =
        LocalAgent(workspace) { flowOf(answer) }

    @Test
    fun patientGuideUsesLocalRetrievalAndSources() = runBlocking {
        val workspace = LocalWorkspace()
        val user = workspace.userFor(Roles.PATIENT)
        val session = workspace.newSession()
        val question = "抽血前能吃饭吗？"
        workspace.appendMessage(session.sessionId, MessageRoles.USER, question)

        val sources = ArrayList<List<SourceRef>>()
        val answer = agent(workspace).respond(
            sessionId = session.sessionId,
            user = user,
            text = question,
            attachments = emptyList(),
            onDelta = {},
            onSources = { sources += it },
            onStep = {},
        )

        assertTrue(answer.startsWith("本地模型回答"), "回答应来自模型：$answer")
        assertTrue(answer.contains("及时就医"), "患者回答应带就医提示")
        assertTrue(sources.first().any { it.docName.contains("检验") }, "应命中本地检验指南")
        assertTrue(workspace.session(session.sessionId)!!.messages.last().sources.isNotEmpty())
        assertTrue(answer.isNotBlank())
    }

    @Test
    fun staffPolicyRetrievalWorks() = runBlocking {
        val workspace = LocalWorkspace()
        val user = workspace.userFor(Roles.MEMBER)
        val session = workspace.newSession()
        val question = "危急值要在多久内上报？"
        workspace.appendMessage(session.sessionId, MessageRoles.USER, question)

        val sources = ArrayList<List<SourceRef>>()
        agent(workspace).respond(session.sessionId, user, question, emptyList(), {}, { sources += it }, {})
        assertTrue(sources.first().any { it.docName.contains("危急值") }, "应命中危急值制度")
    }

    @Test
    fun patientAppointmentFlowCompletes() = runBlocking {
        val workspace = LocalWorkspace()
        val user = workspace.userFor(Roles.PATIENT)
        val session = workspace.newSession()
        val reply = agent(workspace)

        val ask = reply.respond(session.sessionId, user, "我要预约门诊", emptyList(), {}, {}, {})
        assertTrue(ask.contains("姓名"), "应进入姓名收集：$ask")

        listOf("Wang", "IM", "2026-11-20", "13800001234").forEach { input ->
            reply.respond(session.sessionId, user, input, emptyList(), {}, {}, {})
        }
        val last = workspace.session(session.sessionId)!!.messages.last().content
        assertTrue(last.contains("APT-"), "应生成预约号：$last")
    }

    @Test
    fun staffAdmissionFlowCompletes() = runBlocking {
        val workspace = LocalWorkspace()
        val user = workspace.userFor(Roles.MEMBER)
        val session = workspace.newSession()
        val reply = agent(workspace)

        reply.respond(session.sessionId, user, "我要办理住院登记", emptyList(), {}, {}, {})
        listOf("李建国", "ZY20261117001", "心内科").forEach { input ->
            reply.respond(session.sessionId, user, input, emptyList(), {}, {}, {})
        }
        val last = workspace.session(session.sessionId)!!.messages.last().content
        assertTrue(last.contains("ADM-"), "应生成住院登记号：$last")
    }

    @Test
    fun attachmentTextIsSentToModelAndCited() = runBlocking {
        val workspace = LocalWorkspace()
        val user = workspace.userFor(Roles.MEMBER)
        val session = workspace.newSession()
        val question = "这份检验报告有什么问题？"
        workspace.appendMessage(session.sessionId, MessageRoles.USER, question)

        var prompt = ""
        val agent = LocalAgent(workspace) { turns ->
            prompt = turns.last().content
            flowOf("血钾 6.8 偏高，建议复查。")
        }
        val dto = AttachmentDto(
            attachmentId = "att_local_1",
            name = "lab-report.txt",
            mime = "text/plain",
            sizeBytes = 128,
            chars = 30,
            hasText = true,
        )
        val sources = ArrayList<List<SourceRef>>()
        val answer = agent.respond(
            sessionId = session.sessionId,
            user = user,
            text = question,
            attachments = listOf(LocalAttachment(dto, "患者血钾 6.8 mmol/L，建议复查。")),
            onDelta = {},
            onSources = { sources += it },
            onStep = {},
        )

        assertTrue(prompt.contains("6.8"), "附件正文应进入提示词")
        assertTrue(sources.first().any { it.docId == "att_local_1" }, "附件应作为来源")
        assertTrue(answer.contains("6.8"))
    }
}
