package com.example.knowhub.core.flow

import kotlin.math.abs

/**
 * 流程表达式与参数模板的求值工具（不引入脚本引擎，保证多端可预测）。
 */
object FlowSupport {

    private val placeholder = Regex("\\$\\{([a-zA-Z0-9_]+)}")

    fun interpolate(text: String, vars: Map<String, String>, results: Map<String, String>): String {
        return placeholder.replace(text) { match ->
            val key = match.groupValues[1]
            vars[key] ?: results[key] ?: ""
        }
    }

    fun evalCondition(expr: String, vars: Map<String, String>, results: Map<String, String>): Boolean {
        val e = interpolate(expr, vars, results).trim()
        if (e.isEmpty()) return false
        if (e == "true") return true
        if (e == "false") return false

        if (e.startsWith("exists(") && e.endsWith(")")) {
            val name = e.removePrefix("exists(").removeSuffix(")").trim()
            return valueOf(name, vars, results).isNotBlank()
        }
        if (e.startsWith("contains(") && e.endsWith(")")) {
            val inner = e.removePrefix("contains(").removeSuffix(")")
            val parts = inner.split(",", limit = 2)
            if (parts.size != 2) return false
            return valueOf(parts[0].trim(), vars, results).contains(parts[1].trim().trim('"', '\''))
        }

        for (op in listOf(">=", "<=", "==", "!=", ">", "<")) {
            val idx = e.indexOf(op)
            if (idx <= 0) continue
            val left = valueOf(e.substring(0, idx).trim(), vars, results)
            val right = e.substring(idx + op.length).trim().trim('"', '\'')
            return compare(left, op, right)
        }
        return false
    }

    private fun valueOf(name: String, vars: Map<String, String>, results: Map<String, String>): String {
        val key = name.removePrefix("\${").removeSuffix("}").trim()
        return vars[key] ?: results[key] ?: ""
    }

    private fun compare(left: String, op: String, right: String): Boolean {
        val l = left.trim()
        val r = right.trim()
        val ln = l.toDoubleOrNull()
        val rn = r.toDoubleOrNull()
        if (ln != null && rn != null) {
            return when (op) {
                ">=" -> ln >= rn
                "<=" -> ln <= rn
                ">" -> ln > rn
                "<" -> ln < rn
                "==" -> abs(ln - rn) < 1e-9
                "!=" -> abs(ln - rn) >= 1e-9
                else -> false
            }
        }
        return when (op) {
            "==" -> l == r
            "!=" -> l != r
            ">=" -> l >= r
            "<=" -> l <= r
            ">" -> l > r
            "<" -> l < r
            else -> false
        }
    }

    fun validateInput(value: String, rule: String?): String? {
        val v = value.trim()
        if (v.isEmpty()) return "内容不能为空"
        return when (rule) {
            null, "", "text" -> null
            "number" -> if (v.toDoubleOrNull() == null) "请输入数字" else null
            "date" -> if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(v)) "请按 YYYY-MM-DD 格式输入日期" else null
            "phone" -> if (!Regex("^1\\d{10}$").matches(v)) "请输入 11 位手机号" else null
            else -> null
        }
    }
}
