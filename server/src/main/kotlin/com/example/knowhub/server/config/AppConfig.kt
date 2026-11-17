package com.example.knowhub.server.config

import java.io.File

/**
 * 服务端配置全部来自环境变量，默认值保证开箱可跑（无大模型时降级为检索式回答）。
 */
data class AppConfig(
    val port: Int = env("KNOWHUB_PORT")?.toIntOrNull() ?: env("PORT")?.toIntOrNull() ?: 8080,
    val host: String = env("KNOWHUB_HOST") ?: "0.0.0.0",
    val dataDir: String = env("KNOWHUB_DATA_DIR") ?: "data",
    val llmBaseUrl: String? = env("KNOWHUB_LLM_BASE_URL"),
    val llmApiKey: String? = env("KNOWHUB_LLM_API_KEY"),
    val llmModel: String = env("KNOWHUB_LLM_MODEL") ?: "deepseek-chat",
    val tokenSecret: String? = env("KNOWHUB_TOKEN_SECRET"),
    val maxUploadBytes: Long = env("KNOWHUB_MAX_UPLOAD_MB")?.toLongOrNull()?.times(1024 * 1024) ?: 20L * 1024 * 1024,
) {
    val llmEnabled: Boolean get() = !llmBaseUrl.isNullOrBlank()

    fun dataDirectory(): File = File(dataDir).apply { mkdirs() }

    companion object {
        private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
    }
}
