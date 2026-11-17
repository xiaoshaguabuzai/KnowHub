package com.example.knowhub.server.chat

import com.example.knowhub.core.agent.IntentClassifier
import com.example.knowhub.core.flow.FlowEvent
import com.example.knowhub.core.flow.FlowRunner
import com.example.knowhub.core.model.ChatRequest
import com.example.knowhub.core.model.FlowRunStatus
import com.example.knowhub.core.model.FlowStepInfo
import com.example.knowhub.core.model.Intents
import com.example.knowhub.core.model.MessageRoles
import com.example.knowhub.core.model.NodeTypes
import com.example.knowhub.core.model.SourceRef
import com.example.knowhub.core.model.StreamEvent
import com.example.knowhub.core.model.StreamEventTypes
import com.example.knowhub.core.model.UsageInfo
import com.example.knowhub.core.rag.PromptBuilder
import com.example.knowhub.core.rag.ScoredChunk
import com.example.knowhub.core.security.Acl
import com.example.knowhub.core.text.TextProcessor
import com.example.knowhub.core.util.newId
import com.example.knowhub.server.audit.AuditService
import com.example.knowhub.server.ingest.AttachmentService
import com.example.knowhub.server.llm.LlmGateway
import com.example.knowhub.server.rag.IndexService
import com.example.knowhub.server.store.AttachmentRecord
import com.example.knowhub.server.store.DataStore
import com.example.knowhub.server.store.MessageRecord
import com.example.knowhub.server.store.PendingFlow
import com.example.knowhub.server.store.SessionRecord
import com.example.knowhub.server.store.UserAccount
import com.example.knowhub.server.support.ApiException
import com.example.knowhub.server.tools.ToolRegistry
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException

/**
 * 问答编排：
 *  1. 写入用户消息 → 2. 判断是否处于流程中 / 意图分类
 *  3. 知识库：检索（ACL 过滤）→ 来源事件 → 大模型流式 or 检索式回答
 *  4. 流程：FlowRunner 推进 → 参数收集 / 工具调用 / 分支 / 结束
 *  5. 持久化消息、更新会话、审计
 */
