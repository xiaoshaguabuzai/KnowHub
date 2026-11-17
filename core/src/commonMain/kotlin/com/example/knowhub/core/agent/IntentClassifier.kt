package com.example.knowhub.core.agent

import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.IntentResult
import com.example.knowhub.core.model.Intents
import com.example.knowhub.core.text.TextProcessor

/**
 * 规则优先的意图识别：知识库问答 / 业务流程 / 调教指令 / 闲聊。
 * 置信度与原因会随 meta 事件下发，便于前端与运营观察误判。
 */
object IntentClassifier {

    private val tuneKeywords = listOf("调教", "提示词", "切片", "权重", "负样本", "正样本", "检索参数", "rag 参数")

    private val greetings = setOf("你好", "您好", "hi", "hello", "在吗", "谢谢", "再见", "哈喽", "嗨")

    fun classify(text: String, flows: List<FlowTemplate>): IntentResult {
        val normalized = TextProcessor.normalize(text).trim()

        var bestFlow: FlowTemplate? = null
        var bestLength = 0
        for (flow in flows) {
            if (!flow.enabled) continue
            for (keyword in flow.triggerKeywords) {
                val k = TextProcessor.normalize(keyword).trim()
                if (k.isNotEmpty() && normalized.contains(k) && k.length > bestLength) {
                    bestFlow = flow
                    bestLength = k.length
                }
            }
        }
        if (bestFlow != null) {
            return IntentResult(
                type = Intents.FLOW,
                confidence = 0.90,
                flowId = bestFlow.flowId,
                reason = "命中流程触发词：${bestFlow.name}",
            )
        }

        if (tuneKeywords.any { normalized.contains(it) }) {
            return IntentResult(Intents.TUNE, 0.85, null, "命中调教指令关键词")
        }

        if (normalized.length <= 8 && greetings.any { normalized == it || normalized.startsWith(it) }) {
            return IntentResult(Intents.CHITCHAT, 0.70, null, "问候语")
        }

        return IntentResult(Intents.KB, 0.80, null, "默认走知识库问答")
    }
}
