package com.example.agent

import com.example.agent.tools.ToolRegistry
import com.example.core.SecurityUtils
import org.json.JSONArray
import org.json.JSONObject

class TaskDecomposer(
    private val toolRegistry: ToolRegistry
) {

    fun parseAiPlan(
        taskId: String,
        goal: String,
        rawJson: String
    ): TaskPlan? {
        val obj = runCatching { JSONObject(TaskUnderstandingEngine.cleanJsonFences(rawJson)) }.getOrNull()
            ?: return null
        val rationale = obj.optString("rationale").trim()
        val stepsArr = obj.optJSONArray("steps") ?: return null
        if (stepsArr.length() == 0) return null

        val rawSteps = mutableListOf<TaskStep>()
        for (i in 0 until stepsArr.length().coerceAtMost(MAX_PLAN_STEPS)) {
            val s = stepsArr.optJSONObject(i) ?: continue
            val toolName = s.optString("toolName").trim()
            val spec = toolRegistry.getSpec(toolName) ?: continue
            val stepId = s.optString("stepId").trim().ifBlank { "step_${i + 1}" }
            val title = s.optString("title").trim().ifBlank { spec.name }
            val desc = s.optString("description").trim().ifBlank { spec.description }
            val args = parseArgsArray(s.optJSONArray("args"))
            val dependsOn = parseStringArray(s.optJSONArray("dependsOn"))
            val parallelGroup = s.optString("parallelGroup").trim().takeIf { it.isNotBlank() }
            val expectedOutcome = s.optString("expectedOutcome").trim()

            rawSteps.add(
                enrichStep(
                    taskId = taskId,
                    goal = goal,
                    index = rawSteps.size,
                    stepId = stepId,
                    title = title,
                    description = desc,
                    toolName = toolName,
                    args = args,
                    dependsOn = dependsOn,
                    parallelGroup = parallelGroup,
                    expectedOutcome = expectedOutcome
                )
            )
        }

        if (rawSteps.isEmpty()) return null
        val validated = validateAndOrderDag(rawSteps)
        return TaskPlan(steps = validated, rationale = rationale)
    }

    fun buildValidatedPlan(
        taskId: String,
        goal: String,
        rawSteps: List<TaskStep>,
        rationale: String
    ): TaskPlan {
        val enriched = rawSteps.mapIndexed { idx, s ->
            enrichStep(
                taskId = taskId,
                goal = goal,
                index = idx,
                stepId = s.stepId.ifBlank { "step_${idx + 1}" },
                title = s.title,
                description = s.description,
                toolName = s.toolName,
                args = s.args,
                dependsOn = s.dependsOn,
                parallelGroup = s.parallelGroup,
                expectedOutcome = s.expectedOutcome
            )
        }
        return TaskPlan(steps = validateAndOrderDag(enriched), rationale = rationale)
    }

    fun enrichStep(
        taskId: String,
        goal: String,
        index: Int,
        stepId: String,
        title: String,
        description: String,
        toolName: String,
        args: Map<String, String>,
        dependsOn: List<String>,
        parallelGroup: String? = null,
        expectedOutcome: String = ""
    ): TaskStep {
        val spec = toolRegistry.getSpec(toolName)
        val requiresApproval = spec?.requiresApproval == true || isSensitiveIntent(goal, toolName, args)
        val riskLevel = when {
            spec == null -> TaskRiskLevel.BLOCKED_PROHIBITED
            requiresApproval -> TaskRiskLevel.HIGH_APPROVAL_REQUIRED
            else -> spec.riskLevel
        }
        val keySeed = "$taskId|$stepId|$toolName|${args.entries.sortedBy { it.key }.joinToString("&") { "${it.key}=${it.value}" }}"
        val idempotencyKey = SecurityUtils.sha256Hex(keySeed).take(24)

        return TaskStep(
            stepId = stepId,
            index = index,
            title = title.take(140),
            description = description.take(400),
            toolName = toolName,
            args = args,
            dependsOn = dependsOn,
            parallelGroup = parallelGroup,
            isIdempotent = spec?.isIdempotent ?: false,
            requiresApproval = requiresApproval,
            riskLevel = riskLevel,
            requiredPermissions = spec?.requiredPermissions ?: emptyList(),
            expectedOutcome = expectedOutcome.ifBlank { "Verified completion of $toolName" },
            status = StepStatus.PENDING,
            maxRetries = if (spec?.isIdempotent == true) 2 else 0,
            idempotencyKey = idempotencyKey
        )
    }

    private fun validateAndOrderDag(steps: List<TaskStep>): List<TaskStep> {
        val validIds = steps.map { it.stepId }.toSet()
        val sanitized = steps.map { step ->
            step.copy(
                dependsOn = step.dependsOn.filter { dep -> dep in validIds && dep != step.stepId }.distinct()
            )
        }

        // Topological sort (Kahn's algorithm) breaking any residual cycle sequentially
        val remaining = sanitized.toMutableList()
        val placed = mutableListOf<TaskStep>()
        val placedIds = mutableSetOf<String>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { step -> step.dependsOn.all { it in placedIds } }
            if (ready.isNotEmpty()) {
                for (r in ready) {
                    placed.add(r.copy(index = placed.size))
                    placedIds.add(r.stepId)
                    remaining.remove(r)
                }
            } else {
                // Break cycle safely by clearing unresolved dependency on the next step
                val next = remaining.removeAt(0)
                val broken = next.copy(
                    index = placed.size,
                    dependsOn = next.dependsOn.filter { it in placedIds }
                )
                placed.add(broken)
                placedIds.add(broken.stepId)
            }
        }
        return placed
    }

    private fun isSensitiveIntent(goal: String, toolName: String, args: Map<String, String>): Boolean {
        if (toolName in setOf(
                ToolRegistry.TOOL_ANDROID_PREPARE_EMAIL,
                ToolRegistry.TOOL_ANDROID_PREPARE_SMS,
                ToolRegistry.TOOL_ANDROID_CALENDAR_EVENT,
                ToolRegistry.TOOL_ANDROID_SET_ALARM,
                ToolRegistry.TOOL_ANDROID_SHARE,
                ToolRegistry.TOOL_EXECUTE_AUTHORIZED_PAYMENT,
                ToolRegistry.TOOL_EMAIL_SEND_AUTHORIZED
            )
        ) {
            return true
        }
        val readOnlyOrSafeStagingTools = setOf(
            ToolRegistry.TOOL_WALLET_CHECK_STATUS,
            ToolRegistry.TOOL_PREPARE_PURCHASE_AUTHORIZATION,
            ToolRegistry.TOOL_BROWSER_OPEN_SEARCH,
            ToolRegistry.TOOL_BROWSER_EXTRACT_COMPARE,
            ToolRegistry.TOOL_WEB_SEARCH_RESEARCH,
            ToolRegistry.TOOL_FETCH_WEB_PAGE,
            ToolRegistry.TOOL_COMPARE_AND_SUMMARIZE,
            ToolRegistry.TOOL_CREATE_RESEARCH_REPORT,
            ToolRegistry.TOOL_GET_WEATHER,
            ToolRegistry.TOOL_SEARCH_PLACES,
            ToolRegistry.TOOL_LIST_NOTES,
            ToolRegistry.TOOL_QUERY_TASK_HISTORY,
            ToolRegistry.TOOL_CALCULATE_VALUE,
            ToolRegistry.TOOL_GET_DEVICE_STATUS,
            ToolRegistry.TOOL_EXTERNAL_CONNECTOR_STATUS,
            ToolRegistry.TOOL_EMAIL_PREPARE_DRAFT,
            ToolRegistry.TOOL_EMAIL_SEARCH_READ
        )
        if (toolName in readOnlyOrSafeStagingTools) {
            return false
        }
        val argsText = args.values.joinToString(" ").lowercase()
        val goalLower = goal.lowercase()
        return argsText.contains("purchase") || argsText.contains("payment") ||
            argsText.contains("delete") || goalLower.contains("delete")
    }

    private fun parseArgsArray(arr: JSONArray?): Map<String, String> {
        if (arr == null) return emptyMap()
        val map = linkedMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val k = item.optString("key").trim()
            val v = item.optString("value")
            if (k.isNotBlank()) map[k] = v
        }
        return map
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotBlank()) list.add(s)
        }
        return list
    }

    private companion object {
        const val MAX_PLAN_STEPS = 10
    }
}
