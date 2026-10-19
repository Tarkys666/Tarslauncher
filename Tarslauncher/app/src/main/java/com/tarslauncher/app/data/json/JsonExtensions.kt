package com.tarslauncher.app.data.json

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

val AppGson: Gson by lazy { Gson() }

inline fun <reified T> Gson.fromJsonSafe(json: String?): T? {
    if (json.isNullOrBlank()) return null
    return try {
        fromJson<T>(json, object : TypeToken<T>() {}.type)
    } catch (e: Exception) {
        null
    }
}

inline fun <reified T> String?.parseJson(): T? = AppGson.fromJsonSafe<T>(this)
fun Any?.toJson(): String = AppGson.toJson(this)
