package com.example.knowhub.core.sse

import com.example.knowhub.core.AppJson
import com.example.knowhub.core.model.StreamEvent

/**
 * SSE 帧编解码（text/event-stream）。
 *
 * 帧格式：
 * ```
 * id: 12
 * event: text
 * data: {"type":"text",...}
 *
 * ```
 * 客户端按行读取，空行触发派发，支持 `Last-Event-ID` 续接与 seq 去重。
 */
object Sse {

    fun encode(event: StreamEvent): String {
        val json = AppJson.encodeToString(StreamEvent.serializer(), event)
        return buildString {
            append("id: ").append(event.seq).append('\n')
            append("event: ").append(event.type).append('\n')
            for (line in json.split('\n')) {
                append("data: ").append(line).append('\n')
            }
            append('\n')
        }
    }

    /** 心跳注释帧，避免网关/代理因空闲断开长连接。 */
    fun heartbeat(): String = ": ping\n\n"
}

/**
 * 增量式 SSE 解析器：逐行喂入，遇到空行时返回一个完整事件。
 * 平台无关，客户端与服务端测试共用。
 */
class SseParser {

    private val data = StringBuilder()
    private var eventName: String? = null
    private var lastId: String? = null

    var lastEventId: String? = null
        private set

    fun feed(line: String): StreamEvent? {
        if (line.isEmpty()) {
            val payload = data.toString().trim()
            val name = eventName
            val id = lastId
            reset()
            if (payload.isEmpty()) return null
            val event = runCatching { AppJson.decodeFromString(StreamEvent.serializer(), payload) }.getOrNull()
                ?: return null
            if (id != null) lastEventId = id
            return if (name != null && event.type != name) event.copy(type = name) else event
        }
        when {
            line.startsWith(":") -> Unit // 注释 / 心跳
            line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart()).append('\n')
            line.startsWith("event:") -> eventName = line.removePrefix("event:").trim()
            line.startsWith("id:") -> lastId = line.removePrefix("id:").trim()
        }
        return null
    }

    private fun reset() {
        data.setLength(0)
        eventName = null
        lastId = null
    }
}
