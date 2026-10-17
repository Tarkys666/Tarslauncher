package com.tarslauncher.app.agent.domain.parser

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.agent.domain.model.AgentAction

object ActionParser {

    data class ParsedPayload(
        val action: AgentAction,
        val reflection: String = "",
        val replan: List<String>? = null
    )

    private val thinkBlockRegex = Regex("""<think>(.*?)</think>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /**
     * Extracts and maps a JSON object from the model's raw string response into a strongly-typed [AgentAction].
     * Resilient against `<think>` reasoning blocks, markdown code fences, conversational text, or key variations.
     */
    fun parse(rawResponse: String): AgentAction? = parsePayload(rawResponse)?.action

    fun parsePayload(rawResponse: String): ParsedPayload? {
        if (rawResponse.isBlank()) return null

        val thinkContent = thinkBlockRegex.find(rawResponse)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val stripped = thinkBlockRegex.replace(rawResponse, "").trim()
        val jsonSubstring = extractBalancedJsonObject(stripped) ?: return null

        return runCatching {
            val jsonElement = JsonParser.parseString(jsonSubstring)
            if (!jsonElement.isJsonObject) return null

            val rootObj = jsonElement.asJsonObject
            val reflection = rootObj.get("reflection")?.asString?.trim().orEmpty()
            val explicitThought = rootObj.get("thought")?.asString?.trim()
                ?: rootObj.get("reasoning")?.asString?.trim()
                ?: ""
            val thought = explicitThought.ifBlank { thinkContent }

            val actionName = (
                rootObj.get("action")?.asString
                    ?: rootObj.get("tool")?.asString
                    ?: rootObj.get("name")?.asString
            )?.trim()?.lowercase() ?: return null

            val args = rootObj.getAsJsonObject("args")
                ?: rootObj.getAsJsonObject("parameters")
                ?: rootObj.getAsJsonObject("arguments")
                ?: rootObj

            val stepIndex = rootObj.get("step")?.asInt
                ?: rootObj.get("current_step")?.asInt
                ?: rootObj.get("step_index")?.asInt
                ?: -1

            val replan = rootObj.get("replan")?.let { extractStringList(it) }?.takeIf { it.isNotEmpty() }

            val action = mapToAction(thought, actionName, args, stepIndex)
            ParsedPayload(
                action = action,
                reflection = reflection,
                replan = replan
            )
        }.getOrNull()
    }

    fun parsePlan(rawResponse: String): List<String>? {
        if (rawResponse.isBlank()) return null
        val stripped = thinkBlockRegex.replace(rawResponse, "").trim()
        val jsonSubstring = extractBalancedJsonObject(stripped) ?: return null

        return runCatching {
            val jsonElement = JsonParser.parseString(jsonSubstring)
            if (!jsonElement.isJsonObject) return null
            val root = jsonElement.asJsonObject
            val planElement = root.get("plan") ?: root.get("steps") ?: return null
            extractStringList(planElement).takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun extractStringList(element: JsonElement): List<String> {
        if (!element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            when {
                item.isJsonPrimitive -> item.asString.trim().takeIf { it.isNotBlank() }
                item.isJsonObject -> {
                    val obj = item.asJsonObject
                    (obj.get("title")?.asString ?: obj.get("step")?.asString ?: obj.get("description")?.asString)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                }
                else -> null
            }
        }
    }

    /**
     * Finds the first valid balanced `{ ... }` JSON object in [text], ignoring braces inside string literals.
     */
    internal fun extractBalancedJsonObject(text: String): String? {
        var startIdx = text.indexOf('{')
        while (startIdx != -1) {
            var depth = 0
            var inString = false
            var escaped = false

            for (i in startIdx until text.length) {
                val ch = text[i]
                if (inString) {
                    if (escaped) {
                        escaped = false
                    } else if (ch == '\\') {
                        escaped = true
                    } else if (ch == '"') {
                        inString = false
                    }
                } else {
                    when (ch) {
                        '"' -> inString = true
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val candidate = text.substring(startIdx, i + 1)
                                if (runCatching { JsonParser.parseString(candidate).isJsonObject }.getOrDefault(false)) {
                                    return candidate
                                }
                                break
                            }
                        }
                    }
                }
            }
            startIdx = text.indexOf('{', startIdx + 1)
        }
        return null
    }

    private fun mapToAction(thought: String, actionName: String, args: JsonObject, stepIndex: Int): AgentAction {
        return when (actionName) {
            "open_app", "launch_app" -> {
                val appName = args.get("name")?.asString
                    ?: args.get("app")?.asString
                    ?: args.get("app_name")?.asString.orEmpty()
                AgentAction.OpenApp(thought, appName, stepIndex)
            }

            "open_url", "browse", "url" -> {
                val url = args.get("url")?.asString
                    ?: args.get("link")?.asString
                    ?: args.get("uri")?.asString.orEmpty()
                AgentAction.OpenUrl(thought, url, stepIndex)
            }

            "search", "web_search", "search_web", "youtube_search" -> {
                val query = args.get("query")?.asString
                    ?: args.get("q")?.asString
                    ?: args.get("text")?.asString.orEmpty()
                val platform = if (actionName == "youtube_search") {
                    "youtube"
                } else {
                    (args.get("platform")?.asString ?: args.get("engine")?.asString ?: "google").lowercase()
                }
                AgentAction.SearchWeb(thought, query, platform, stepIndex)
            }

            "tap", "click" -> {
                val id = args.getSafeInt("id", "target_id", fallback = -1)
                AgentAction.Tap(thought, id, stepIndex)
            }

            "long_press", "long_click", "hold" -> {
                val id = args.getSafeInt("id", "target_id", fallback = -1)
                AgentAction.LongPress(thought, id, stepIndex)
            }

            "type", "type_text", "input" -> {
                val id = args.getSafeInt("id", "target_id", fallback = -1)
                val text = args.get("text")?.asString
                    ?: args.get("value")?.asString.orEmpty()
                val pressEnter = args.getSafeBoolean("enter", "press_enter", fallback = true)
                AgentAction.TypeText(thought, id, text, pressEnter, stepIndex)
            }

            "scroll", "swipe" -> {
                val dirString = args.get("direction")?.asString.orEmpty()
                AgentAction.Scroll(thought, AgentAction.Scroll.Direction.fromString(dirString), stepIndex)
            }

            "back", "navigate_back" -> AgentAction.Back(thought, stepIndex)

            "home", "go_home" -> AgentAction.Home(thought, stepIndex)

            "recents", "recent_apps" -> AgentAction.Recents(thought, stepIndex)

            "notifications", "open_notifications" -> AgentAction.Notifications(thought, stepIndex)

            "quick_settings" -> AgentAction.QuickSettings(thought, stepIndex)

            "set_volume", "volume" -> {
                val level = args.getSafeInt("level", "percent", fallback = -1)
                val mode = (
                    args.get("mode")?.asString
                        ?: args.get("direction")?.asString
                        ?: if (level >= 0) "set" else "down"
                ).lowercase().trim()
                AgentAction.SetVolume(thought, mode, level, stepIndex)
            }

            "media_control", "media" -> {
                val cmd = (
                    args.get("command")?.asString
                        ?: args.get("mode")?.asString
                        ?: args.get("action")?.asString
                        ?: "toggle"
                ).lowercase().trim()
                AgentAction.MediaControl(thought, cmd, stepIndex)
            }

            "flashlight", "torch" -> {
                val stateStr = (args.get("state")?.asString ?: args.get("mode")?.asString).orEmpty().lowercase()
                val enabled = when {
                    stateStr == "off" || stateStr == "false" || stateStr == "kapat" -> false
                    else -> args.getSafeBoolean("enabled", "on", fallback = true)
                }
                AgentAction.Flashlight(thought, enabled, stepIndex)
            }

            "screenshot", "take_screenshot" -> AgentAction.TakeScreenshot(thought, stepIndex)

            "lock_screen", "lock" -> AgentAction.LockScreen(thought, stepIndex)

            "open_settings", "settings" -> {
                val section = (
                    args.get("section")?.asString
                        ?: args.get("page")?.asString
                        ?: args.get("name")?.asString
                        ?: "main"
                ).lowercase().trim()
                AgentAction.OpenSettings(thought, section, stepIndex)
            }

            "set_alarm", "alarm" -> {
                val hour = args.getSafeInt("hour", fallback = 8)
                val minute = args.getSafeInt("minute", "minutes", fallback = 0)
                val message = args.get("message")?.asString ?: args.get("label")?.asString.orEmpty()
                AgentAction.SetAlarm(thought, hour.coerceIn(0, 23), minute.coerceIn(0, 59), message, stepIndex)
            }

            "set_timer", "timer" -> {
                val seconds = args.getSafeInt("seconds", fallback = -1).let { sec ->
                    if (sec > 0) sec else (args.getSafeInt("minutes", fallback = 1) * 60)
                }
                val message = args.get("message")?.asString ?: args.get("label")?.asString.orEmpty()
                AgentAction.SetTimer(thought, seconds.coerceIn(1, 86_400), message, stepIndex)
            }

            "clipboard_set", "copy" -> {
                val text = args.get("text")?.asString
                    ?: args.get("content")?.asString.orEmpty()
                AgentAction.ClipboardSet(thought, text, stepIndex)
            }

            "note", "take_note", "save_note", "create_note", "add_note", "record_note", "write_note", "not", "not_al", "not_ekle", "not_kaydet" -> {
                val rawAct = (
                    args.get("action")?.asString
                        ?: args.get("mode")?.asString
                        ?: args.get("command")?.asString
                        ?: args.get("type")?.asString
                        ?: "add"
                ).lowercase().trim()

                val act = when (rawAct) {
                    "add", "create", "save", "ekle", "kaydet", "yaz", "yeni", "new" -> "add"
                    "list", "get", "show", "read", "listele", "göster", "oku" -> "list"
                    "delete", "remove", "del", "sil", "kaldır" -> "delete"
                    "clear", "clean", "temizle", "hepsini_sil" -> "clear"
                    else -> "add"
                }

                val text = args.get("text")?.asString
                    ?: args.get("content")?.asString
                    ?: args.get("note")?.asString
                    ?: args.get("message")?.asString
                    ?: args.get("icerik")?.asString
                    ?: args.get("not_metni")?.asString
                    ?: args.get("body")?.asString.orEmpty()

                val title = args.get("title")?.asString
                    ?: args.get("baslik")?.asString
                    ?: args.get("label")?.asString
                    ?: args.get("konu")?.asString
                    ?: args.get("subject")?.asString
                    ?: args.get("header")?.asString.orEmpty()

                val noteId = args.get("id")?.asString
                    ?: args.get("target")?.asString
                    ?: args.get("note_id")?.asString.orEmpty()

                AgentAction.NoteAction(thought, act, text, title, noteId, stepIndex)
            }

            "wait", "sleep" -> {
                val msFromSec = (args.get("seconds")?.asLong ?: args.get("sec")?.asLong)?.times(1000L)
                val ms = msFromSec
                    ?: args.get("ms")?.asLong
                    ?: args.get("duration")?.asLong
                    ?: 1000L
                AgentAction.Wait(thought, ms.coerceIn(200L, 15_000L), stepIndex)
            }

            "ask_user", "ask" -> {
                val question = args.get("question")?.asString
                    ?: args.get("text")?.asString.orEmpty()
                AgentAction.AskUser(thought, question, stepIndex)
            }

            "done", "finish", "complete" -> {
                val summary = args.get("message")?.asString
                    ?: args.get("summary")?.asString
                    ?: "Görev başarıyla tamamlandı."
                AgentAction.Done(thought, summary, stepIndex)
            }

            else -> AgentAction.Unknown(thought, actionName, stepIndex)
        }
    }

    private fun JsonObject.getSafeInt(vararg keys: String, fallback: Int = -1): Int {
        for (k in keys) {
            val el = get(k) ?: continue
            if (el.isJsonNull) continue
            val num = runCatching {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isNumber) {
                    el.asInt
                } else {
                    el.asString.filter { it.isDigit() || it == '-' }.toIntOrNull()
                }
            }.getOrNull()
            if (num != null) return num
        }
        return fallback
    }

    private fun JsonObject.getSafeBoolean(vararg keys: String, fallback: Boolean = false): Boolean {
        for (k in keys) {
            val el = get(k) ?: continue
            if (el.isJsonNull) continue
            val bool = runCatching {
                if (el.isJsonPrimitive && el.asJsonPrimitive.isBoolean) {
                    el.asBoolean
                } else {
                    when (el.asString.trim().lowercase()) {
                        "true", "1", "on", "yes", "açık" -> true
                        "false", "0", "off", "no", "kapalı" -> false
                        else -> null
                    }
                }
            }.getOrNull()
            if (bool != null) return bool
        }
        return fallback
    }
}

