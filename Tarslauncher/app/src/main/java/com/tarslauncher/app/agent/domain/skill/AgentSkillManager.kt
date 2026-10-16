package com.tarslauncher.app.agent.domain.skill

import android.content.Context
import com.google.gson.JsonObject
import com.tarslauncher.app.agent.data.firebase.FirebaseAgentLogger
import com.tarslauncher.app.agent.data.supabase.SupabaseAgentLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentSkillManager(
    private val context: Context,
    private val firebaseLogger: FirebaseAgentLogger = FirebaseAgentLogger(context),
    private val supabaseLogger: SupabaseAgentLogger = SupabaseAgentLogger(context)
) {

    private val skillsDir = File(context.filesDir, "skills").apply { mkdirs() }
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    private val trLocale = Locale.forLanguageTag("tr-TR")

    suspend fun saveSkill(
        goal: String,
        targetPackage: String,
        stepsSummary: List<String>
    ): File? = withContext(Dispatchers.IO) {
        if (stepsSummary.isEmpty()) return@withContext null

        val sanitizedGoalName = goal.lowercase(trLocale)
            .replace(Regex("""[^a-z0-9ğüşıöç]+"""), "_")
            .trim('_')
            .take(40)

        val fileName = "skill_${sanitizedGoalName}.md"
        val skillFile = File(skillsDir, fileName)

        val dateStr = dateFormat.format(Date())
        val content = buildString {
            appendLine("# Skill: $goal")
            appendLine("- **Tarih:** $dateStr")
            appendLine("- **Uygulama Paketi:** $targetPackage")
            appendLine()
            appendLine("## Başarılı Eylem Sırası:")
            stepsSummary.forEachIndexed { index, step ->
                appendLine("${index + 1}. $step")
            }
            appendLine()
            appendLine("> Bu beceri TARS Agent tarafından başarıyla tamamlanmış ve otonom olarak kaydedilmiştir.")
        }

        skillFile.writeText(content, Charsets.UTF_8)

        // Sync to Firebase Firestore
        firebaseLogger.logSkill(fileName, goal, targetPackage, content)

        // Also sync to Supabase if configured
        if (supabaseLogger.isConfigured()) {
            val skillJson = JsonObject().apply {
                addProperty("id", fileName)
                addProperty("title", goal)
                addProperty("target_package", targetPackage)
                addProperty("markdown_content", content)
                addProperty("created_at", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
            }
            supabaseLogger.logSkill(skillJson)
        }

        skillFile
    }

    suspend fun findRelevantSkill(goal: String): String? = withContext(Dispatchers.IO) {
        val files = skillsDir.listFiles { _, name -> name.endsWith(".md") } ?: return@withContext null
        if (files.isEmpty()) return@withContext null

        val goalTokens = goal.lowercase(trLocale)
            .split(Regex("""[^\p{L}\p{Nd}]+"""))
            .filter { it.length > 2 }
        if (goalTokens.isEmpty()) return@withContext null

        var bestContent: String? = null
        var maxScore = 0

        for (file in files) {
            val content = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
            val headerLine = content.lineSequence().firstOrNull().orEmpty().lowercase(trLocale)
            val searchableTokens = (file.nameWithoutExtension.lowercase(trLocale) + " " + headerLine)
                .split(Regex("""[^\p{L}\p{Nd}_]+"""))
                .filter { it.length > 2 }

            val score = goalTokens.count { token ->
                searchableTokens.any { it.contains(token) || token.contains(it) }
            }
            if (score > maxScore && score >= 1) {
                maxScore = score
                bestContent = content
            }
        }

        bestContent
    }

    suspend fun getSkillsCount(): Int = withContext(Dispatchers.IO) {
        skillsDir.listFiles { _, name -> name.endsWith(".md") }?.size ?: 0
    }
}
