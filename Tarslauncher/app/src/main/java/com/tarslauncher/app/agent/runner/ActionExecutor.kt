package com.tarslauncher.app.agent.runner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.service.TarsAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class ActionExecutor(
    private val context: Context
) {

    suspend fun execute(action: AgentAction): String {
        val service = TarsAccessibilityService.getInstance()
            ?: return "BAŞARISIZ: Erişilebilirlik servisi aktif değil."

        return when (action) {
            is AgentAction.OpenApp -> launchAppByName(action.appName)

            is AgentAction.OpenUrl -> openUrlDirectly(action.url)

            is AgentAction.Tap -> {
                val node = service.treeParser.getNode(action.targetId)
                val summary = service.treeParser.getNodeSummary(action.targetId)?.take(40).orEmpty()
                if (node == null) {
                    "BAŞARISIZ: [${action.targetId}] numaralı öğe ekranda bulunamadı."
                } else if (service.gestureDispatcher.performClick(node)) {
                    "BAŞARILI: [${action.targetId}] ($summary) öğesine dokunuldu."
                } else {
                    "BAŞARISIZ: [${action.targetId}] numaralı öğeye dokunulamadı."
                }
            }

            is AgentAction.LongPress -> {
                val node = service.treeParser.getNode(action.targetId)
                val summary = service.treeParser.getNodeSummary(action.targetId)?.take(40).orEmpty()
                if (node == null) {
                    "BAŞARISIZ: [${action.targetId}] numaralı öğe ekranda bulunamadı."
                } else if (service.gestureDispatcher.performLongClick(node)) {
                    "BAŞARILI: [${action.targetId}] ($summary) öğesine uzun basıldı."
                } else {
                    "BAŞARISIZ: [${action.targetId}] numaralı öğeye uzun basılamadı."
                }
            }

            is AgentAction.TypeText -> {
                val node = service.treeParser.getNode(action.targetId)
                if (node == null) {
                    "BAŞARISIZ: [${action.targetId}] numaralı metin alanı bulunamadı."
                } else if (service.gestureDispatcher.typeText(node, action.text, action.pressEnter)) {
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

            is AgentAction.Recents -> {
                if (service.gestureDispatcher.performRecents()) {
                    "BAŞARILI: Son kullanılan uygulamalar açıldı."
                } else {
                    "BAŞARISIZ: Son kullanılan uygulamalar açılamadı."
                }
            }

            is AgentAction.Notifications -> {
                if (service.gestureDispatcher.performNotifications()) {
                    "BAŞARILI: Bildirim paneli açıldı."
                } else {
                    "BAŞARISIZ: Bildirim paneli açılamadı."
                }
            }

            is AgentAction.QuickSettings -> {
                if (service.gestureDispatcher.performQuickSettings()) {
                    "BAŞARILI: Hızlı ayarlar paneli açıldı."
                } else {
                    "BAŞARISIZ: Hızlı ayarlar paneli açılamadı."
                }
            }

            is AgentAction.ClipboardSet -> {
                withContext(Dispatchers.Main) {
                    runCatching {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("TARS", action.text))
                        "BAŞARILI: Metin panoya kopyalandı."
                    }.getOrElse {
                        "BAŞARISIZ: Panoya kopyalanamadı (${it.message})."
                    }
                }
            }

            is AgentAction.Wait -> {
                val sec = action.durationMs / 1000L
                delay(action.durationMs)
                if (sec > 1) {
                    "BAŞARILI: $sec saniye beklendi."
                } else {
                    "BAŞARILI: ${action.durationMs}ms beklendi."
                }
            }

            is AgentAction.AskUser,
            is AgentAction.Done -> "TAMAMLANDI"

            is AgentAction.Unknown -> "BAŞARISIZ: Desteklenmeyen veya geçersiz eylem '${action.rawAction}'."
        }
    }

    private suspend fun openUrlDirectly(rawUrl: String): String = withContext(Dispatchers.IO) {
        val trimmed = rawUrl.trim()
        if (trimmed.isBlank()) return@withContext "BAŞARISIZ: Boş URL açılamaz."
        val formattedUrl = if (!trimmed.contains("://")) "https://$trimmed" else trimmed
        runCatching {
            val uri = Uri.parse(formattedUrl)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                return@withContext "BAŞARISIZ: Yalnızca http/https bağlantıları desteklenir ($formattedUrl)."
            }
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "BAŞARILI: '$formattedUrl' bağlantısı açıldı."
        }.getOrElse {
            "BAŞARISIZ: '$formattedUrl' açılamadı (${it.message})."
        }
    }

    private suspend fun launchAppByName(query: String): String = withContext(Dispatchers.IO) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank()) {
            return@withContext "BAŞARISIZ: Uygulama adı boş."
        }
        val packageManager = context.packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
        // Prefer exact label match first, then prefix match, then substring match, then package name match
        val labeledInfos = resolveInfos.map { info ->
            info to info.loadLabel(packageManager).toString().trim()
        }
        val matched = (
            labeledInfos.firstOrNull { (_, label) -> label.equals(trimmedQuery, ignoreCase = true) }
                ?: labeledInfos.firstOrNull { (_, label) -> label.startsWith(trimmedQuery, ignoreCase = true) }
                ?: labeledInfos.firstOrNull { (_, label) -> label.contains(trimmedQuery, ignoreCase = true) }
                ?: labeledInfos.firstOrNull { (info, _) -> info.activityInfo.packageName.contains(trimmedQuery, ignoreCase = true) }
            )?.first

        if (matched == null) {
            return@withContext "BAŞARISIZ: '$trimmedQuery' adında yüklü bir uygulama bulunamadı."
        }

        val packageName = matched.activityInfo.packageName
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return@withContext "BAŞARISIZ: '$trimmedQuery' uygulaması başlatılamadı."

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        context.startActivity(launchIntent)
        "BAŞARILI: '$trimmedQuery' uygulaması açıldı ($packageName)."
    }
}

