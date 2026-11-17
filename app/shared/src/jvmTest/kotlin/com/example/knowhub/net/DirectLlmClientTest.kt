package com.example.knowhub.net

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectLlmClientTest {

    @Test
    fun parsesOpenAiCompatibleSseDeltas() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { exchange ->
            exchange.responseHeaders.add("Content-Type", "text/event-stream")
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { out ->
                listOf("你好", "，", "直连").forEach { text ->
                    val chunk = """data: {"choices":[{"delta":{"content":"$text"}}]}""" + "\n\n"
                    out.write(chunk.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
                out.write("data: [DONE]\n\n".toByteArray())
                out.flush()
            }
        }
        server.start()
        val client = HttpClient(OkHttp)
        try {
            val direct = DirectLlmClient(
                http = client,
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                model = "mock",
            )
            val chunks = direct.stream(listOf("user" to "hi")).toList()
            assertEquals("你好，直连", chunks.joinToString(""))
        } finally {
            client.close()
            server.stop(0)
        }
    }
}
