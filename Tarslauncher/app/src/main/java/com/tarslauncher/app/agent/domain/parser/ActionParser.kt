package com.tarslauncher.app.agent.domain.parser

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.data.model.WorkspaceItemType
import com.tarslauncher.app.data.model.WorkspacePriority

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
                    ?: rootObj.get("command")?.asString
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

    private fun extractStringList(element: JsonElement?): List<String> {
        if (element == null || !element.isJsonArray) return emptyList()
        return element.asJsonArray.mapNotNull { item ->
            when {
                item.isJsonPrimitive -> item.asString.trim().takeIf { it.isNotBlank() }
                item.isJsonObject -> {
                    val obj = item.asJsonObject
                    (obj.get("title")?.asString ?: obj.get("text")?.asString ?: obj.get("step")?.asString ?: obj.get("description")?.asString)
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
            "create", "add", "save", "note", "task", "idea", "todo",
            "add_task", "add_note", "add_idea", "create_task", "create_note", "create_idea",
            "not", "not_al", "not_ekle", "gorev_ekle", "fikir_ekle",
            "set_alarm", "alarm", "reminder", "timer", "hatirlat", "alarm_kur" -> {
                val subAction = (args.get("action")?.asString ?: args.get("mode")?.asString).orEmpty().lowercase().trim()
                if (subAction in listOf("delete", "remove", "sil", "kaldır")) {
                    val target = extractTarget(args)
                    return AgentAction.DeleteWorkspaceItem(thought, target, stepIndex)
                }
                if (subAction in listOf("complete", "done", "finish", "tamamla", "bitir")) {
                    val target = extractTarget(args)
                    return AgentAction.CompleteWorkspaceItem(thought, target, stepIndex)
                }
                if (subAction in listOf("list", "query", "show", "get", "listele", "göster", "özetle")) {
                    val filter = (args.get("type")?.asString ?: args.get("filter")?.asString ?: "ALL").uppercase()
                    val summary = extractSummaryOrText(args)
                    return AgentAction.QueryWorkspace(thought, filter, summary, stepIndex)
                }
                if (subAction in listOf("clear", "temizle", "hepsini_sil")) {
                    return AgentAction.ClearAllWorkspace(thought, stepIndex)
                }

                val inferredTypeStr = when {
                    actionName.contains("task") || actionName.contains("todo") || actionName.contains("gorev") || actionName.contains("alarm") || actionName.contains("reminder") || actionName.contains("hatirlat") -> "TASK"
                    actionName.contains("idea") || actionName.contains("fikir") -> "IDEA"
                    else -> args.get("type")?.asString
                        ?: args.get("category")?.asString
                        ?: args.get("kind")?.asString
                        ?: "NOTE"
                }
                val itemType = WorkspaceItemType.fromString(inferredTypeStr)
                val priority = WorkspacePriority.fromString(
                    args.get("priority")?.asString ?: args.get("oncelik")?.asString
                )

                val title = (
                    args.get("title")?.asString
                        ?: args.get("baslik")?.asString
                        ?: args.get("name")?.asString
                        ?: args.get("subject")?.asString
                        ?: args.get("konu")?.asString
                )?.trim().orEmpty()

                val content = (
                    args.get("content")?.asString
                        ?: args.get("text")?.asString
                        ?: args.get("description")?.asString
                        ?: args.get("icerik")?.asString
                        ?: args.get("not_metni")?.asString
                        ?: args.get("body")?.asString
                        ?: args.get("note")?.asString
                        ?: args.get("message")?.asString
                )?.trim().orEmpty()

                val checklist = extractStringList(args.get("checklist") ?: args.get("subtasks") ?: args.get("items") ?: args.get("steps"))
                val tags = extractStringList(args.get("tags") ?: args.get("labels") ?: args.get("etiketler"))

                var alarmHour = runCatching {
                    (args.get("alarm_hour") ?: args.get("hour") ?: args.get("saat"))?.asInt
                }.getOrNull()
                var alarmMinute = runCatching {
                    (args.get("alarm_minute") ?: args.get("minute") ?: args.get("dakika"))?.asInt
                }.getOrNull()

                val timeStr = runCatching {
                    (args.get("alarm_time") ?: args.get("time"))?.asString?.trim()
                }.getOrNull()
                if (alarmHour == null && !timeStr.isNullOrBlank()) {
                    val parts = timeStr.split(":", ".")
                    val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
                    val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    if (h != null && h in 0..23 && m in 0..59) {
                        alarmHour = h
                        alarmMinute = m
                    }
                }
                if (alarmHour != null && alarmMinute == null) {
                    alarmMinute = 0
                }

                val durationSeconds = runCatching {
                    (args.get("duration_seconds") ?: args.get("seconds"))?.asLong
                }.getOrNull()

                val isTomorrow = runCatching {
                    (args.get("is_tomorrow") ?: args.get("tomorrow") ?: args.get("yarin"))?.asBoolean
                }.getOrNull() ?: false

                val finalTitle = title.ifBlank {
                    content.lineSequence().firstOrNull()?.take(40) ?: itemType.labelTr
                }

                AgentAction.CreateWorkspaceItem(
                    thought = thought,
                    itemType = itemType,
                    title = finalTitle,
                    content = content,
                    priority = priority,
                    checklist = checklist,
                    tags = tags,
                    alarmHour = alarmHour?.takeIf { it in 0..23 },
                    alarmMinute = alarmMinute?.takeIf { it in 0..59 },
                    durationSeconds = durationSeconds?.takeIf { it > 0L },
                    isTomorrow = isTomorrow,
                    stepIndex = stepIndex
                )
            }

            "complete", "complete_task", "mark_done", "tamamla", "bitir" -> {
                val target = extractTarget(args)
                AgentAction.CompleteWorkspaceItem(thought, target, stepIndex)
            }

            "delete", "remove", "delete_item", "delete_note", "delete_task", "sil", "kaldır" -> {
                val target = extractTarget(args)
                AgentAction.DeleteWorkspaceItem(thought, target, stepIndex)
            }

            "clear_completed", "clean_completed", "tamamlananlari_sil" -> {
                AgentAction.ClearCompletedTasks(thought, stepIndex)
            }

            "clear_all", "clear", "temizle" -> {
                AgentAction.ClearAllWorkspace(thought, stepIndex)
            }

            "query", "list", "search", "summarize", "listele", "göster", "özetle" -> {
                val filter = (args.get("type")?.asString ?: args.get("filter")?.asString ?: "ALL").uppercase()
                val summary = extractSummaryOrText(args)
                AgentAction.QueryWorkspace(thought, filter, summary, stepIndex)
            }

            "ask_user", "ask" -> {
                val question = args.get("question")?.asString
                    ?: args.get("text")?.asString.orEmpty()
                AgentAction.AskUser(thought, question, stepIndex)
            }

            "done", "finish" -> {
                val summary = extractSummaryOrText(args).ifBlank { "İşlem başarıyla tamamlandı." }
                AgentAction.Done(thought, summary, stepIndex)
            }

            else -> AgentAction.Unknown(thought, actionName, stepIndex)
        }
    }

    private fun extractTarget(args: JsonObject): String {
        return (
            args.get("target")?.asString
                ?: args.get("id")?.asString
                ?: args.get("title")?.asString
                ?: args.get("query")?.asString
                ?: args.get("text")?.asString
                ?: args.get("note_id")?.asString
        )?.trim().orEmpty()
    }

    private fun extractSummaryOrText(args: JsonObject): String {
        return (
            args.get("summary")?.asString
                ?: args.get("message")?.asString
                ?: args.get("answer")?.asString
                ?: args.get("response")?.asString
                ?: args.get("text")?.asString
                ?: args.get("content")?.asString
        )?.trim().orEmpty()
    }
}
