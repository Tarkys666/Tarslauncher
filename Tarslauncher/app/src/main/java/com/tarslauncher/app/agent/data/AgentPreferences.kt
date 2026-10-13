package com.tarslauncher.app.agent.data

import android.content.Context
import android.content.SharedPreferences

class AgentPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getGeminiApiKey(): String {
        return prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()
    }

    fun setGeminiApiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    companion object {
        private const val PREFS_NAME = "tars_agent_prefs"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
    }
}
