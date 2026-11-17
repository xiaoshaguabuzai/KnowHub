package com.example.knowhub.core.flow

import com.example.knowhub.core.model.FlowTemplate
import com.example.knowhub.core.model.NodeTypes

/**
 * 流程模板校验：节点类型、必填配置、悬空引用、环、不可达节点。
 * 对应技术验证报告 P0-6：Schema 必须可执行，而不是任意 object。
 */
object FlowValidator {

    fun validate(template: FlowTemplate): List<String> {
        val errors = ArrayList<String>()
        if (template.flowId.isBlank()) errors.add("flowId 不能为空")
        if (template.name.isBlank()) errors.add("name 不能为空")
        if (template.version < 1) errors.add("version 必须 ≥ 1")
        if (template.nodes.isEmpty()) {
            errors.add("nodes 不能为空")
            return errors
        }

        val ids = template.nodes.map { it.nodeId }
        if (ids.any { it.isBlank() }) errors.add("nodeId 不能为空")
        if (ids.size != ids.toSet().size) errors.add("nodeId 存在重复")

        val idSet = ids.toSet()
        val start = template.startNodeId ?: ids.first()
        if (start !in idSet) errors.add("startNodeId 不存在：$start")

        for (node in template.nodes) {
            if (node.nodeType !in NodeTypes.all) errors.add("节点 ${node.nodeId} 类型非法：${node.nodeType}")
            when (node.nodeType) {
                NodeTypes.COLLECT_PARAM -> {
                    if (node.config.variable.isNullOrBlank()) errors.add("参数节点 ${node.nodeId} 缺少 variable")
                    if (node.config.prompt.isNullOrBlank()) errors.add("参数节点 ${node.nodeId} 缺少 prompt")
                }
                NodeTypes.TOOL_INVOKE -> if (node.config.toolId.isNullOrBlank()) {
                    errors.add("工具节点 ${node.nodeId} 缺少 toolId")
                }
                NodeTypes.BRANCH -> if (node.branchMap.isEmpty()) {
                    errors.add("分支节点 ${node.nodeId} 缺少 branchMap")
                }
                NodeTypes.MESSAGE -> if (node.config.text.isNullOrBlank()) {
                    errors.add("消息节点 ${node.nodeId} 缺少 text")
                }
            }
            node.nextNodeId?.let { next ->
                if (next !in idSet) errors.add("节点 ${node.nodeId} 的 nextNodeId 不存在：$next")
            }
            for ((condition, target) in node.branchMap) {
                if (target !in idSet) errors.add("节点 ${node.nodeId} 的分支「$condition」目标不存在：$target")
            }
        }

        if (start in idSet) {
            if (hasCycle(template, start)) errors.add("流程存在环，无法保证终止")
            val reachable = reachable(template, start)
            val unreachable = ids.filter { it !in reachable }
            if (unreachable.isNotEmpty()) errors.add("存在不可达节点：${unreachable.joinToString("，")}")
        }
        return errors
    }

    private fun targets(template: FlowTemplate, nodeId: String): List<String> {
        val node = template.nodes.firstOrNull { it.nodeId == nodeId } ?: return emptyList()
        return listOfNotNull(node.nextNodeId) + node.branchMap.values
    }

    private fun hasCycle(template: FlowTemplate, start: String): Boolean {
        val visiting = HashSet<String>()
        val done = HashSet<String>()

        fun dfs(id: String): Boolean {
            if (id in done) return false
            if (!visiting.add(id)) return true
            for (next in targets(template, id)) {
                if (dfs(next)) return true
            }
            visiting.remove(id)
            done.add(id)
            return false
        }
        return dfs(start)
    }

    private fun reachable(template: FlowTemplate, start: String): Set<String> {
        val seen = HashSet<String>()
        fun walk(id: String) {
            if (!seen.add(id)) return
            for (next in targets(template, id)) walk(next)
        }
        walk(start)
        return seen
    }
}
