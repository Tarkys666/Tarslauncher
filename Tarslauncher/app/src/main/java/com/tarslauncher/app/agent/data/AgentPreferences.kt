package com.tarslauncher.app.agent.data

import android.content.Context
import android.content.SharedPreferences

enum class AgentProvider {
    GEMINI,
    GROQ
}

class AgentPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getProvider(): AgentProvider = AgentProvider.GROQ
    fun setProvider(provider: AgentProvider) {
        // Locked to Groq
    }

    fun getGeminiApiKey(): String = ""
    fun setGeminiApiKey(key: String) {}

    fun getGroqApiKey(): String {
        return prefs.getString(KEY_GROQ_API_KEY, "").orEmpty()
    }

    fun setGroqApiKey(key: String) {
        prefs.edit().putString(KEY_GROQ_API_KEY, key.trim()).apply()
    }

    fun getGroqModel(): String = GroqRemoteDataSource.DEFAULT_GROQ_MODEL

    fun setGroqModel(model: String) {
        // Fixed to GroqRemoteDataSource.DEFAULT_GROQ_MODEL
    }

    fun getSupabaseUrl(): String {
        return prefs.getString(KEY_SUPABASE_URL, "").orEmpty()
    }

    fun setSupabaseUrl(url: String) {
        prefs.edit().putString(KEY_SUPABASE_URL, url.trim()).apply()
    }

    fun getSupabaseAnonKey(): String {
        return prefs.getString(KEY_SUPABASE_ANON_KEY, "").orEmpty()
    }

    fun setSupabaseAnonKey(key: String) {
        prefs.edit().putString(KEY_SUPABASE_ANON_KEY, key.trim()).apply()
    }

    fun isAgentEnabled(): Boolean {
        return prefs.getBoolean(KEY_AGENT_ENABLED, true)
    }

    fun setAgentEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AGENT_ENABLED, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "tars_agent_prefs"
        private const val KEY_AGENT_ENABLED = "agent_master_enabled"
        private const val KEY_PROVIDER = "agent_provider"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_SUPABASE_URL = "supabase_url"
        private const val KEY_SUPABASE_ANON_KEY = "supabase_anon_key"
    }
}
