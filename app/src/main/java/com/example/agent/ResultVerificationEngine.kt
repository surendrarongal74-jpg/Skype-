package com.example.agent

import com.example.agent.tools.ToolRegistry

class ResultVerificationEngine {

    fun verify(step: TaskStep, result: ToolResult): VerificationOutcome {
        if (!result.success) {
            val isTransient = result.failureKind == ToolFailureKind.TRANSIENT_NETWORK
            val shouldReplan = result.failureKind == ToolFailureKind.NOT_CONNECTED ||
                (step.toolName == ToolRegistry.TOOL_FETCH_WEB_PAGE && result.failureKind == ToolFailureKind.EXECUTION_ERROR)
            return VerificationOutcome(
                passed = false,
                verdict = "Step failed (${result.failureKind?.name ?: "ERROR"})",
                shouldRetryIfTransient = isTransient,
                shouldReplan = shouldReplan,
                failureReason = result.errorMessage ?: result.summary
            )
        }

        if (result.summary.isBlank()) {
            return VerificationOutcome(
                passed = false,
                verdict = "Empty result returned",
                shouldRetryIfTransient = true,
                failureReason = "Tool '${step.toolName}' reported success with empty output."
            )
        }

        return when (step.toolName) {
            ToolRegistry.TOOL_WEB_SEARCH_RESEARCH -> {
                if (result.sources.isEmpty()) {
                    VerificationOutcome(
                        passed = false,
                        verdict = "Zero search sources retrieved",
                        shouldRetryIfTransient = false,
                        shouldReplan = false,
                        failureReason = "Web search returned no verifiable URLs or snippets."
                    )
                } else {
                    VerificationOutcome(
                        passed = true,
                        verdict = "Verified ${result.sources.size} source(s) retrieved"
                    )
                }
            }

            ToolRegistry.TOOL_FETCH_WEB_PAGE -> {
                val text = result.structuredData["extracted_text"].orEmpty()
                if (text.length < 20) {
                    VerificationOutcome(
                        passed = false,
                        verdict = "Extracted page content too thin",
                        shouldReplan = true,
                        failureReason = "Fetched page had insufficient readable text (${text.length} chars)."
                    )
                } else {
                    VerificationOutcome(
                        passed = true,
                        verdict = "Verified ${text.length} chars extracted from page"
                    )
                }
            }

            ToolRegistry.TOOL_COMPARE_AND_SUMMARIZE -> {
                val md = result.structuredData["comparison_markdown"].orEmpty()
                if (md.isBlank()) {
                    VerificationOutcome(
                        passed = false,
                        verdict = "Comparison matrix missing",
                        failureReason = "Comparison tool produced empty output."
                    )
                } else {
                    VerificationOutcome(passed = true, verdict = "Verified structured comparison generated")
                }
            }

            ToolRegistry.TOOL_CREATE_RESEARCH_REPORT -> {
                val md = result.structuredData["report_markdown"].orEmpty()
                if (md.length < 30) {
                    VerificationOutcome(
                        passed = false,
                        verdict = "Report body incomplete",
                        failureReason = "Generated report content was empty or too short."
                    )
                } else {
                    VerificationOutcome(passed = true, verdict = "Verified report compiled (${md.length} chars)")
                }
            }

            ToolRegistry.TOOL_SAVE_NOTE,
            ToolRegistry.TOOL_CREATE_TASK_LIST,
            ToolRegistry.TOOL_SAVE_RESEARCH_REPORT -> {
                val noteId = result.structuredData["note_id"].orEmpty()
                if (noteId.isBlank()) {
                    VerificationOutcome(
                        passed = false,
                        verdict = "Missing persisted note ID",
                        failureReason = "Database persistence could not be verified."
                    )
                } else {
                    VerificationOutcome(passed = true, verdict = "Verified persisted note id=$noteId")
                }
            }

            ToolRegistry.TOOL_ANDROID_OPEN_APP,
            ToolRegistry.TOOL_ANDROID_OPEN_SETTINGS,
            ToolRegistry.TOOL_ANDROID_OPEN_WEBSITE,
            ToolRegistry.TOOL_ANDROID_OPEN_MAPS,
            ToolRegistry.TOOL_ANDROID_PREPARE_SMS,
            ToolRegistry.TOOL_ANDROID_PREPARE_EMAIL,
            ToolRegistry.TOOL_ANDROID_CALENDAR_EVENT,
            ToolRegistry.TOOL_ANDROID_SET_ALARM,
            ToolRegistry.TOOL_ANDROID_SHARE -> {
                val resolvedPkg = result.structuredData["resolved_package"].orEmpty()
                if (resolvedPkg.isBlank()) {
                    VerificationOutcome(
                        passed = false,
                        verdict = "Target activity could not be verified",
                        failureReason = "No resolved Android package confirmed for Intent."
                    )
                } else {
                    VerificationOutcome(passed = true, verdict = "Verified handoff to $resolvedPkg")
                }
            }

            ToolRegistry.TOOL_PREPARE_PURCHASE_AUTHORIZATION -> {
                val idem = result.structuredData["payment_idempotency_key"].orEmpty()
                if (idem.isBlank()) {
                    VerificationOutcome(false, "Missing staged disclosure key", failureReason = "Payment disclosure did not produce an idempotency key.")
                } else {
                    VerificationOutcome(true, "Verified itemized payment disclosure staged ($idem)")
                }
            }

            ToolRegistry.TOOL_EXECUTE_AUTHORIZED_PAYMENT -> {
                val txId = result.structuredData["provider_transaction_id"].orEmpty()
                val sha = result.structuredData["receipt_sha256"].orEmpty()
                if (txId.isBlank() || sha.length != 64) {
                    VerificationOutcome(false, "Unverified payment receipt", failureReason = "Provider transaction ID or receipt SHA-256 missing.")
                } else {
                    VerificationOutcome(true, "Verified payment $txId (receipt ${sha.take(10)})")
                }
            }

            ToolRegistry.TOOL_EMAIL_SEND_AUTHORIZED -> {
                val msgId = result.structuredData["sent_message_id"].orEmpty()
                if (msgId.isBlank()) {
                    VerificationOutcome(false, "Missing sent message ID", failureReason = "Email send returned no message reference.")
                } else {
                    VerificationOutcome(true, "Verified email sent ($msgId)")
                }
            }

            ToolRegistry.TOOL_BROWSER_OPEN_SEARCH -> {
                val url = result.structuredData["browser_url"].orEmpty()
                if (url.isBlank()) {
                    VerificationOutcome(false, "Browser URL missing", failureReason = "Browser did not confirm active URL.")
                } else {
                    VerificationOutcome(true, "Verified browser navigated to $url")
                }
            }

            ToolRegistry.TOOL_BROWSER_INTERACT -> {
                val verified = result.structuredData["webview_verified"] == "true"
                if (!verified) {
                    VerificationOutcome(
                        passed = false,
                        verdict = "Browser DOM interaction unverified",
                        shouldReplan = true,
                        failureReason = result.errorMessage ?: "Live WebView did not verify DOM action completion."
                    )
                } else {
                    VerificationOutcome(
                        passed = true,
                        verdict = "Verified browser ${result.structuredData["dom_action"] ?: "DOM"} (${result.structuredData["dom_status"] ?: "OK"})"
                    )
                }
            }

            else -> VerificationOutcome(passed = true, verdict = "Verified outcome: ${result.summary.take(80)}")
        }
    }

