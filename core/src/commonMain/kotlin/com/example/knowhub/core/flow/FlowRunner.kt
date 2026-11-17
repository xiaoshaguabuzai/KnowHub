package com.example.knowhub.core.flow

import com.example.knowhub.core.model.FlowNode
import com.example.knowhub.core.model.FlowRunState
import com.example.knowhub.core.model.FlowRunStatus
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.NodeTypes
import com.example.knowhub.core.model.ToolResult

sealed class FlowEvent {
    data class Ask(val nodeId: String, val variable: String, val question: String) : FlowEvent()
    data class Say(val nodeId: String, val text: String) : FlowEvent()
    data class CallTool(
        val nodeId: String,
        val toolId: String,
        val args: Map<String, String>,
        val resultVariable: String?,
    ) : FlowEvent()

    data class Done(val nodeId: String, val message: String) : FlowEvent()
    data class Failed(val nodeId: String, val message: String) : FlowEvent()
}

data class FlowTick(val state: FlowRunState, val events: List<FlowEvent>)

/**
 * 流程执行引擎：状态机式推进，一次 tick 内连续执行到「需要输入 / 需要工具 / 结束」为止。
 * 状态可序列化，天然支持断点续跑（服务端按 session 持久化）。
 */
class FlowRunner(private val template: FlowTemplate) {

    private val nodes: Map<String, FlowNode> = template.nodes.associateBy { it.nodeId }
    private val startId: String? = template.startNodeId ?: template.nodes.firstOrNull()?.nodeId

    fun start(now: Long = 0L): FlowTick {
        val id = startId
            ?: return FlowTick(
                FlowRunState(flowId = template.flowId, currentNodeId = "", status = FlowRunStatus.FAILED),
                listOf(FlowEvent.Failed("", "流程未配置起始节点")),
            )
        return advance(FlowRunState(flowId = template.flowId, currentNodeId = id, startedAt = now))
    }

    fun submitInput(state: FlowRunState, answer: String): FlowTick {
        val node = nodes[state.currentNodeId]
            ?: return fail(state, "当前节点不存在：${state.currentNodeId}")
        if (node.nodeType != NodeTypes.COLLECT_PARAM) {
            return fail(state, "当前节点不是参数收集节点")
        }
        val variable = node.config.variable
            ?: return fail(state, "参数节点未配置 variable")
        val error = FlowSupport.validateInput(answer, node.config.validation)
        if (error != null) {
            val question = FlowSupport.interpolate(
                node.config.prompt ?: "请提供 $variable",
                state.vars,
                state.results,
            )
            return FlowTick(
                state.copy(status = FlowRunStatus.WAITING_INPUT, pendingVariable = variable),
                listOf(FlowEvent.Ask(node.nodeId, variable, "$question\n（输入无效：$error）")),
            )
        }
        val newVars = state.vars + (variable to answer.trim())
        val next = node.nextNodeId
            ?: return done(state.copy(vars = newVars), listOf(), "已收到：${answer.trim()}")
        return advance(state.copy(vars = newVars, currentNodeId = next, steps = state.steps + 1))
    }

    fun submitToolResult(state: FlowRunState, result: ToolResult): FlowTick {
        val node = nodes[state.currentNodeId]
            ?: return fail(state, "当前节点不存在：${state.currentNodeId}")
        if (node.nodeType != NodeTypes.TOOL_INVOKE) {
            return fail(state, "当前节点不是工具调用节点")
        }
        val variable = node.config.resultVariable ?: node.nodeId
        val payload = if (result.message.isNotBlank()) {
            result.message
        } else {
            result.data.entries.joinToString("，") { "${it.key}=${it.value}" }
        }
        val newResults = state.results + (variable to payload)
        val next = node.nextNodeId
            ?: return done(state.copy(results = newResults), listOf(), payload)
        return advance(state.copy(results = newResults, currentNodeId = next, steps = state.steps + 1))
    }

