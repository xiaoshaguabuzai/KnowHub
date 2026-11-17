package com.example.knowhub.core

import com.example.knowhub.core.agent.IntentClassifier
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.Intents
import com.example.knowhub.core.model.StreamEvent
import com.example.knowhub.core.model.StreamEventTypes
import com.example.knowhub.core.sse.Sse
import com.example.knowhub.core.sse.SseParser
import com.example.knowhub.core.text.Chunker
import com.example.knowhub.core.text.TextProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextAndProtocolTest {

    @Test
    fun tokenizerProducesCjkBigramsAndWords() {
        val tokens = TextProcessor.tokens("年假有 5 天，Amend Policy")
        assertTrue(tokens.contains("年假"))
        assertTrue(tokens.contains("5"))
        assertTrue(tokens.contains("amend"))
    }

    @Test
    fun chunkerSplitsLongTextWithinBudget() {
        val paragraph = "员工年假为每年 5 天，入职满一年后享受。年假可以跨年度结转，最多结转 5 天。加班需提前申请。"
        val text = (1..12).joinToString("\n\n") { paragraph }
        val chunks = Chunker.chunk(text, docId = "doc_1", docName = "制度", page = 2)
        assertTrue(chunks.size >= 2)
        assertTrue(chunks.all { it.text.isNotBlank() })
        assertTrue(chunks.all { it.page == 2 })
        assertEquals(chunks.size, chunks.map { it.chunkId }.toSet().size)
    }

    @Test
    fun sseRoundTripPreservesEvent() {
        val event = StreamEvent(
            type = StreamEventTypes.TEXT,
            seq = 7,
            sessionId = "s_1",
            messageId = "m_1",
            content = "你好，世界",
        )
        val frame = Sse.encode(event)
        val parser = SseParser()
        var decoded: StreamEvent? = null
        for (line in frame.split("\n")) {
            parser.feed(line)?.let { decoded = it }
        }
        assertNotNull(decoded)
        assertEquals(event, decoded)
        assertEquals("7", parser.lastEventId)
    }

    @Test
    fun intentClassifierDetectsFlowAndChitchat() {
        val flow = FlowTemplate(
            flowId = "flow_overtime",
            name = "加班申请登记",
            triggerKeywords = listOf("加班申请"),
        )
        assertEquals(Intents.FLOW, IntentClassifier.classify("我要提交加班申请", listOf(flow)).type)
        assertEquals(Intents.CHITCHAT, IntentClassifier.classify("你好", emptyList()).type)
        assertEquals(Intents.KB, IntentClassifier.classify("年假有几天", emptyList()).type)
    }
}