class ChatService(
    private val store: DataStore,
    private val index: IndexService,
    private val tools: ToolRegistry,
    private val llm: LlmGateway,
    private val audit: AuditService,
    private val attachments: AttachmentService,
) {

    suspend fun stream(
        user: UserAccount,
        request: ChatRequest,
        emit: suspend (StreamEvent) -> Unit,
    ) {
        val traceId = newId("tr")
        val messageId = newId("msg")
        val startedAt = System.currentTimeMillis()
        var seq = 0L

        val send: suspend (StreamEvent) -> Unit = { event ->
            seq += 1
            emit(
                event.copy(
                    seq = seq,
                    sessionId = request.sessionId,
                    traceId = traceId,
                    messageId = event.messageId ?: messageId,
                )
            )
        }

        send(StreamEvent(type = StreamEventTypes.META))

        try {
            val attachmentPairs = if (request.attachmentIds.isNotEmpty()) {
                attachments.load(user.userId, request.attachmentIds)
            } else {
                emptyList()
            }
            val attachmentOnly = request.message.isBlank() && attachmentPairs.isNotEmpty()
            val text = request.message.trim().ifBlank {
                if (attachmentPairs.isNotEmpty()) "请解读我上传的附件。" else ""
            }
            if (text.isEmpty()) throw ApiException(HttpStatusCode.BadRequest, "EMPTY_MESSAGE", "消息不能为空")

            val db = store.snapshot()
            val session = db.sessionById(request.sessionId)
                ?: throw ApiException(HttpStatusCode.NotFound, "SESSION_NOT_FOUND", "会话不存在，请新建会话")
            if (session.userId != user.userId) {
                throw ApiException(HttpStatusCode.Forbidden, "FORBIDDEN", "无权访问该会话")
            }

            persistUserMessage(session, user, text, traceId)

            val outcome: ChatOutcome = when {
                session.pendingFlow != null -> runFlow(user, session, text, null, send)
                else -> {
                    val userDto = user.toDto()
                    // 患者只能触发 audience=all 的流程（如门诊预约），内部流程不对患者开放
                    val visibleFlows = db.flows.filter { it.enabled && Acl.canRunFlow(it.audience, userDto) }
                    val intent = IntentClassifier.classify(text, visibleFlows)
                    when (intent.type) {
                        Intents.FLOW -> runFlow(user, session, text, intent.flowId, send)
                        Intents.CHITCHAT -> {
                            val reply = if (Acl.isPatient(userDto)) PATIENT_CHITCHAT_REPLY else CHITCHAT_REPLY
                            emitText(reply, send)
                            ChatOutcome(reply, emptyList(), Intents.CHITCHAT, llm = llm.name)
                        }
                        Intents.TUNE -> {
                            val reply = tuneReply(db.rag.topK, db.rag.chunkSizeTokens, db.rag.overlapTokens, db.rag.forceCitation)
                            emitText(reply, send)
                            ChatOutcome(reply, emptyList(), Intents.TUNE, llm = llm.name)
                        }
                        else -> knowledgeAnswer(user, session, text, attachmentPairs, attachmentOnly, send)
                    }
                }
            }

            val latency = System.currentTimeMillis() - startedAt
            send(
                StreamEvent(
                    type = StreamEventTypes.DONE,
                    sources = outcome.sources.ifEmpty { null },
                    usage = UsageInfo(
                        promptTokens = outcome.promptTokens,
                        completionTokens = outcome.completionTokens,
                        llm = outcome.llm,
                        latencyMs = latency,
                    ),
                )
            )
            persistAssistantMessage(
                user = user,
                messageId = messageId,
                sessionId = session.sessionId,
                content = outcome.content,
                sources = outcome.sources,
                intent = outcome.intent,
                flowId = outcome.flowId,
                traceId = traceId,
                latency = latency,
                model = outcome.llm,
                pendingFlow = outcome.pendingFlow,
            )
            audit.record(
                actor = user,
                action = "CHAT",
                targetType = "session",
                targetId = session.sessionId,
                detail = "intent=${outcome.intent} len=${outcome.content.length}",
                terminal = "chat",
            )
        } catch (e: ApiException) {
            send(
                StreamEvent(
                    type = StreamEventTypes.ERROR,
                    error = com.example.knowhub.core.model.ApiErrorInfo(e.code, e.message, traceId),
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            send(
                StreamEvent(
                    type = StreamEventTypes.ERROR,
                    error = com.example.knowhub.core.model.ApiErrorInfo(
                        "INTERNAL_ERROR",
                        e.message ?: "服务端处理失败",
                        traceId,
                    ),
                )
            )
        }
    }

    // ---------------------------------------------------------------- 知识库问答

    private suspend fun knowledgeAnswer(
        user: UserAccount,
        session: SessionRecord,
        question: String,
        attachmentPairs: List<Pair<AttachmentRecord, String>>,
        attachmentOnly: Boolean,
        send: suspend (StreamEvent) -> Unit,
    ): ChatOutcome {
        val db = store.snapshot()
        val audience = if (Acl.isPatient(user.toDto())) Acl.AUDIENCE_ALL else Acl.AUDIENCE_STAFF
        // 纯附件提问时只依据附件回答，避免检索到无关制度
        val results: List<ScoredChunk> =
            if (attachmentOnly) emptyList() else index.retriever().retrieve(question, user.toDto(), db.rag.topK)

        val attachmentSources = attachmentPairs
            .filter { it.second.isNotBlank() }
            .map { (record, text) ->
                SourceRef(
                    docId = record.attachmentId,
                    docName = record.name,
                    chunkId = "att_${record.attachmentId}",
                    page = 1,
                    section = "附件",
                    chunkIndex = 0,
                    score = 1.0,
                    snippet = TextProcessor.truncateChars(text, 200),
                )
            }
        val sources = attachmentSources + results.map { it.toSourceRef() }
        if (sources.isNotEmpty()) {
            send(StreamEvent(type = StreamEventTypes.SOURCE, sources = sources))
        }

        val hasAttachmentText = attachmentPairs.any { it.second.isNotBlank() }
        val answer = StringBuilder()
        var promptTokens = 0
        var completionTokens = 0

        if (llm.remote && (results.isNotEmpty() || hasAttachmentText)) {
            val history = db.messagesOf(session.sessionId).takeLast(8).map { it.toDto() }
            val questionWithAttachments = buildAttachmentQuestion(question, attachmentPairs)
            val turns = PromptBuilder.buildMessages(
                questionWithAttachments,
                results,
                history,
                db.rag.forceCitation,
                audience = audience,
            )
            try {
                val result = llm.streamChat(turns) { delta ->
                    answer.append(delta)
                    send(StreamEvent(type = StreamEventTypes.TEXT, content = delta))
                }
                promptTokens = result.promptTokens
                completionTokens = result.completionTokens
            } catch (e: Exception) {
                if (answer.isNotEmpty()) answer.append("\n\n")
                answer.append("（大模型调用失败，已切换为检索式回答）\n")
                val fallback = PromptBuilder.extractiveAnswer(question, results, audience)
                emitText(fallback, send)
                answer.append(fallback)
            }
        } else {
            if (hasAttachmentText) {
                val excerpt = buildString {
                    appendLine("根据附件内容：")
                    attachmentPairs.filter { it.second.isNotBlank() }.take(3).forEach { (record, text) ->
                        appendLine("《${record.name}》：${TextProcessor.truncateChars(text, 600)}")
                    }
                    appendLine()
                }
                emitText(excerpt, send)
                answer.append(excerpt)
            }
            if (!attachmentOnly && (results.isNotEmpty() || !hasAttachmentText)) {
                val local = PromptBuilder.extractiveAnswer(question, results, audience)
                emitText(local, send)
                answer.append(local)
            }
            if (attachmentPairs.any { it.second.isBlank() }) {
                val note = "（附件包含图片，当前部署未启用服务端图片文字识别；可先用拍照识别或补充文字说明。）"
                emitText(note, send)
                answer.append(note)
            }
        }

        if (Acl.isPatient(user.toDto())) {
            val note = "（以上为就医指引，具体以医院现场安排为准；如有不适请及时就医。）"
            emitText("\n\n$note", send)
            answer.append("\n\n").append(note)
        }

        return ChatOutcome(
            content = answer.toString().trim(),
            sources = sources,
            intent = Intents.KB,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            llm = if (llm.remote && (results.isNotEmpty() || hasAttachmentText)) llm.name else "retrieval",
        )
    }

    /** 把附件正文拼进提示词（LLM 模式）；检索式回答单独引用附件摘录。 */
    private fun buildAttachmentQuestion(
        question: String,
        attachmentPairs: List<Pair<AttachmentRecord, String>>,
    ): String {
        val withText = attachmentPairs.filter { it.second.isNotBlank() }
        if (withText.isEmpty()) {
            return if (attachmentPairs.isEmpty()) question
            else "（附件为图片，未包含可识别文字）\n$question"
        }
        return buildString {
            appendLine("【用户上传的附件】")
            withText.take(3).forEach { (record, text) ->
                appendLine("《${record.name}》")
                appendLine(TextProcessor.truncateChars(text, 2500))
                appendLine()
            }
            appendLine("【问题】")
            append(question)
        }
    }

    // ---------------------------------------------------------------- 流程执行

    private suspend fun runFlow(
        user: UserAccount,
        session: SessionRecord,
        text: String,
        explicitFlowId: String?,
        send: suspend (StreamEvent) -> Unit,
    ): ChatOutcome {
        val db = store.snapshot()
        val pending = session.pendingFlow
        val flowId = pending?.flowId
            ?: explicitFlowId
            ?: throw ApiException(HttpStatusCode.BadRequest, "FLOW_NOT_FOUND", "没有匹配的业务流程")
        val template = db.flowById(flowId)
            ?: throw ApiException(HttpStatusCode.NotFound, "FLOW_NOT_FOUND", "流程不存在：$flowId")
        if (!Acl.canRunFlow(template.audience, user.toDto())) {
            throw ApiException(HttpStatusCode.Forbidden, "FLOW_FORBIDDEN", "该流程仅对院内职工开放")
        }

        val runner = FlowRunner(template)
        var tick = if (pending != null) runner.submitInput(pending.state, text) else runner.start()
        val content = StringBuilder()
        var lastToolMessage = ""

        while (true) {
            var toolCall: FlowEvent.CallTool? = null
            for (event in tick.events) {
                when (event) {
                    is FlowEvent.Ask -> {
                        send(flowStepEvent(flowId, event.nodeId, NodeTypes.COLLECT_PARAM, event.question, "WAITING_INPUT"))
                        emitText(event.question + "\n", send)
                        content.append(event.question).append('\n')
                    }

                    is FlowEvent.Say -> {
                        send(flowStepEvent(flowId, event.nodeId, NodeTypes.MESSAGE, event.text, "DONE"))
                        emitText(event.text + "\n", send)
                        content.append(event.text).append('\n')
                    }

                    is FlowEvent.CallTool -> toolCall = event

                    is FlowEvent.Done -> {
                        send(flowStepEvent(flowId, event.nodeId, NodeTypes.MESSAGE, "流程结束", "DONE"))
                    }

                    is FlowEvent.Failed -> throw ApiException(
                        HttpStatusCode.UnprocessableEntity,
                        "FLOW_FAILED",
                        event.message,
                    )
                }
            }

            val call = toolCall ?: break
            send(flowStepEvent(flowId, call.nodeId, NodeTypes.TOOL_INVOKE, "调用工具 ${call.toolId}…", "RUNNING"))
            val result = tools.invoke(call.toolId, call.args, user)
            send(
                flowStepEvent(
                    flowId,
                    call.nodeId,
                    NodeTypes.TOOL_INVOKE,
                    result.message,
                    if (result.ok) "DONE" else "FAILED",
                )
            )
            if (!result.ok) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, "TOOL_FAILED", result.message)
            }
            lastToolMessage = result.message
            tick = runner.submitToolResult(tick.state, result)
        }

        // 工具的完整回执由流程的消息节点输出；若流程没有消息节点，则兜底展示工具结果
        if (content.isBlank() && lastToolMessage.isNotBlank()) {
            emitText(lastToolMessage, send)
            content.append(lastToolMessage)
        }

        val pendingState = if (tick.state.status == FlowRunStatus.WAITING_INPUT) {
            PendingFlow(flowId, tick.state)
        } else {
            null
        }

        return ChatOutcome(
            content = content.toString().trim(),
            sources = emptyList(),
            intent = Intents.FLOW,
            flowId = flowId,
            llm = "flow-engine",
            pendingFlow = pendingState,
        )
    }

    // ---------------------------------------------------------------- 持久化

    private fun persistUserMessage(session: SessionRecord, user: UserAccount, text: String, traceId: String) {
        val now = System.currentTimeMillis()
        store.mutate { db ->
            val message = MessageRecord(
                messageId = newId("msg"),
                sessionId = session.sessionId,
                userId = user.userId,
                role = MessageRoles.USER,
                content = text,
                createdAt = now,
                traceId = traceId,
            )
            val sessions = db.sessions.map { existing ->
                if (existing.sessionId != session.sessionId) existing
                else existing.copy(
                    updatedAt = now,
                    title = if (existing.title == DEFAULT_TITLE) text.take(20) else existing.title,
                )
            }
            db.copy(messages = db.messages + message, sessions = sessions) to Unit
        }
    }

    private fun persistAssistantMessage(
        user: UserAccount,
        messageId: String,
        sessionId: String,
        content: String,
        sources: List<SourceRef>,
        intent: String,
        flowId: String?,
        traceId: String,
        latency: Long,
        model: String,
        pendingFlow: PendingFlow?,
    ) {
        val now = System.currentTimeMillis()
        store.mutate { db ->
            val message = MessageRecord(
                messageId = messageId,
                sessionId = sessionId,
                userId = user.userId,
                role = MessageRoles.ASSISTANT,
                content = content,
                sources = sources,
                createdAt = now,
                intent = intent,
                flowId = flowId,
                traceId = traceId,
                latencyMs = latency,
                model = model,
            )
            val sessions = db.sessions.map { existing ->
                if (existing.sessionId != sessionId) existing
                else existing.copy(updatedAt = now, pendingFlow = pendingFlow)
            }
            db.copy(messages = db.messages + message, sessions = sessions) to Unit
        }
    }

    // ---------------------------------------------------------------- 小工具

    private suspend fun emitText(text: String, send: suspend (StreamEvent) -> Unit) {
        var index = 0
        while (index < text.length) {
            val end = minOf(text.length, index + CHUNK_CHARS)
            send(StreamEvent(type = StreamEventTypes.TEXT, content = text.substring(index, end)))
            index = end
        }
    }

    private fun flowStepEvent(
        flowId: String,
        nodeId: String,
        nodeType: String,
        desc: String,
        status: String,
    ): StreamEvent = StreamEvent(
        type = StreamEventTypes.FLOW,
        flow = FlowStepInfo(
            flowId = flowId,
            nodeId = nodeId,
            nodeType = nodeType,
            desc = desc,
            status = status,
        ),
    )

    private fun ScoredChunk.toSourceRef(): SourceRef = SourceRef(
        docId = chunk.docId,
        docName = chunk.docName,
        chunkId = chunk.chunkId,
        page = chunk.page,
        section = chunk.section,
        chunkIndex = chunk.index,
        score = score,
        snippet = snippet,
    )

    private fun tuneReply(topK: Int, chunkSize: Int, overlap: Int, forceCitation: Boolean): String = buildString {
        appendLine("当前医院知识库调教状态：")
        appendLine("• 检索参数：TopK=$topK，切片 $chunkSize tokens / 重叠 $overlap tokens，强制溯源=${if (forceCitation) "开启" else "关闭"}")
        appendLine("• 正负样本：在回答的“引用来源”里点 👍/👎，会写入调教样本并实时影响后续召回排序")
        appendLine("• 管理端：管理员可在「设置 → 检索配置」调整参数，或对制度文档执行重建索引")
        append("普通职工可以提交反馈样本，配置变更需要管理员（医务处）权限。")
    }

    private data class ChatOutcome(
        val content: String,
        val sources: List<SourceRef>,
        val intent: String,
        val flowId: String? = null,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        val llm: String = "retrieval",
        val pendingFlow: PendingFlow? = null,
    )

    companion object {
        const val DEFAULT_TITLE = "新会话"
        private const val CHUNK_CHARS = 48

        private val CHITCHAT_REPLY = """
            你好，我是 KnowHub 医院知识助手 👋
            可以帮你：
            • 查制度：门诊、住院、核心制度、药事、护理、院感
            • 走流程：住院登记、危急值上报、设备报修
            • 看原文：回答中的引用可直接打开制度原文
            直接输入问题即可，例如「危急值上报时限是多久？」。
        """.trimIndent()

        /** 患者/家属版本：只引导就医相关能力。 */
        private val PATIENT_CHITCHAT_REPLY = """
            你好，我是 KnowHub 就医助手 👋
            可以帮你：
            • 查指南：预约挂号、住院办理、检查注意、医保报销、出院复诊
            • 约门诊：告诉我科室、日期和手机号即可
            • 看报告：拍检验单或报告照片，我帮你识别文字并解释
            直接问就行，例如「抽血前能吃饭吗？」。
        """.trimIndent()
    }
}
