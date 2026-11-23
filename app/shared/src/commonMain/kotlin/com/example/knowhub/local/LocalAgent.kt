package com.example.knowhub.local

import com.example.knowhub.core.agent.IntentClassifier
import com.example.knowhub.core.flow.FlowEvent
import com.example.knowhub.core.flow.FlowRunner
import com.example.knowhub.core.model.FlowRunState
import com.example.knowhub.core.model.FlowRunStatus
import com.example.knowhub.core.model.FlowStepInfo
import com.example.knowhub.core.model.Intents
import com.example.knowhub.core.model.MessageDto
import com.example.knowhub.core.model.MessageRoles
import com.example.knowhub.core.model.NodeTypes
import com.example.knowhub.core.model.Roles
import com.example.knowhub.core.model.SourceRef
import com.example.knowhub.core.model.UserDto
import com.example.knowhub.core.rag.ChatTurn
import com.example.knowhub.core.rag.PromptBuilder
import com.example.knowhub.core.security.Acl
import com.example.knowhub.core.text.TextProcessor
import kotlinx.coroutines.flow.Flow

/**
 * 直连模式的本地 Agent：意图识别 → 本地流程 / 本地检索 / 闲聊，再调用用户配置的模型。
 * 与中台 ChatService 的行为保持一致：引用编号、流程断点续跑、附件纳入回答、患者提示。
 */
