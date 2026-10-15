package com.tarslauncher.app.agent.data.supabase

import android.content.Context
import android.util.Log
import com.google.gson.JsonObject
import com.tarslauncher.app.agent.data.AgentPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Lightweight, zero-dependency REST client for Supabase PostgREST.
 * Stores agent execution sessions, step-by-step traces, reasoning/thoughts, and skills.
 */
class SupabaseAgentLogger(
    private val context: Context,
    private val preferences: AgentPreferences = AgentPreferences(context)
) {

    companion object {
        private const val TAG = "SupabaseAgentLogger"
        private const val TABLE_SESSIONS = "tars_agent_sessions"
        private const val TABLE_STEPS = "tars_agent_steps"
        private const val TABLE_SKILLS = "tars_agent_skills"
    }

    fun isConfigured(): Boolean {
        val url = preferences.getSupabaseUrl().trim()
        val key = preferences.getSupabaseAnonKey().trim()
        return url.isNotBlank() && key.isNotBlank()
    }

    /**
     * Records or updates a complete agent session (goal, plan, provider, model, final status, summary).
     */
    suspend fun logSession(sessionData: JsonObject): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.success(Unit)
        postToTable(TABLE_SESSIONS, sessionData)
    }

    /**
     * Records a single execution step (thought, screen dump, action, execution result, status).
     */
    suspend fun logStep(stepData: JsonObject): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.success(Unit)
        postToTable(TABLE_STEPS, stepData)
    }

    /**
     * Records a learned skill (.md recipe) to Supabase.
     */
    suspend fun logSkill(skillData: JsonObject): Result<Unit> = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext Result.success(Unit)
        postToTable(TABLE_SKILLS, skillData)
    }

    private fun postToTable(table: String, body: JsonObject): Result<Unit> {
        val baseUrl = preferences.getSupabaseUrl().trim().trimEnd('/')
        val anonKey = preferences.getSupabaseAnonKey().trim()

        if (baseUrl.isBlank() || anonKey.isBlank()) {
            return Result.success(Unit)
        }

        return runCatching {
            val endpoint = URL("$baseUrl/rest/v1/$table")
            val connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("apikey", anonKey)
                setRequestProperty("Authorization", "Bearer $anonKey")
                // Upsert behavior on primary key collision
                setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal")
            }

            connection.outputStream.use { out ->
                out.write(body.toString().toByteArray(Charsets.UTF_8))
            }

            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                val errorStream: InputStream? = connection.errorStream
                val errorResponse = errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                Log.w(TAG, "Supabase request failed ($statusCode): $errorResponse")
                throw RuntimeException("Supabase HTTP $statusCode: $errorResponse")
            }
        }.onFailure { e ->
            Log.w(TAG, "Supabase error posting to $table: ${e.message}")
        }
    }
}