    private fun advance(initial: FlowRunState): FlowTick {
        var state = initial.copy(status = FlowRunStatus.RUNNING, pendingVariable = null)
        val events = ArrayList<FlowEvent>()
        var guard = 0
        while (guard++ < MAX_STEPS) {
            val node = nodes[state.currentNodeId]
                ?: return fail(state.copy(status = FlowRunStatus.FAILED), "节点不存在：${state.currentNodeId}", events)
            when (node.nodeType) {
                NodeTypes.COLLECT_PARAM -> {
                    val variable = node.config.variable
                        ?: return fail(state, "参数节点 ${node.nodeId} 未配置 variable", events)
                    if (state.vars.containsKey(variable)) {
                        val next = node.nextNodeId
                        if (next == null) return done(state, events, "")
                        state = state.copy(currentNodeId = next, steps = state.steps + 1)
                        continue
                    }
                    val question = FlowSupport.interpolate(
                        node.config.prompt ?: "请提供 $variable",
                        state.vars,
                        state.results,
                    )
                    events.add(FlowEvent.Ask(node.nodeId, variable, question))
                    return FlowTick(
                        state.copy(
                            status = FlowRunStatus.WAITING_INPUT,
                            pendingVariable = variable,
                            steps = state.steps + 1,
                        ),
                        events,
                    )
                }

                NodeTypes.MESSAGE -> {
                    val text = FlowSupport.interpolate(node.config.text ?: "", state.vars, state.results)
                    if (text.isNotBlank()) events.add(FlowEvent.Say(node.nodeId, text))
                    val next = node.nextNodeId ?: return done(state, events, text)
                    state = state.copy(currentNodeId = next, steps = state.steps + 1)
                }

                NodeTypes.SOURCE_REF -> {
                    val text = FlowSupport.interpolate(
                        node.config.text ?: node.config.query ?: "",
                        state.vars,
                        state.results,
                    )
                    if (text.isNotBlank()) events.add(FlowEvent.Say(node.nodeId, text))
                    val next = node.nextNodeId ?: return done(state, events, text)
                    state = state.copy(currentNodeId = next, steps = state.steps + 1)
                }

                NodeTypes.BRANCH -> {
                    val target = chooseTarget(node, state)
                        ?: return fail(state, "分支节点 ${node.nodeId} 未命中任何条件", events)
                    state = state.copy(currentNodeId = target, steps = state.steps + 1)
                }

                NodeTypes.TOOL_INVOKE -> {
                    val toolId = node.config.toolId
                        ?: return fail(state, "工具节点 ${node.nodeId} 未配置 toolId", events)
                    val args = node.config.args.mapValues { (_, value) ->
                        FlowSupport.interpolate(value, state.vars, state.results)
                    }
                    events.add(FlowEvent.CallTool(node.nodeId, toolId, args, node.config.resultVariable))
                    return FlowTick(state.copy(status = FlowRunStatus.WAITING_TOOL, steps = state.steps + 1), events)
                }

                else -> return fail(state, "未知节点类型：${node.nodeType}", events)
            }
        }
        return fail(state, "流程执行步数超限，已中止", events)
    }

    private fun chooseTarget(node: FlowNode, state: FlowRunState): String? {
        for ((condition, target) in node.branchMap) {
            if (condition == DEFAULT_BRANCH) continue
            if (FlowSupport.evalCondition(condition, state.vars, state.results)) return target
        }
        return node.branchMap[DEFAULT_BRANCH]
    }

    private fun done(state: FlowRunState, events: List<FlowEvent>, message: String): FlowTick {
        val text = message.ifBlank {
            events.filterIsInstance<FlowEvent.Say>().joinToString("\n") { it.text }
        }
        val all = events + FlowEvent.Done(state.currentNodeId, text)
        return FlowTick(state.copy(status = FlowRunStatus.DONE, pendingVariable = null), all)
    }

    private fun fail(state: FlowRunState, message: String, events: List<FlowEvent> = listOf()): FlowTick {
        val all = events + FlowEvent.Failed(state.currentNodeId, message)
        return FlowTick(state.copy(status = FlowRunStatus.FAILED), all)
    }

    companion object {
        const val MAX_STEPS = 24
        const val DEFAULT_BRANCH = "_default"
    }
}