    fun verifyTaskOutcome(record: AutonomousTaskRecord): VerificationOutcome {
        if (record.steps.isEmpty()) {
            return VerificationOutcome(
                passed = false,
                verdict = "No executable steps verified",
                failureReason = "Task cannot complete with an empty plan."
            )
        }
        val incomplete = record.steps.filter {
            it.status != StepStatus.COMPLETED && it.status != StepStatus.SKIPPED
        }
        if (incomplete.isNotEmpty()) {
            return VerificationOutcome(
                passed = false,
                verdict = "${incomplete.size} step(s) unverified",
                failureReason = "Incomplete steps remain: ${incomplete.joinToString { it.title }}"
            )
        }
        val completedSteps = record.steps.filter { it.status == StepStatus.COMPLETED }
        if (completedSteps.isEmpty()) {
            return VerificationOutcome(
                passed = false,
                verdict = "All steps skipped",
                failureReason = "At least one tool step must execute and pass verification."
            )
        }
        val unverified = completedSteps.firstOrNull { it.verificationVerdict.isBlank() }
        if (unverified != null) {
            return VerificationOutcome(
                passed = false,
                verdict = "Missing verification verdict on '${unverified.title}'",
                failureReason = "Step '${unverified.title}' finished without an auditable verification verdict."
            )
        }
        val blankOutputs = completedSteps.firstOrNull { it.outputSummary.isBlank() }
        if (blankOutputs != null) {
            return VerificationOutcome(
                passed = false,
                verdict = "Missing verified output summary",
                failureReason = "Step '${blankOutputs.title}' produced an empty outcome."
            )
        }
        return VerificationOutcome(
            passed = true,
            verdict = "Verified ${completedSteps.size} completed step(s) under ${record.executionId.ifBlank { record.taskId }}"
        )
    }
}
