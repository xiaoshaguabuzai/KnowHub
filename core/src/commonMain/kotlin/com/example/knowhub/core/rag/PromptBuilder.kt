package com.example.knowhub.core.rag

import com.example.knowhub.core.model.ChatRequest
import com.example.knowhub.core.model.MessageDto
import com.example.knowhub.core.text.TextProcessor

data class ChatTurn(val role: String, val content: String)

/**
 * 提示词构建：强制引用编号、限制上下文长度、保留最近几轮对话。
 */
object PromptBuilder {

    const val MAX_CONTEXT_CHARS = 6000

    fun systemPrompt(forceCitation: Boolean, audience: String = "staff"): String {
        if (audience == "all") return patientSystemPrompt()
        val citation = if (forceCitation) {
            "回答中的每个事实性结论都必须用 [编号] 标注出处；若检索结果不足以回答，必须明确说明“知识库中未找到相关依据”，不要编造。"
        } else {
            "尽量标注出处编号；若检索结果不足，请明确说明。"
        }
        return buildString {
            append("你是「掌故 KnowHub」的医院知识助手，服务于院内医务人员与管理人员。\n")
            append("规则：\n")
            append("1. 只依据给定的【参考资料】回答，不使用参考资料之外的知识。\n")
            append("2. $citation\n")
            append("3. 用简体中文回答，先给结论，再给依据；条目化表达，避免空话。\n")
            append("4. 涉及制度流程时，给出步骤与责任科室；涉及时限、权限、剂量等关键数值时，必须明确出处。\n")
            append("5. 涉及患者诊疗决策时，提醒以主管医师判断与院内制度为准。\n")
        }
    }

    fun buildMessages(
        question: String,
        contexts: List<ScoredChunk>,
        history: List<MessageDto> = emptyList(),
        forceCitation: Boolean = true,
        maxContextChars: Int = MAX_CONTEXT_CHARS,
        audience: String = "staff",
    ): List<ChatTurn> {
        val messages = ArrayList<ChatTurn>()
        messages.add(ChatTurn("system", systemPrompt(forceCitation, audience)))

        for (turn in history.takeLast(6)) {
            if (turn.role == "user" || turn.role == "assistant") {
                messages.add(ChatTurn(turn.role, TextProcessor.truncateChars(turn.content, 400)))
            }
        }

        val contextText = buildContext(contexts, maxContextChars)
        val userContent = buildString {
            append("【参考资料】\n")
            append(if (contextText.isBlank()) "（无）" else contextText)
            append("\n\n【问题】\n")
            append(question)
        }
        messages.add(ChatTurn("user", userContent))
        return messages
    }

    /** 患者/家属视角：通俗、分步骤、不做诊断、不透露内部管理信息。 */
    private fun patientSystemPrompt(): String = buildString {
        append("你是「KnowHub」医院就医助手，面向患者与家属，用通俗语言说明就医流程。\\n")
        append("规则：\\n")
        append("1. 只依据给定的【参考资料】回答，不编造；找不到时说明“就医指南里暂未收录，建议咨询导医台或门诊服务台”。\\n")
        append("2. 先给结论，再分步骤说明；少用专业术语，必须使用时先解释。\\n")
        append("3. 不做诊断、不推荐用药、不判断病情轻重；涉及具体病情时建议尽快到相应科室就诊，紧急情况拨打 120。\\n")
        append("4. 引用资料时标注 [编号]；费用、报销比例、号源与时间以医院最新公示为准。\\n")
        append("5. 员工薪酬、内部纠纷、未公开管理文件等内部信息不对患者开放，被问到时礼貌说明。\\n")
    }

    fun buildContext(contexts: List<ScoredChunk>, maxContextChars: Int = MAX_CONTEXT_CHARS): String {
        val sb = StringBuilder()
        var index = 0
        for (context in contexts) {
            index++
            val header = buildString {
                append("[").append(index).append("] 《").append(context.chunk.docName).append("》")
                append(" 第").append(context.chunk.page).append("页")
                context.chunk.section?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
            }
            val body = TextProcessor.truncateChars(context.chunk.text, 800)
            val block = header + "\n" + body + "\n"
            if (sb.length + block.length > maxContextChars) break
            sb.append(block).append('\n')
        }
        return sb.toString().trim()
    }

    /** 无大模型时的检索式回答：可解释、可溯源，明确标注为检索模式。 */
    fun extractiveAnswer(question: String, contexts: List<ScoredChunk>, audience: String = "staff"): String {
        val sourceLabel = if (audience == "all") "就医指南" else "知识库"
        if (contexts.isEmpty()) {
            return if (audience == "all") {
                "就医指南中暂未找到与「$question」直接相关的说明，建议咨询导医台或门诊服务台。"
            } else {
                "知识库中未找到与「$question」直接相关的依据。建议补充相关制度文档，或在管理后台调教检索参数后重试。"
            }
        }
        val sb = StringBuilder()
        sb.append("根据").append(sourceLabel).append("检索结果，找到以下依据：\n")
        contexts.take(3).forEachIndexed { index, context ->
            sb.append(index + 1).append(". 《").append(context.chunk.docName).append("》")
            sb.append(" 第").append(context.chunk.page).append("页")
            context.chunk.section?.takeIf { it.isNotBlank() }?.let { sb.append("（").append(it).append("）") }
            // 首条给出该片段完整条款（分档条款常跨多句），其余给出最相关摘要
            val excerpt = if (index == 0) {
                TextProcessor.truncateChars(TextProcessor.cleanMarkdown(context.chunk.text), 360)
            } else {
                TextProcessor.truncateChars(context.snippet, 200)
            }
            sb.append("：").append(excerpt).append(" [").append(index + 1).append("]\n")
        }
        sb.append("\n以上内容摘自").append(sourceLabel).append("原文，可在下方“引用来源”中查看全文片段。")
        sb.append("（当前为检索式回答，配置大模型后会生成更连贯的总结。）")
        return sb.toString()
    }
}
