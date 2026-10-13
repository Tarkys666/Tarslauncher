package com.tarslauncher.app.agent.runner

import android.content.Context
import android.content.Intent
import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.service.TarsAccessibilityService
import kotlinx.coroutines.delay

class ActionExecutor(
    private val context: Context
) {

    suspend fun execute(action: AgentAction): String {
        val service = TarsAccessibilityService.getInstance()
            ?: return "BAŞARISIZ: Erişilebilirlik servisi aktif değil."

        return when (action) {
            is AgentAction.OpenApp -> launchAppByName(action.appName)

            is AgentAction.Tap -> {
                val node = service.treeParser.getNode(action.targetId)
                if (node == null) {
                    "BAŞARISIZ: [${action.targetId}] numaralı öğe ekranda bulunamadı."
                } else if (service.gestureDispatcher.performClick(node)) {
                    "BAŞARILI: [${action.targetId}] numaralı öğeye dokunuldu."
                } else {
                    "BAŞARISIZ: [${action.targetId}] numaralı öğeye dokunulamadı."
                }
            }

            is AgentAction.TypeText -> {
                val node = service.treeParser.getNode(action.targetId)
                if (node == null) {
                    "BAŞARISIZ: [${action.targetId}] numaralı metin alanı bulunamadı."
                } else if (service.gestureDispatcher.typeText(node, action.text)) {
                    "BAŞARILI: [${action.targetId}] alanına '${action.text}' yazıldı."
                } else {
                    "BAŞARISIZ: [${action.targetId}] alanına yazılamadı."
                }
            }

            is AgentAction.Scroll -> {
                val isDown = action.direction == AgentAction.Scroll.Direction.DOWN
                if (service.gestureDispatcher.performScroll(isDown)) {
                    "BAŞARILI: Ekran ${if (isDown) "aşağı" else "yukarı"} kaydırıldı."
                } else {
                    "BAŞARISIZ: Ekran kaydırılamadı."
                }
            }

            is AgentAction.Back -> {
                if (service.gestureDispatcher.performBack()) {
                    "BAŞARILI: Geri tuşuna basıldı."
                } else {
                    "BAŞARISIZ: Geri tuşu çalışmadı."
                }
            }

            is AgentAction.Home -> {
                if (service.gestureDispatcher.performHome()) {
                    "BAŞARILI: Ana ekrana dönüldü."
                } else {
                    "BAŞARISIZ: Ana ekrana dönülemedi."
                }
            }

            is AgentAction.Wait -> {
                delay(action.durationMs)
                "BAŞARILI: ${action.durationMs}ms beklendi."
            }

            is AgentAction.AskUser,
            is AgentAction.Done -> "TAMAMLANDI"

            is AgentAction.Unknown -> "BAŞARISIZ: Desteklenmeyen veya geçersiz eylem '${action.rawAction}'."
        }
    }

    private suspend fun launchAppByName(query: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val packageManager = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
        val matched = resolveInfos.firstOrNull { info ->
            val label = info.loadLabel(packageManager).toString()
            label.contains(query, ignoreCase = true)
        }

        if (matched == null) {
            return@withContext "BAŞARISIZ: '$query' adında yüklü bir uygulama bulunamadı."
        }

        val packageName = matched.activityInfo.packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext "BAŞARISIZ: '$query' uygulaması başlatılamadı."

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
        "BAŞARILI: '$query' uygulaması açıldı ($packageName)."
    }
}
