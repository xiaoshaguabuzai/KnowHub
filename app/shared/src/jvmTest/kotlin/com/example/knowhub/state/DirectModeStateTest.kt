package com.example.knowhub.state

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectModeStateTest {

    @Test
    fun directModeStreamsAnswerWithoutServerLogin() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                listOf("直连", "回答", "成功").forEach { text ->
                    out.write("""data: {"choices":[{"delta":{"content":"$text"}}]}""" .plus("\n\n").toByteArray())
                    out.flush()
                }
                out.write("data: [DONE]\n\n".toByteArray())
                out.flush()
            }
        }
        server.start()
        try {
            val state = AppState()
            state.directApiKey = "test-key"
            state.directBaseUrl = "http://127.0.0.1:${server.address.port}/v1"
            state.directModel = "mock"
            state.startDirect()

            assertTrue(state.isDirect)
            assertTrue(state.loggedIn)
            assertNull(state.token)
            assertEquals("direct", state.currentSessionId)

            state.send(this, "你好")
            withTimeout(8_000) {
                while (state.streaming) delay(20)
            }

            assertEquals(2, state.messages.size, "应有一条用户消息与一条助手消息")
            assertEquals("你好", state.messages.first().content)
            assertEquals("直连回答成功", state.messages.last().content)
        } finally {
            server.stop(0)
        }
    }
}