class LocalAgent(
    private val workspace: LocalWorkspace,
    private val streamLlm: suspend (List<ChatTurn>) -> Flow<String>,
) {

    suspend fun respond(
        sessionId: String,
        user: UserDto,
        text: String,
        attachments: List<LocalAttachment>,
        onDelta: (String) -> Unit,
        onSources: (List<SourceRef>) -> Unit,
        onStep: (FlowStepInfo) -> Unit,
    ): String {
        val session = workspace.session(sessionId) ?: return ""
        val isPatient = user.roles.contains(Roles.PATIENT)

        val pending = session.pendingFlow
        if (pending != null) {
            return completeFlow(sessionId, user, pending, text, onDelta, onStep).orEmpty()
        }

        val intent = IntentClassifier.classify(text, workspace.visibleFlows(user))
        when (intent.type) {
            Intents.FLOW -> return runFlow(sessionId, user, intent.flowId, onDelta, onStep)
            Intents.CHITCHAT -> {
                val reply = if (isPatient) PATIENT_CHITCHAT else STAFF_CHITCHAT
                emit(reply, onDelta)
                workspace.appendMessage(sessionId, MessageRoles.ASSISTANT, reply)
                return reply
            }
            Intents.TUNE -> {
                val reply = "直连模式的检索参数已内置：topK=${workspace.rag.topK}，chunk=${workspace.rag.chunkSizeTokens} tokens，" +
                    "检索权重 BM25 0.55 / 向量 0.45。反馈会写入本地调教样本，影响后续召回。"
                emit(reply, onDelta)
                workspace.appendMessage(sessionId, MessageRoles.ASSISTANT, reply)
                return reply
            }
            else -> Unit
        }

        return knowledgeAnswer(sessionId, user, text, attachments, onDelta, onSources)
    }

    // ---------------------------------------------------------------- 流程

    private suspend fun runFlow(
        sessionId: String,
        user: UserDto,
        flowId: String?,
        onDelta: (String) -> Unit,
        onStep: (FlowStepInfo) -> Unit,
    ): String {
        val template = workspace.flows.firstOrNull { it.flowId == flowId }
            ?: return "未找到流程：$flowId"
        if (!Acl.canRunFlow(template.audience, user)) {
            return "该流程仅对院内职工开放。"
        }
        val runner = FlowRunner(template)
        var tick = runner.start(localNow())
        tick = driveTools(runner, tick, onStep)
        return finishTick(sessionId, template.flowId, tick, onDelta, onStep)
    }

    private suspend fun completeFlow(
        sessionId: String,
        user: UserDto,
        pending: FlowRunState,
        input: String,
        onDelta: (String) -> Unit,
        onStep: (FlowStepInfo) -> Unit,
    ): String? {
        val template = workspace.flows.firstOrNull { it.flowId == pending.flowId } ?: return null
        if (!Acl.canRunFlow(template.audience, user)) {
            workspace.setPending(sessionId, null)
            return "该流程仅对院内职工开放。"
        }
        val runner = FlowRunner(template)
        var tick = runner.submitInput(pending, input)
        tick = driveTools(runner, tick, onStep)
        return finishTick(sessionId, template.flowId, tick, onDelta, onStep)
    }

    private suspend fun driveTools(
        runner: FlowRunner,
        initial: com.example.knowhub.core.flow.FlowTick,
        onStep: (FlowStepInfo) -> Unit,
    ): com.example.knowhub.core.flow.FlowTick {
        var tick = initial
        var guard = 0
        while (guard++ < 8) {
            val call = tick.events.filterIsInstance<FlowEvent.CallTool>().lastOrNull() ?: return tick
            onStep(
                FlowStepInfo(
                    flowId = tick.state.flowId,
                    nodeId = call.nodeId,
                    nodeType = NodeTypes.TOOL_INVOKE,
                    desc = "调用工具 ${call.toolId}",
                    status = "RUNNING",
                )
            )
            val result = LocalTools.invoke(call.toolId, call.args)
            tick = runner.submitToolResult(tick.state, result)
        }
        return tick
    }

    private suspend fun finishTick(
        sessionId: String,
        flowId: String,
        tick: com.example.knowhub.core.flow.FlowTick,
        onDelta: (String) -> Unit,
        onStep: (FlowStepInfo) -> Unit,
    ): String {
        val out = StringBuilder()
        for (event in tick.events) {
            when (event) {
                is FlowEvent.Ask -> {
                    out.append(event.question)
                    onStep(
                        FlowStepInfo(
                            flowId = flowId,
                            nodeId = event.nodeId,
                            nodeType = NodeTypes.COLLECT_PARAM,
                            desc = event.question,
                            status = "RUNNING",
                        )
                    )
                }
                is FlowEvent.Say -> out.append(event.text)
                is FlowEvent.Done -> {
                    out.append(event.message)
                    onStep(FlowStepInfo(flowId, event.nodeId, NodeTypes.MESSAGE, "流程完成", "DONE"))
                }
                is FlowEvent.Failed -> out.append("流程失败：").append(event.message)
                is FlowEvent.CallTool -> Unit
            }
        }
        val answer = out.toString().trim().ifBlank { "流程已结束。" }
        val waiting = tick.state.status == FlowRunStatus.WAITING_INPUT || tick.state.status == FlowRunStatus.WAITING_TOOL
        workspace.setPending(sessionId, if (waiting) tick.state else null)
        emit(answer, onDelta)
        workspace.appendMessage(sessionId, MessageRoles.ASSISTANT, answer)
        return answer
    }

    // ---------------------------------------------------------------- 知识库

    private suspend fun knowledgeAnswer(
        sessionId: String,
        user: UserDto,
        question: String,
        attachments: List<LocalAttachment>,
        onDelta: (String) -> Unit,
        onSources: (List<SourceRef>) -> Unit,
    ): String {
        val isPatient = user.roles.contains(Roles.PATIENT)
        val audience = if (isPatient) Acl.AUDIENCE_ALL else Acl.AUDIENCE_STAFF
        val results = workspace.retriever().retrieve(question, user, workspace.rag.topK)

        val attachmentSources = attachments
            .filter { it.text.isNotBlank() }
            .map { attachment ->
                SourceRef(
                    docId = attachment.dto.attachmentId,
                    docName = attachment.dto.name,
                    chunkId = "att_${attachment.dto.attachmentId}",
                    page = 1,
                    section = "附件",
                    chunkIndex = 0,
                    score = 1.0,
                    snippet = TextProcessor.truncateChars(attachment.text, 200),
                )
            }
        val sources = attachmentSources + results.map { scored ->
            SourceRef(
                docId = scored.chunk.docId,
                docName = scored.chunk.docName,
                chunkId = scored.chunk.chunkId,
                page = scored.chunk.page,
                section = scored.chunk.section,
                chunkIndex = scored.chunk.index,
                score = scored.score,
                snippet = scored.snippet,
            )
        }
        if (sources.isNotEmpty()) onSources(sources)

        val hasAttachmentText = attachments.any { it.text.isNotBlank() }
        val answer = StringBuilder()

        if (results.isNotEmpty() || hasAttachmentText) {
            val session = workspace.session(sessionId)
            val history = session?.messages
                ?.takeLast(8)
                ?.map { MessageDto(messageId = it.messageId, sessionId = sessionId, role = it.role, content = it.content) }
                .orEmpty()
            val questionWithAttachments = buildAttachmentQuestion(question, attachments)
            val turns = PromptBuilder.buildMessages(
                question = questionWithAttachments,
                contexts = results,
                history = history,
                forceCitation = workspace.rag.forceCitation,
                audience = audience,
            )
            try {
                streamLlm(turns).collect { delta ->
                    answer.append(delta)
                    onDelta(delta)
                }
            } catch (e: Exception) {
                if (answer.isNotEmpty()) answer.append("\n\n")
                val fallback = PromptBuilder.extractiveAnswer(question, results, audience)
                emit(fallback, onDelta)
                answer.append(fallback)
                workspace.appendMessage(
                    sessionId,
                    MessageRoles.ASSISTANT,
                    answer.toString().trim(),
                    sources,
                )
                return answer.toString().trim()
            }
        } else {
            val local = PromptBuilder.extractiveAnswer(question, results, audience)
            emit(local, onDelta)
            answer.append(local)
        }

        if (attachments.any { it.text.isBlank() }) {
            val note = "（附件为图片，未包含可识别文字，可在输入框补充描述。）"
            emit("\\n\\n$note", onDelta)
            answer.append("\\n\\n").append(note)
        }

        if (isPatient) {
            val note = "（以上为就医指引，具体以医院现场安排为准；如有不适请及时就医。）"
            emit("\n\n$note", onDelta)
            answer.append("\n\n").append(note)
        }

        val content = answer.toString().trim()
        workspace.appendMessage(sessionId, MessageRoles.ASSISTANT, content, sources)
        return content
    }

    private fun buildAttachmentQuestion(question: String, attachments: List<LocalAttachment>): String {
        val withText = attachments.filter { it.text.isNotBlank() }
        if (withText.isEmpty()) return question
        return buildString {
            appendLine("【用户上传的附件】")
            withText.take(3).forEach { attachment ->
                appendLine("《${attachment.dto.name}》")
                appendLine(TextProcessor.truncateChars(attachment.text, 2500))
                appendLine()
            }
            appendLine("【问题】")
            append(question)
        }
    }

    private fun emit(text: String, onDelta: (String) -> Unit) {
        if (text.isNotEmpty()) onDelta(text)
    }

    private companion object {
        val STAFF_CHITCHAT = """
            你好，我是 KnowHub 医院知识助手（直连模式）👋
            可以帮你：
            • 查制度：门诊、住院、核心制度、药事、护理、院感
            • 走流程：住院登记、危急值上报、设备报修
            • 看原文：回答中的引用可直接打开本地制度原文
            直接输入问题即可，例如「危急值上报时限是多久？」。
        """.trimIndent()

        val PATIENT_CHITCHAT = """
            你好，我是 KnowHub 就医助手（直连模式）👋
            可以帮你：
            • 查指南：预约挂号、住院办理、检查注意、医保报销、出院复诊
            • 约门诊：告诉我科室、日期和手机号即可
            • 看报告：拍检验单或报告照片，我帮你识别文字并解释
            直接问就行，例如「抽血前能吃饭吗？」。
        """.trimIndent()
    }
}
