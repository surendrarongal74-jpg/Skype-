package com.example.data.remote

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class AutonomousTaskFirestoreRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun autonomousTask_ownerLifecycle_enforcesTerminalCompletedLock() = runBlocking {
        val uid = signInTestUser(ALICE_EMAIL)
        val store = AutonomousTaskFirestoreStore(firestore, auth)
        val taskId = "task_${UUID.randomUUID().toString().replace("-", "").take(10)}"

        val createRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            store.upsertTask(
                AutonomousTaskDoc(
                    id = taskId,
                    userId = uid,
                    title = "Laptop budget comparison",
                    goal = "Research best laptops under $1200 and save comparison report",
                    understandingSummary = "Research 3 options and compare RAM/CPU/price",
                    status = "RUNNING",
                    totalSteps = 4L,
                    completedSteps = 1L,
                    checkpointJson = "{\"completedSteps\":[\"step_1\"]}",
                    finalOutcome = "",
                    reportMarkdown = ""
                )
            )
        }
        assertTrue("Create autonomous task failed: ${createRes.exceptionOrNull()}", createRes.isSuccess)

        val observed = withTimeout(FLOW_TIMEOUT_MS) {
            store.observeTasks(uid).first { list -> list.any { it.id == taskId && it.status == "RUNNING" } }
        }
        assertEquals(1L, observed.first { it.id == taskId }.completedSteps)

        val completeRes = withTimeout(DEFAULT_TIMEOUT_MS) {
            store.upsertTask(
                AutonomousTaskDoc(
                    id = taskId,
                    userId = uid,
                    title = "Laptop budget comparison",
                    goal = "Research best laptops under $1200 and save comparison report",
                    understandingSummary = "Research 3 options and compare RAM/CPU/price",
                    status = "COMPLETED",
                    totalSteps = 4L,
                    completedSteps = 4L,
                    checkpointJson = "{\"completedSteps\":[\"step_1\",\"step_2\",\"step_3\",\"step_4\"]}",
                    finalOutcome = "Saved comparison report",
                    reportMarkdown = "# Laptop Comparison Report\n- ThinkPad E14\n- Zenbook 14"
                )
            )
        }
        assertTrue("Completing autonomous task failed: ${completeRes.exceptionOrNull()}", completeRes.isSuccess)

        // Attempt to mutate terminal COMPLETED task must be rejected by Firestore Rules
        val postCompletedUpdate = withTimeout(DEFAULT_TIMEOUT_MS) {
            store.upsertTask(
                AutonomousTaskDoc(
                    id = taskId,
                    userId = uid,
                    title = "Tampered after completion",
                    goal = "Research best laptops under $1200 and save comparison report",
                    understandingSummary = "Tampered",
                    status = "RUNNING",
                    totalSteps = 4L,
                    completedSteps = 0L,
                    checkpointJson = "{}",
                    finalOutcome = "",
                    reportMarkdown = ""
                )
            )
        }
        assertTrue("Expected terminal COMPLETED task update to be rejected", postCompletedUpdate.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            postCompletedUpdate.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        val delRes = withTimeout(DEFAULT_TIMEOUT_MS) { store.deleteTask(taskId) }
        assertTrue(delRes.isSuccess)
    }

    @Test
    fun autonomousTask_andWorkspaceNote_crossUserAccessRejected() = runBlocking {
        val aliceUid = signInTestUser(ALICE_EMAIL)
        val aliceStore = AutonomousTaskFirestoreStore(firestore, auth)
        val taskId = "task_priv_${UUID.randomUUID().toString().replace("-", "").take(8)}"
        val noteId = "note_priv_${UUID.randomUUID().toString().replace("-", "").take(8)}"

        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceStore.upsertTask(
                AutonomousTaskDoc(
                    id = taskId,
                    userId = aliceUid,
                    title = "Private research",
                    goal = "Analyze private notes",
                    understandingSummary = "Internal note analysis",
                    status = "PAUSED",
                    totalSteps = 2L,
                    completedSteps = 1L,
                    checkpointJson = "{\"step\":1}"
                )
            ).getOrThrow()

            aliceStore.upsertNote(
                WorkspaceNoteDoc(
                    id = noteId,
                    userId = aliceUid,
                    taskId = taskId,
                    noteType = "RESEARCH_REPORT",
                    title = "Private Comparison",
                    content = "Confidential shortlist",
                    tagsCsv = "research"
                )
            ).getOrThrow()
        }

        signInTestUser(BOB_EMAIL)
        val bobStore = AutonomousTaskFirestoreStore(firestore, auth)

        val crossReadTask = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobStore.getTask(aliceUid, taskId)
        }
        assertTrue("Cross-user task read must fail", crossReadTask.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            crossReadTask.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        auth.signOut()
        val unauthRead = withTimeout(DEFAULT_TIMEOUT_MS) {
            bobStore.getTask(aliceUid, taskId)
        }
        assertTrue("Unauthenticated task read must fail", unauthRead.isFailure)
        assertEquals(
            FirebaseFirestoreException.Code.PERMISSION_DENIED,
            unauthRead.exceptionOrNull()?.unwrapFirestoreException()?.code
        )

        // Cleanup as Alice
        signInTestUser(ALICE_EMAIL)
        val aliceCleanupStore = AutonomousTaskFirestoreStore(firestore, auth)
        withTimeout(DEFAULT_TIMEOUT_MS) {
            aliceCleanupStore.deleteTask(taskId).getOrThrow()
            aliceCleanupStore.deleteNote(noteId).getOrThrow()
            val afterDel = aliceCleanupStore.getTask(aliceUid, taskId).getOrNull()
            assertNull(afterDel)
        }

        assertNotNull(aliceUid)
    }

    private companion object {
        const val ALICE_EMAIL = "alice_allora@test.com"
        const val BOB_EMAIL = "bob_allora@test.com"
        const val DEFAULT_TIMEOUT_MS = 5000L
        const val FLOW_TIMEOUT_MS = 3000L
    }
}
