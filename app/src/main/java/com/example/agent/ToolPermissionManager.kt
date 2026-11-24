package com.example.agent

import com.example.agent.tools.ToolRegistry
import com.example.live.permissions.PermissionKind
import com.example.live.permissions.PermissionManager
import com.example.services.AgentCapability
import com.example.services.PermissionDecision
import com.example.services.PermissionService

data class PermissionCheckResult(
    val granted: Boolean,
    val missingPermissions: List<String> = emptyList(),
    val explanation: String = "",
    val hardBlocked: Boolean = false
)

class ToolPermissionManager(
    private val permissionManager: PermissionManager,
    private val permissionService: PermissionService? = null
) {
    suspend fun checkPermissionsForStep(userId: String, step: TaskStep): PermissionCheckResult {
        val androidCheck = checkPermissionsForStep(step)
        if (!androidCheck.granted) return androidCheck

        val policySvc = permissionService ?: return androidCheck
        val mappedCapability = mapToolToCapability(step.toolName) ?: return PermissionCheckResult(granted = true)
        val domain = step.args["url"]?.let { PermissionService.normalizeDomain(it) }
            ?: step.args["domain"]?.let { PermissionService.normalizeDomain(it) }
            ?: step.args["merchant"]?.let { PermissionService.normalizeDomain(it) }
        val amountMinor = step.args["subtotal_minor"]?.toLongOrNull()
            ?: step.args["total_minor"]?.toLongOrNull()
            ?: 0L

        return when (val decision = policySvc.evaluateAction(userId, mappedCapability, amountMinor, domain)) {
            PermissionDecision.Allowed -> PermissionCheckResult(granted = true)
            is PermissionDecision.Blocked -> PermissionCheckResult(
                granted = false,
                missingPermissions = listOf(mappedCapability.label),
                explanation = decision.reason,
                hardBlocked = true
            )
            is PermissionDecision.RequiresOwnerApproval -> {
                // Purchases & email sends pause via UserApprovalManager approval gate; other gated tools prompt in Permission Center
                if (step.requiresApproval || step.toolName == ToolRegistry.TOOL_EXECUTE_AUTHORIZED_PAYMENT || step.toolName == ToolRegistry.TOOL_EMAIL_SEND_AUTHORIZED) {
                    PermissionCheckResult(granted = true)
                } else {
                    PermissionCheckResult(
                        granted = false,
                        missingPermissions = listOf(mappedCapability.label),
                        explanation = decision.reason,
                        hardBlocked = false
                    )
                }
            }
        }
    }

    fun checkPermissionsForStep(step: TaskStep): PermissionCheckResult {
        if (step.requiredPermissions.isEmpty()) {
            return PermissionCheckResult(granted = true)
        }
        permissionManager.refresh()
        val missing = mutableListOf<String>()
        for (permName in step.requiredPermissions) {
            val kind = PermissionKind.entries.firstOrNull {
                it.name.equals(permName, ignoreCase = true) || it.androidPermission == permName
            }
            when {
                kind != null -> {
                    if (!permissionManager.isGranted(kind)) {
                        missing.add(kind.title)
                    }
                }
                permName.startsWith("android.permission.") -> {
                    if (!permissionManager.isAndroidPermissionGranted(permName)) {
                        missing.add(permName)
                    }
                }
                else -> {
                    missing.add(permName)
                }
            }
        }
        if (missing.isEmpty()) {
            return PermissionCheckResult(granted = true)
        }
        return PermissionCheckResult(
            granted = false,
            missingPermissions = missing,
            explanation = "Step '${step.title}' requires permission: ${missing.joinToString(", ")}. Grant it explicitly or cancel."
        )
    }

    private fun mapToolToCapability(toolName: String): AgentCapability? = when (toolName) {
        ToolRegistry.TOOL_WALLET_CHECK_STATUS,
        ToolRegistry.TOOL_PREPARE_PURCHASE_AUTHORIZATION -> AgentCapability.WALLET_ACCESS
        ToolRegistry.TOOL_EXECUTE_AUTHORIZED_PAYMENT -> AgentCapability.PURCHASE
        ToolRegistry.TOOL_EMAIL_SEARCH_READ -> AgentCapability.EMAIL_READ
        ToolRegistry.TOOL_EMAIL_SEND_AUTHORIZED -> AgentCapability.EMAIL_SEND
        ToolRegistry.TOOL_BROWSER_OPEN_SEARCH,
        ToolRegistry.TOOL_BROWSER_EXTRACT_COMPARE,
        ToolRegistry.TOOL_BROWSER_INTERACT,
        ToolRegistry.TOOL_WEB_SEARCH_RESEARCH,
        ToolRegistry.TOOL_FETCH_WEB_PAGE -> AgentCapability.BROWSER_ACCESS
        else -> null
    }
}
