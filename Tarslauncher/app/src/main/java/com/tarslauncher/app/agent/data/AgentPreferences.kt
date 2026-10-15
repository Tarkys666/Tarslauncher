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

    fun getProvider(): AgentProvider {
        val name = prefs.getString(KEY_PROVIDER, AgentProvider.GEMINI.name) ?: AgentProvider.GEMINI.name
        return runCatching { AgentProvider.valueOf(name) }.getOrDefault(AgentProvider.GEMINI)
    }

    fun setProvider(provider: AgentProvider) {
        prefs.edit().putString(KEY_PROVIDER, provider.name).apply()
    }

    fun getGeminiApiKey(): String {
        return prefs.getString(KEY_GEMINI_API_KEY, "").orEmpty()
    }

    fun setGeminiApiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun getGroqApiKey(): String {
        return prefs.getString(KEY_GROQ_API_KEY, "").orEmpty()
    }

    fun setGroqApiKey(key: String) {
        prefs.edit().putString(KEY_GROQ_API_KEY, key.trim()).apply()
    }

    fun getGroqModel(): String {
        return prefs.getString(KEY_GROQ_MODEL, "qwen/qwen3.8-27b").orEmpty().ifBlank { "qwen/qwen3.8-27b" }
    }

    fun setGroqModel(model: String) {
        prefs.edit().putString(KEY_GROQ_MODEL, model.trim()).apply()
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

    companion object {
        private const val PREFS_NAME = "tars_agent_prefs"
        private const val KEY_PROVIDER = "agent_provider"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_SUPABASE_URL = "supabase_url"
        private const val KEY_SUPABASE_ANON_KEY = "supabase_anon_key"
    }
}
