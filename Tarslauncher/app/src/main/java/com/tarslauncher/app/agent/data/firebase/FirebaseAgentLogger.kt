package com.tarslauncher.app.agent.data.firebase

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.tarslauncher.app.agent.domain.model.AgentPlan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Native, zero-configuration telemetry and logging engine powered by Firebase Firestore.
 * Automatically saves session trajectories, LLM thoughts, actions, and learned skills under the user's anonymous UID.
 */
class FirebaseAgentLogger(
    private val context: Context
) {

    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    companion object {
        private const val TAG = "FirebaseAgentLogger"
        private const val COLLECTION_USERS = "users"
        private const val SUBCOLLECTION_SESSIONS = "agent_sessions"
        private const val SUBCOLLECTION_STEPS = "steps"
        private const val SUBCOLLECTION_SKILLS = "agent_skills"
    }

    /**
     * Ensures an anonymous user ID is available for isolation.
     */
    suspend fun getUserId(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val current = auth.currentUser
            if (current != null) return@withContext current.uid
            val result = auth.signInAnonymously().await()
            result.user?.uid
        }.getOrNull()
    }

    /**
     * Initializes a session document in Firestore.
     */
    suspend fun logSessionStart(
        sessionId: String,
        goal: String,
        plan: AgentPlan?,
        provider: String,
        model: String
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val uid = getUserId() ?: return@withContext
            val now = System.currentTimeMillis()

            val planList = plan?.steps?.map { s ->
                mapOf(
                    "step" to s.index,
                    "title" to s.title
                )
            } ?: emptyList()

            val sessionData = hashMapOf(
                "session_id" to sessionId,
                "goal" to goal,
                "status" to "IN_PROGRESS",
                "provider" to provider,
                "model" to model,
                "total_steps" to 0,
                "plan" to planList,
                "created_at" to now,
                "updated_at" to now
            )

            firestore.collection(COLLECTION_USERS)
                .document(uid)
                .collection(SUBCOLLECTION_SESSIONS)
                .document(sessionId)
                .set(sessionData, SetOptions.merge())
                .await()
        }.onFailure { e ->
            Log.w(TAG, "Failed to log session start to Firebase: ${e.message}")
        }
    }

    /**
     * Logs an individual execution step (including the LLM's thought process).
     */
    suspend fun logStep(
        sessionId: String,
        step: Int,
        thought: String,
        actionSignature: String,
        executionResult: String,
        status: String,
        screenDumpSnippet: String? = null
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val uid = getUserId() ?: return@withContext
            val now = System.currentTimeMillis()

            val stepData = hashMapOf(
                "step" to step,
                "thought" to redactSensitive(thought),
                "action_signature" to redactSensitive(actionSignature),
                "execution_result" to redactSensitive(executionResult),
                "status" to status,
                "timestamp" to now
            )

            if (!screenDumpSnippet.isNullOrBlank()) {
                stepData["screen_snippet"] = redactSensitive(screenDumpSnippet.take(1000))
            }

            firestore.collection(COLLECTION_USERS)
                .document(uid)
                .collection(SUBCOLLECTION_SESSIONS)
                .document(sessionId)
                .collection(SUBCOLLECTION_STEPS)
                .document("step_$step")
                .set(stepData, SetOptions.merge())
                .await()
        }.onFailure { e ->
            Log.w(TAG, "Failed to log step $step to Firebase: ${e.message}")
        }
    }

    private fun redactSensitive(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace(Regex("""gsk_[A-Za-z0-9_-]{8,}"""), "gsk_***REDACTED***")
            .replace(Regex("""AIza[A-Za-z0-9_-]{10,}"""), "AIza***REDACTED***")
            .replace(Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}"""), "***JWT_REDACTED***")
            .replace(Regex("""\b[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){12,30}\b""", RegexOption.IGNORE_CASE), "***IBAN_REDACTED***")
            .replace(Regex("""\b(?:\d{4}[ -]?){3}\d{4}\b|\b\d{15,19}\b"""), "***KART_REDACTED***")
            .replace(Regex("""\b[1-9]\d{10}\b"""), "***TCKN_REDACTED***")
            .replace(Regex("""(?i)\b(?:otp|kod|code|doğrulama|şifre|onay|pin)[\s:=#-]*\d{4,8}\b|\b\d{4,8}\s*(?:kodu|kod|otp|pin)\b"""), "***OTP_REDACTED***")
    }

    /**
     * Updates the session document with its final execution outcome.
     */
    suspend fun logSessionFinish(
        sessionId: String,
        status: String,
        executionSummary: String,
        totalSteps: Int
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val uid = getUserId() ?: return@withContext
            val now = System.currentTimeMillis()

            val finishData = hashMapOf(
                "status" to status,
                "execution_summary" to executionSummary,
                "total_steps" to totalSteps,
                "updated_at" to now
            )

            firestore.collection(COLLECTION_USERS)
                .document(uid)
                .collection(SUBCOLLECTION_SESSIONS)
                .document(sessionId)
                .set(finishData, SetOptions.merge())
                .await()
        }.onFailure { e ->
            Log.w(TAG, "Failed to update session finish in Firebase: ${e.message}")
        }
    }

    /**
     * Stores a learned skill recipe in Firestore.
     */
    suspend fun logSkill(
        skillId: String,
        title: String,
        targetPackage: String,
        markdownContent: String
    ) = withContext(Dispatchers.IO) {
        runCatching {
            val uid = getUserId() ?: return@withContext
            val now = System.currentTimeMillis()

            val skillData = hashMapOf(
                "id" to skillId,
                "title" to title,
                "target_package" to targetPackage,
                "markdown_content" to markdownContent,
                "created_at" to now
            )

            firestore.collection(COLLECTION_USERS)
                .document(uid)
                .collection(SUBCOLLECTION_SKILLS)
                .document(skillId)
                .set(skillData, SetOptions.merge())
                .await()
        }.onFailure { e ->
            Log.w(TAG, "Failed to log skill to Firebase: ${e.message}")
        }
    }
}
