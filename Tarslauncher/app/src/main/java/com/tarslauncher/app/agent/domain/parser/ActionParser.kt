package com.tarslauncher.app.agent.domain.parser

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tarslauncher.app.agent.domain.model.AgentAction

object ActionParser {

    /**
     * Extracts and maps a JSON object from the model's raw string response into a strongly-typed [AgentAction].
     * Resilient against surrounding markdown code fences, leading text, or minor parameter key variations.
     */
    fun parse(rawResponse: String): AgentAction? {
        val trimmed = rawResponse.trim()
        val startIndex = trimmed.indexOf('{')
        val endIndex = trimmed.lastIndexOf('}')

        if (startIndex == -1 || endIndex <= startIndex) {
            return null
        }

        val jsonSubstring = trimmed.substring(startIndex, endIndex + 1)

        return runCatching {
            val jsonElement = JsonParser.parseString(jsonSubstring)
            if (!jsonElement.isJsonObject) return null

            val rootObj = jsonElement.asJsonObject
            val thought = rootObj.get("thought")?.asString.orEmpty()
            val actionName = rootObj.get("action")?.asString?.lowercase() ?: return null
            val args = rootObj.getAsJsonObject("args") ?: JsonObject()
            val stepIndex = rootObj.get("step")?.asInt
                ?: rootObj.get("current_step")?.asInt
                ?: rootObj.get("step_index")?.asInt
                ?: -1

            mapToAction(thought, actionName, args, stepIndex)
        }.getOrNull()
    }

    fun parsePlan(rawResponse: String): List<String>? {
        val trimmed = rawResponse.trim()
        val startIndex = trimmed.indexOf('{')
        val endIndex = trimmed.lastIndexOf('}')
        if (startIndex == -1 || endIndex <= startIndex) return null

        return runCatching {
            val jsonElement = JsonParser.parseString(trimmed.substring(startIndex, endIndex + 1))
            val root = jsonElement.asJsonObject
            val planArray = root.getAsJsonArray("plan") ?: return null
            planArray.mapNotNull { it.asString }
        }.getOrNull()
    }

    private fun mapToAction(thought: String, actionName: String, args: JsonObject, stepIndex: Int): AgentAction {
        return when (actionName) {
            "open_app" -> {
                val appName = args.get("name")?.asString
                    ?: args.get("app")?.asString
                    ?: args.get("app_name")?.asString.orEmpty()
                AgentAction.OpenApp(thought, appName, stepIndex)
            }

            "tap", "click" -> {
                val id = args.get("id")?.asInt
                    ?: args.get("target_id")?.asInt
                    ?: -1
                AgentAction.Tap(thought, id, stepIndex)
            }

            "type", "type_text", "input" -> {
                val id = args.get("id")?.asInt
                    ?: args.get("target_id")?.asInt
                    ?: -1
                val text = args.get("text")?.asString.orEmpty()
                val pressEnter = args.get("enter")?.asBoolean
                    ?: args.get("press_enter")?.asBoolean
                    ?: true
                AgentAction.TypeText(thought, id, text, pressEnter, stepIndex)
            }

            "scroll", "swipe" -> {
                val dirString = args.get("direction")?.asString.orEmpty()
                AgentAction.Scroll(thought, AgentAction.Scroll.Direction.fromString(dirString), stepIndex)
            }

            "back" -> AgentAction.Back(thought, stepIndex)

            "home" -> AgentAction.Home(thought, stepIndex)

            "wait" -> {
                val msFromSec = (args.get("seconds")?.asLong ?: args.get("sec")?.asLong)?.times(1000L)
                val ms = msFromSec
                    ?: args.get("ms")?.asLong
                    ?: args.get("duration")?.asLong
                    ?: 1000L
                AgentAction.Wait(thought, ms.coerceIn(200L, 300_000L), stepIndex)
            }

            "ask_user", "ask" -> {
                val question = args.get("question")?.asString
                    ?: args.get("text")?.asString.orEmpty()
                AgentAction.AskUser(thought, question, stepIndex)
            }

            "done", "finish" -> {
                val summary = args.get("message")?.asString
                    ?: args.get("summary")?.asString
                    ?: "Görev başarıyla tamamlandı."
                AgentAction.Done(thought, summary, stepIndex)
            }

            else -> AgentAction.Unknown(thought, actionName, stepIndex)
        }
    }
}
