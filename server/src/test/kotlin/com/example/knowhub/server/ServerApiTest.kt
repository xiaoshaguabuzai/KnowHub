package com.example.knowhub.server

import com.example.knowhub.core.AppJson
import com.example.knowhub.core.api.ApiRoutes
import com.example.knowhub.core.model.DocumentDto
import com.example.knowhub.core.model.DocumentStatus
import com.example.knowhub.core.model.LoginRequest
import com.example.knowhub.core.model.LoginResponse
import com.example.knowhub.core.model.SessionDto
import com.example.knowhub.core.model.StreamEvent
import com.example.knowhub.core.model.StreamEventTypes
import com.example.knowhub.core.sse.SseParser
import com.example.knowhub.server.config.AppConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServerApiTest {

    private fun tempConfig(): AppConfig {
        val dir = Files.createTempDirectory("knowhub-test").toFile()
        return AppConfig(dataDir = dir.absolutePath, tokenSecret = "test-secret")
    }

    private suspend fun login(client: HttpClient, username: String, password: String): LoginResponse {
        val response = client.post(ApiRoutes.LOGIN) {
            contentType(ContentType.Application.Json)
            setBody(AppJson.encodeToString(LoginRequest.serializer(), LoginRequest(username, password)))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return AppJson.decodeFromString(LoginResponse.serializer(), response.bodyAsText())
    }

    private suspend fun HttpClient.postJson(path: String, body: String, token: String? = null): HttpResponse =
        post(path) {
            contentType(ContentType.Application.Json)
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            setBody(body)
        }

    private suspend fun HttpClient.createSession(token: String): SessionDto {
        val response = postJson(ApiRoutes.SESSIONS, "{}", token)
        assertEquals(HttpStatusCode.Created, response.status)
        return AppJson.decodeFromString(SessionDto.serializer(), response.bodyAsText())
    }

    private fun parseEvents(raw: String): List<StreamEvent> {
        val parser = SseParser()
        val events = ArrayList<StreamEvent>()
        for (line in raw.split("\n")) {
            parser.feed(line.trimEnd('\r'))?.let { events.add(it) }
        }
        return events
    }

    private suspend fun HttpClient.chat(token: String, sessionId: String, message: String): List<StreamEvent> {
        val body = AppJson.encodeToString(
            com.example.knowhub.core.model.ChatRequest.serializer(),
            com.example.knowhub.core.model.ChatRequest(sessionId, message),
        )
        val response = postJson(ApiRoutes.CHAT_STREAM, body, token)
        assertEquals(HttpStatusCode.OK, response.status)
        return parseEvents(response.bodyAsText())
    }

    private fun List<StreamEvent>.answerText(): String =
        filter { it.type == StreamEventTypes.TEXT }.joinToString("") { it.content.orEmpty() }

    @Test
    fun healthAndLoginWork() = testApplication {
        application { module(tempConfig()) }
        val health = client.get(ApiRoutes.HEALTH)
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.bodyAsText().contains("\"status\":\"ok\""))

        val login = login(client, "demo", "demo1234")
        assertEquals("demo", login.user.username)
        assertTrue(login.token.isNotBlank())
    }

    @Test
    fun unauthorizedAndPermissionEnforced() = testApplication {
        application { module(tempConfig()) }
        val noToken = client.get(ApiRoutes.SESSIONS)
        assertEquals(HttpStatusCode.Unauthorized, noToken.status)

        val member = login(client, "demo", "demo1234")
        val upload = client.postJson(ApiRoutes.DOCUMENTS_TEXT, """{"name":"x.md","content":"hello"}""", member.token)
        assertEquals(HttpStatusCode.Forbidden, upload.status)
    }

    @Test
    fun hospitalKnowledgeAnswerStreamsSourcesAndAcl() = testApplication {
        application { module(tempConfig()) }
        val demo = login(client, "demo", "demo1234")
        val session = client.createSession(demo.token)

        val events = client.chat(demo.token, session.sessionId, "危急值要在多久内上报？")
        val types = events.map { it.type }
        assertTrue(types.contains(StreamEventTypes.META))
        assertTrue(types.contains(StreamEventTypes.SOURCE))
        assertTrue(types.contains(StreamEventTypes.TEXT))
        assertTrue(types.contains(StreamEventTypes.DONE))

        val sourceDocIds = events.flatMap { it.sources ?: emptyList() }.map { it.docId }
        assertTrue(sourceDocIds.contains("doc_core_rules"), "应命中医疗核心制度：$sourceDocIds")
        assertTrue(sourceDocIds.none { it == "doc_hr_salary" }, "普通职工不应检索到人事薪酬制度")

        assertTrue(events.answerText().contains("危急值"))
    }

    @Test
    fun hrCanSeeSalaryPolicyAndAdminOnlyDocStaysHidden() = testApplication {
        application { module(tempConfig()) }
        val hr = login(client, "hr", "hr1234")
        val session = client.createSession(hr.token)
        val events = client.chat(hr.token, session.sessionId, "职工薪酬保密有哪些要求？")
        val docIds = events.flatMap { it.sources ?: emptyList() }.map { it.docId }
        assertTrue(docIds.contains("doc_hr_salary"), "人事科应命中薪酬保密办法：$docIds")
        assertTrue(docIds.none { it == "doc_med_dispute" }, "人事科不应看到医务处内部指引")
    }

    @Test
    fun admissionFlowCollectsParamsAndInvokesTool() = testApplication {
        application { module(tempConfig()) }
        val demo = login(client, "demo", "demo1234")
        val session = client.createSession(demo.token)

        val first = client.chat(demo.token, session.sessionId, "我要办理住院登记")
        assertTrue(first.any { it.type == StreamEventTypes.FLOW })
        assertTrue(first.answerText().contains("患者姓名"))

        val second = client.chat(demo.token, session.sessionId, "李建国")
        assertTrue(second.answerText().contains("住院证号"))

        val third = client.chat(demo.token, session.sessionId, "ZY20261117001")
        assertTrue(third.answerText().contains("收治科室"))

        val fourth = client.chat(demo.token, session.sessionId, "心内科")
        val fourthText = fourth.answerText()
        assertTrue(fourthText.contains("ADM-"), "流程应创建住院登记：$fourthText")
        assertTrue(fourth.any { it.type == StreamEventTypes.DONE })

        val messages = client.get(ApiRoutes.sessionMessages(session.sessionId)) {
            header(HttpHeaders.Authorization, "Bearer ${demo.token}")
        }
        assertEquals(HttpStatusCode.OK, messages.status)
        assertTrue(messages.bodyAsText().contains("ADM-"))
    }

    @Test
    fun adminCanUploadTextDocumentAndItBecomesSearchable() = testApplication {
        application { module(tempConfig()) }
        val admin = login(client, "admin", "admin1234")
        val upload = client.postJson(
            ApiRoutes.DOCUMENTS_TEXT,
            """{"name":"门诊号源管理规定.md","content":"门诊号源提前 7 天开放预约，现场号源不超过总号源的 30%，70 岁以上老人可优先就诊。"}""",
            admin.token,
        )
        assertEquals(HttpStatusCode.Accepted, upload.status)

        var indexed: DocumentDto? = null
        repeat(40) {
            val response = client.get(ApiRoutes.DOCUMENTS) {
                header(HttpHeaders.Authorization, "Bearer ${admin.token}")
            }
            val docs = AppJson.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(DocumentDto.serializer()),
                response.bodyAsText(),
            )
            indexed = docs.firstOrNull { it.name == "门诊号源管理规定.md" && it.status == DocumentStatus.INDEXED }
            if (indexed == null) delay(100)
        }
        assertTrue(indexed != null, "文档应完成索引")

        val session = client.createSession(admin.token)
        val events = client.chat(admin.token, session.sessionId, "门诊号源提前几天开放预约？")
        val docNames = events.flatMap { it.sources ?: emptyList() }.map { it.docName }
        assertTrue(docNames.contains("门诊号源管理规定.md"), "新上传文档应可被检索：$docNames")

        val stats = client.get(ApiRoutes.STATS) { header(HttpHeaders.Authorization, "Bearer ${admin.token}") }
        assertEquals(HttpStatusCode.OK, stats.status)
        assertTrue(stats.bodyAsText().contains("\"documentCount\""))
    }

    @Test
    fun documentContentIsReadableWithAcl() = testApplication {
        application { module(tempConfig()) }
        val demo = login(client, "demo", "demo1234")
        val visible = client.get(ApiRoutes.document("doc_outpatient")) {
            header(HttpHeaders.Authorization, "Bearer ${demo.token}")
        }
        assertEquals(HttpStatusCode.OK, visible.status)
        val detail = AppJson.decodeFromString(
            com.example.knowhub.core.model.DocumentDetailDto.serializer(),
            visible.bodyAsText(),
        )
        assertTrue(detail.content.contains("首诊"), "文档正文应可读")
        assertTrue(detail.doc.docId == "doc_outpatient")
        assertEquals("YW-ZD-001", detail.doc.docNo)
        assertEquals("医务处", detail.doc.department)
        assertEquals("2026-01-01", detail.doc.effectiveDate)

        val restricted = client.get(ApiRoutes.document("doc_hr_salary")) {
            header(HttpHeaders.Authorization, "Bearer ${demo.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, restricted.status)

        val hr = login(client, "hr", "hr1234")
        val hrView = client.get(ApiRoutes.document("doc_hr_salary")) {
            header(HttpHeaders.Authorization, "Bearer ${hr.token}")
        }
        assertEquals(HttpStatusCode.OK, hrView.status)
    }

    @Test
    fun patientSeesGuidesOnlyAndCannotUseStaffFlows() = testApplication {
        application { module(tempConfig()) }
        val patient = login(client, "patient", "patient1234")
        assertTrue(patient.user.roles.contains("patient"))

        val docs = client.get(ApiRoutes.DOCUMENTS) {
            header(HttpHeaders.Authorization, "Bearer ${patient.token}")
        }.bodyAsText()
        assertTrue(docs.contains("doc_guide_appointment"), "患者应能看到就医指南")
        assertTrue(!docs.contains("doc_core_rules"), "患者不应看到院内核心制度")

        val internal = client.get(ApiRoutes.document("doc_core_rules")) {
            header(HttpHeaders.Authorization, "Bearer ${patient.token}")
        }
        assertEquals(HttpStatusCode.Forbidden, internal.status)

        val flows = client.get(ApiRoutes.FLOWS) {
            header(HttpHeaders.Authorization, "Bearer ${patient.token}")
        }.bodyAsText()
        assertTrue(flows.contains("flow_appointment"), "患者应看到门诊预约流程")
        assertTrue(!flows.contains("flow_admission"), "患者不应看到住院登记流程")

        val session = client.createSession(patient.token)
        val answer = client.chat(patient.token, session.sessionId, "抽血前能吃饭吗？").answerText()
        assertTrue(answer.contains("空腹") || answer.contains("禁食"), "患者指南应命中检验注意事项：$answer")
        assertTrue(answer.contains("就医"), "患者回答应带就医提示：$answer")
    }

    @Test
    fun attachmentTextIsUsedInAnswer() = testApplication {
        application { module(tempConfig()) }
        val demo = login(client, "demo", "demo1234")
        val session = client.createSession(demo.token)

        val content = "检验报告：患者住院号 ZY20261117001，血钾 6.8 mmol/L，提示高钾血症，建议立即复查并通知主管医师。"
        val upload = client.post(ApiRoutes.ATTACHMENTS) {
            header(HttpHeaders.Authorization, "Bearer ${demo.token}")
            setBody(
                io.ktor.client.request.forms.MultiPartFormDataContent(
                    io.ktor.client.request.forms.formData {
                        append(
                            "file",
                            content.toByteArray(),
                            Headers.build {
                                append(HttpHeaders.ContentType, "text/plain")
                                append(HttpHeaders.ContentDisposition, "filename=\"lab-report.txt\"")
                            },
                        )
                    }
                )
            )
        }
        assertEquals(HttpStatusCode.Created, upload.status, upload.bodyAsText())
        val attachment = AppJson.decodeFromString(
            com.example.knowhub.core.model.AttachmentDto.serializer(),
            upload.bodyAsText(),
        )
        assertTrue(attachment.hasText)
        assertTrue(attachment.chars > 10)

        val body = AppJson.encodeToString(
            com.example.knowhub.core.model.ChatRequest.serializer(),
            com.example.knowhub.core.model.ChatRequest(
                sessionId = session.sessionId,
                message = "这份检验报告有什么问题？",
                attachmentIds = listOf(attachment.attachmentId),
            ),
        )
        val response = client.postJson(ApiRoutes.CHAT_STREAM, body, demo.token)
        assertEquals(HttpStatusCode.OK, response.status)
        val events = parseEvents(response.bodyAsText())
        val answer = events.answerText()
        assertTrue(answer.contains("6.8"), "回答应引用附件内容：$answer")
        assertTrue(events.flatMap { it.sources ?: emptyList() }.any { it.docId == attachment.attachmentId })
    }

    @Test
    fun feedbackCreatesTuneSample() = testApplication {
        application { module(tempConfig()) }
        val demo = login(client, "demo", "demo1234")
        val session = client.createSession(demo.token)
        val events = client.chat(demo.token, session.sessionId, "抗菌药物特殊使用级怎么开具？")
        val done = events.last { it.type == StreamEventTypes.DONE }
        val source = done.sources?.firstOrNull()
        assertTrue(source != null, "回答应包含来源")

        val assistantId = done.messageId
        assertTrue(assistantId != null)

        val response = client.postJson(
            ApiRoutes.FEEDBACK,
            """{"messageId":"$assistantId","rating":"up","chunkId":"${source!!.chunkId}"}""",
            demo.token,
        )
        assertEquals(HttpStatusCode.Created, response.status)

        val hr = login(client, "hr", "hr1234")
        val audit = client.get(ApiRoutes.AUDIT) { header(HttpHeaders.Authorization, "Bearer ${hr.token}") }
        assertEquals(HttpStatusCode.Forbidden, audit.status)
    }
}
