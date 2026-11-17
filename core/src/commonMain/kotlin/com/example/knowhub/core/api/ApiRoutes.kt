package com.example.knowhub.core.api

/** 全端共用路由常量，避免客户端/服务端各写一份字符串。 */
object ApiRoutes {
    const val V1 = "/api/v1"

    const val LOGIN = "$V1/auth/login"
    const val ME = "$V1/auth/me"

    const val SESSIONS = "$V1/sessions"
    fun sessionMessages(sessionId: String) = "$SESSIONS/$sessionId/messages"
    fun session(sessionId: String) = "$SESSIONS/$sessionId"

    const val CHAT_STREAM = "$V1/chat/stream"
    const val ATTACHMENTS = "$V1/attachments"

    const val DOCUMENTS = "$V1/documents"
    const val DOCUMENTS_TEXT = "$V1/documents/text"
    fun document(docId: String) = "$DOCUMENTS/$docId"
    fun documentReindex(docId: String) = "$DOCUMENTS/$docId/reindex"

    const val FLOWS = "$V1/flows"
    fun flow(flowId: String) = "$FLOWS/$flowId"

    const val TOOLS = "$V1/tools"
    fun toolInvoke(toolId: String) = "$TOOLS/$toolId/invoke"

    const val RAG_CONFIG = "$V1/config/rag"
    const val STATS = "$V1/stats/overview"
    const val AUDIT = "$V1/audit"
    const val FEEDBACK = "$V1/feedback"
    const val HEALTH = "/healthz"
}

object AppDefaults {
    /** 真机通过 `adb reverse tcp:8080 tcp:8080` 访问本机服务 */
    const val BASE_URL = "http://127.0.0.1:8080"
    const val DEMO_USER = "demo"
    const val DEMO_PASSWORD = "demo1234"
    const val ADMIN_USER = "admin"
    const val ADMIN_PASSWORD = "admin1234"
    const val PATIENT_USER = "patient"
    const val PATIENT_PASSWORD = "patient1234"
    const val APP_NAME = "KnowHub"
}
