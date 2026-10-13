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

            mapToAction(thought, actionName, args)
        }.getOrNull()
    }

    private fun mapToAction(thought: String, actionName: String, args: JsonObject): AgentAction {
        return when (actionName) {
            "open_app" -> {
                val appName = args.get("name")?.asString
                    ?: args.get("app")?.asString
                    ?: args.get("app_name")?.asString.orEmpty()
                AgentAction.OpenApp(thought, appName)
            }

            "tap", "click" -> {
                val id = args.get("id")?.asInt
                    ?: args.get("target_id")?.asInt
                    ?: -1
                AgentAction.Tap(thought, id)
            }

            "type", "type_text", "input" -> {
                val id = args.get("id")?.asInt
                    ?: args.get("target_id")?.asInt
                    ?: -1
                val text = args.get("text")?.asString.orEmpty()
                AgentAction.TypeText(thought, id, text)
            }

            "scroll", "swipe" -> {
                val dirString = args.get("direction")?.asString.orEmpty()
                AgentAction.Scroll(thought, AgentAction.Scroll.Direction.fromString(dirString))
            }

            "back" -> AgentAction.Back(thought)

            "home" -> AgentAction.Home(thought)

            "wait" -> {
                val ms = args.get("ms")?.asLong
                    ?: args.get("duration")?.asLong
                    ?: 1000L
                AgentAction.Wait(thought, ms.coerceIn(200L, 5000L))
            }

            "ask_user", "ask" -> {
                val question = args.get("question")?.asString
                    ?: args.get("text")?.asString.orEmpty()
                AgentAction.AskUser(thought, question)
            }

            "done", "finish" -> {
                val summary = args.get("message")?.asString
                    ?: args.get("summary")?.asString
                    ?: "Görev başarıyla tamamlandı."
                AgentAction.Done(thought, summary)
            }

            else -> AgentAction.Unknown(thought, actionName)
        }
    }
}
