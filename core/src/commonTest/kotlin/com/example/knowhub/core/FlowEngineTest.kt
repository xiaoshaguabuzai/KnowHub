package com.example.knowhub.core

import com.example.knowhub.core.flow.FlowEvent
import com.example.knowhub.core.flow.FlowRunner
import com.example.knowhub.core.flow.FlowValidator
import com.example.knowhub.core.model.FlowNode
import com.example.knowhub.core.model.FlowRunStatus
import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.NodeConfig
import com.example.knowhub.core.model.NodeTypes
import com.example.knowhub.core.model.ToolResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FlowEngineTest {

    private val overtimeFlow = FlowTemplate(
        flowId = "flow_overtime",
        name = "加班申请登记",
        triggerKeywords = listOf("加班申请", "申请加班"),
        startNodeId = "n1",
        nodes = listOf(
            FlowNode(
                nodeId = "n1",
                nodeType = NodeTypes.COLLECT_PARAM,
                config = NodeConfig(
                    prompt = "请提供加班日期（YYYY-MM-DD）",
                    variable = "date",
                    validation = "date",
                ),
                nextNodeId = "n2",
            ),
            FlowNode(
                nodeId = "n2",
                nodeType = NodeTypes.COLLECT_PARAM,
                config = NodeConfig(
                    prompt = "请提供加班时长（小时）",
                    variable = "hours",
                    validation = "number",
                ),
                nextNodeId = "n3",
            ),
            FlowNode(
                nodeId = "n3",
                nodeType = NodeTypes.COLLECT_PARAM,
                config = NodeConfig(prompt = "请简述加班事由", variable = "reason"),
                nextNodeId = "n4",
            ),
            FlowNode(
                nodeId = "n4",
                nodeType = NodeTypes.TOOL_INVOKE,
                config = NodeConfig(
                    toolId = "create_overtime_request",
                    args = mapOf(
                        "date" to "\${date}",
                        "hours" to "\${hours}",
                        "reason" to "\${reason}",
                    ),
                    resultVariable = "overtime",
                ),
                nextNodeId = "n5",
            ),
            FlowNode(
                nodeId = "n5",
                nodeType = NodeTypes.MESSAGE,
                config = NodeConfig(text = "加班申请已提交：\${overtime}"),
            ),
        ),
    )

    @Test
    fun happyPathCollectsParamsThenCallsToolThenFinishes() {
        val runner = FlowRunner(overtimeFlow)

        val first = runner.start()
        assertEquals(FlowRunStatus.WAITING_INPUT, first.state.status)
        val ask = first.events.filterIsInstance<FlowEvent.Ask>().single()
        assertEquals("date", ask.variable)

        val second = runner.submitInput(first.state, "2026-11-20")
        assertEquals("hours", second.events.filterIsInstance<FlowEvent.Ask>().single().variable)

        val invalid = runner.submitInput(second.state, "三小时")
        assertEquals(FlowRunStatus.WAITING_INPUT, invalid.state.status)
        assertTrue(invalid.events.filterIsInstance<FlowEvent.Ask>().single().question.contains("输入无效"))

        val third = runner.submitInput(invalid.state, "3")
        assertEquals("reason", third.events.filterIsInstance<FlowEvent.Ask>().single().variable)

        val toolTick = runner.submitInput(third.state, "版本发布保障")
        val call = toolTick.events.filterIsInstance<FlowEvent.CallTool>().single()
        assertEquals("create_overtime_request", call.toolId)
        assertEquals("2026-11-20", call.args["date"])
        assertEquals("3", call.args["hours"])

        val finished = runner.submitToolResult(
            toolTick.state,
            ToolResult(ok = true, message = "加班单 OT-20261120-01 已创建，等待审批"),
        )
        assertEquals(FlowRunStatus.DONE, finished.state.status)
        val done = finished.events.filterIsInstance<FlowEvent.Done>().single()
        assertTrue(done.message.contains("OT-20261120-01"))
    }

    @Test
    fun validatorRejectsDanglingAndCyclicFlows() {
        val dangling = overtimeFlow.copy(
            startNodeId = "a",
            nodes = listOf(
                FlowNode(nodeId = "a", nodeType = NodeTypes.MESSAGE, config = NodeConfig(text = "hi"), nextNodeId = "missing")
            )
        )
        assertTrue(FlowValidator.validate(dangling).any { it.contains("不存在") })

        val cyclic = overtimeFlow.copy(
            startNodeId = "a",
            nodes = listOf(
                FlowNode(nodeId = "a", nodeType = NodeTypes.MESSAGE, config = NodeConfig(text = "a"), nextNodeId = "b"),
                FlowNode(nodeId = "b", nodeType = NodeTypes.MESSAGE, config = NodeConfig(text = "b"), nextNodeId = "a"),
            )
        )
        assertTrue(FlowValidator.validate(cyclic).any { it.contains("环") })
    }

    @Test
    fun validatorAcceptsSeededFlow() {
        assertTrue(FlowValidator.validate(overtimeFlow).isEmpty(), FlowValidator.validate(overtimeFlow).joinToString())
    }

    @Test
    fun branchChoosesTargetByCondition() {
        val flow = FlowTemplate(
            flowId = "flow_branch",
            name = "分支测试",
            startNodeId = "n1",
            nodes = listOf(
                FlowNode(
                    nodeId = "n1",
                    nodeType = NodeTypes.COLLECT_PARAM,
                    config = NodeConfig(prompt = "几天？", variable = "days", validation = "number"),
                    nextNodeId = "n2",
                ),
                FlowNode(
                    nodeId = "n2",
                    nodeType = NodeTypes.BRANCH,
                    branchMap = mapOf("days>3" to "n4", "_default" to "n3"),
                ),
                FlowNode(nodeId = "n3", nodeType = NodeTypes.MESSAGE, config = NodeConfig(text = "短期加班")),
                FlowNode(nodeId = "n4", nodeType = NodeTypes.MESSAGE, config = NodeConfig(text = "长期加班，需主管审批")),
            ),
        )
        val tick = FlowRunner(flow).start()
        val result = FlowRunner(flow).submitInput(tick.state, "5")
        assertEquals("长期加班，需主管审批", result.events.filterIsInstance<FlowEvent.Say>().single().text)
    }
}
