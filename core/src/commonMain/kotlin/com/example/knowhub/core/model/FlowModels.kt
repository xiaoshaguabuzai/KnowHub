package com.example.knowhub.core.model

import kotlinx.serialization.Serializable

object NodeTypes {
    const val COLLECT_PARAM = "collect_param"
    const val TOOL_INVOKE = "tool_invoke"
    const val BRANCH = "branch"
    const val SOURCE_REF = "source_ref"
    const val MESSAGE = "message"

    val all = setOf(COLLECT_PARAM, TOOL_INVOKE, BRANCH, SOURCE_REF, MESSAGE)
}

object FlowRunStatus {
    const val RUNNING = "RUNNING"
    const val WAITING_INPUT = "WAITING_INPUT"
    const val WAITING_TOOL = "WAITING_TOOL"
    const val DONE = "DONE"
    const val FAILED = "FAILED"
}

@Serializable
data class FlowVariable(
    val type: String = "string",
    val description: String = "",
    val required: Boolean = true,
)

/**
 * 节点配置：用强类型字段替代初版方案里的任意 object，
 * 让编排在编译期与校验期都能被约束。
 */
@Serializable
data class NodeConfig(
    /** collect_param：向用户提出的问题 */
    val prompt: String? = null,
    /** collect_param：写入的变量名 */
    val variable: String? = null,
    /** collect_param：校验规则 text / number / date / phone */
    val validation: String? = null,
    /** tool_invoke：工具 ID */
    val toolId: String? = null,
    /** tool_invoke：参数模板，支持 ${var} 占位 */
    val args: Map<String, String> = emptyMap(),
    /** tool_invoke：结果写入的变量名 */
    val resultVariable: String? = null,
    /** branch：默认条件（branchMap 为空时按 true/false 解释） */
    val condition: String? = null,
    /** message / source_ref：展示文本 */
    val text: String? = null,
    /** source_ref：检索词模板 */
    val query: String? = null,
)

@Serializable
data class FlowNode(
    val nodeId: String,
    val nodeType: String,
    val name: String = "",
    val config: NodeConfig = NodeConfig(),
    val nextNodeId: String? = null,
    /** 条件表达式 -> 目标节点；特殊键 _default 表示兜底 */
    val branchMap: Map<String, String> = emptyMap(),
)

@Serializable
data class FlowTemplate(
    val flowId: String,
    val name: String,
    val description: String = "",
    val triggerKeywords: List<String> = emptyList(),
    /** staff=仅职工可触发；all=患者也可触发（如门诊预约）。 */
    val audience: String = "staff",
    val version: Int = 1,
    val enabled: Boolean = true,
    val startNodeId: String? = null,
    val nodes: List<FlowNode> = emptyList(),
    val variables: Map<String, FlowVariable> = emptyMap(),
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

@Serializable
data class FlowRunState(
    val flowId: String,
    val currentNodeId: String,
    val vars: Map<String, String> = emptyMap(),
    val results: Map<String, String> = emptyMap(),
    val status: String = FlowRunStatus.RUNNING,
    val steps: Int = 0,
    val pendingVariable: String? = null,
    val startedAt: Long = 0,
)

@Serializable
data class ToolResult(
    val ok: Boolean,
    val data: Map<String, String> = emptyMap(),
    val message: String = "",
)

@Serializable
data class ToolInvokeRequest(val args: Map<String, String> = emptyMap())

@Serializable
data class ToolParam(
    val name: String,
    val type: String = "string",
    val required: Boolean = true,
    val description: String = "",
)

@Serializable
data class ToolInfo(
    val toolId: String,
    val name: String,
    val description: String,
    val params: List<ToolParam> = emptyList(),
)

// ============================ 意图 ============================

object Intents {
    const val KB = "kb"
    const val FLOW = "flow"
    const val TUNE = "tune"
    const val CHITCHAT = "chitchat"
}

data class IntentResult(
    val type: String,
    val confidence: Double,
    val flowId: String? = null,
    val reason: String = "",
)
