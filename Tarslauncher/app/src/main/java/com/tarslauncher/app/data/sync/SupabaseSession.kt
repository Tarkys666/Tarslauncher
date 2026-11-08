package com.tarslauncher.app.data.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

/** Session returned by Supabase Auth, never a locally invented Google identity. */
data class SupabaseSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val user: TarsAuthUser
) {
    fun needsRefresh(nowSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expiresAt <= nowSeconds + 60

    fun toJson(): String = JsonObject().apply {
        addProperty("access_token", accessToken)
        addProperty("refresh_token", refreshToken)
        addProperty("expires_at", expiresAt)
        add("user", JsonObject().apply {
            addProperty("id", user.uid)
            addProperty("email", user.email)
            addProperty("is_anonymous", user.isAnonymous)
            add("user_metadata", JsonObject().apply { addProperty("full_name", user.displayName) })
        })
    }.toString()

    companion object {
        fun fromJson(json: String, nowSeconds: Long = System.currentTimeMillis() / 1000): SupabaseSession {
            val obj = JsonParser.parseString(json).asJsonObject
            fun JsonObject.text(key: String): String? = get(key)?.takeUnless { it.isJsonNull }?.asString
            val accessToken = obj.text("access_token").orEmpty()
            val refreshToken = obj.text("refresh_token").orEmpty()
            require(accessToken.isNotBlank() && refreshToken.isNotBlank()) { "Supabase oturumu eksik." }
            val expiresAt = obj.get("expires_at")?.takeUnless { it.isJsonNull }?.asLong
                ?: (nowSeconds + obj.get("expires_in").asLong)
            val authUser = obj.getAsJsonObject("user")
            val uid = authUser.text("id").orEmpty()
            require(UUID.fromString(uid).toString().equals(uid, ignoreCase = true)) { "Geçersiz Supabase kullanıcı kimliği." }
            require(authUser.get("is_anonymous")?.asBoolean != true) { "Google ile giriş yapmalısınız." }
            val metadata = authUser.getAsJsonObject("user_metadata")
            return SupabaseSession(
                accessToken, refreshToken, expiresAt,
                TarsAuthUser(uid, authUser.text("email"), metadata?.text("full_name") ?: metadata?.text("name"), false)
            )
        }
    }
}
