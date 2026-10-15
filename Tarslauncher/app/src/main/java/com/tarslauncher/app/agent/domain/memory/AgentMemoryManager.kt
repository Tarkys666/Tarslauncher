package com.tarslauncher.app.agent.domain.memory

import android.content.Context
import android.content.SharedPreferences

data class SessionMemory(
    val lastGoal: String,
    val lastStatus: String,
    val lastSummary: String,
    val lastAppPackage: String,
    val timestamp: Long
)

class AgentMemoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveLastSession(
        goal: String,
        status: String,
        summary: String,
        lastAppPackage: String
    ) {
        prefs.edit()
            .putString(KEY_LAST_GOAL, goal)
            .putString(KEY_LAST_STATUS, status)
            .putString(KEY_LAST_SUMMARY, summary)
            .putString(KEY_LAST_APP, lastAppPackage)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    fun getLastSessionMemory(): SessionMemory? {
        val lastGoal = prefs.getString(KEY_LAST_GOAL, null) ?: return null
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        // Keep memory valid for 30 minutes
        if (System.currentTimeMillis() - timestamp > 30 * 60 * 1000L) {
            return null
        }

        return SessionMemory(
            lastGoal = lastGoal,
            lastStatus = prefs.getString(KEY_LAST_STATUS, "UNKNOWN").orEmpty(),
            lastSummary = prefs.getString(KEY_LAST_SUMMARY, "").orEmpty(),
            lastAppPackage = prefs.getString(KEY_LAST_APP, "").orEmpty(),
            timestamp = timestamp
        )
    }

    fun clearMemory() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "tars_agent_memory"
        private const val KEY_LAST_GOAL = "last_goal"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_LAST_SUMMARY = "last_summary"
        private const val KEY_LAST_APP = "last_app"
        private const val KEY_TIMESTAMP = "timestamp"
    }
}
