package com.tarslauncher.app.agent.runner

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.tarslauncher.app.agent.domain.model.AgentAction
import com.tarslauncher.app.agent.service.TarsAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import kotlin.math.roundToInt

class ActionExecutor(
    private val context: Context
) {

    suspend fun execute(action: AgentAction): String {
        val service = TarsAccessibilityService.getInstance()
            ?: return "BAŞARISIZ: Erişilebilirlik servisi aktif değil."

        return when (action) {
            is AgentAction.OpenApp -> launchAppByName(action.appName)

            is AgentAction.OpenUrl -> openUrlDirectly(action.url)

            is AgentAction.SearchWeb -> searchWebOrYouTube(action.query, action.platform)

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

            is AgentAction.SetVolume -> adjustDeviceVolume(action.mode, action.level)

            is AgentAction.MediaControl -> dispatchMediaCommand(action.command)

            is AgentAction.Flashlight -> setTorchState(action.enabled)

            is AgentAction.TakeScreenshot -> {
                if (service.gestureDispatcher.performScreenshot()) {
                    "BAŞARILI: Ekran görüntüsü alındı."
                } else {
                    "BAŞARISIZ: Ekran görüntüsü alınamadı."
                }
            }

            is AgentAction.LockScreen -> {
                if (service.gestureDispatcher.performLockScreen()) {
                    "BAŞARILI: Ekran kilitlendi."
                } else {
                    "BAŞARISIZ: Ekran kilitlenemedi."
                }
            }

            is AgentAction.OpenSettings -> openSystemSettings(action.section)

            is AgentAction.SetAlarm -> createSystemAlarm(action.hour, action.minute, action.message)

            is AgentAction.SetTimer -> createSystemTimer(action.seconds, action.message)

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

    private suspend fun adjustDeviceVolume(mode: String, level: Int): String = withContext(Dispatchers.Main) {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stream = AudioManager.STREAM_MUSIC
            val maxVol = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)

            when {
                level in 0..100 || mode == "set" -> {
                    val pct = level.coerceIn(0, 100)
                    val target = ((pct / 100.0) * maxVol).roundToInt().coerceIn(0, maxVol)
                    audioManager.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
                    "BAŞARILI: Medya sesi %$pct seviyesine ayarlandı."
                }
                mode == "mute" || mode == "silent" || mode == "kapat" -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                    "BAŞARILI: Medya sesi tamamen kısıldı (sessize alındı)."
                }
                mode == "unmute" || mode == "aç" -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                    "BAŞARILI: Medya sesi yeniden açıldı."
                }
                mode == "max" || mode == "full" -> {
                    audioManager.setStreamVolume(stream, maxVol, AudioManager.FLAG_SHOW_UI)
                    "BAŞARILI: Medya sesi maksimum (%100) seviyeye çıkarıldı."
                }
                mode == "up" || mode == "raise" || mode == "artır" -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    "BAŞARILI: Ses seviyesi artırıldı."
                }
                else -> {
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    "BAŞARILI: Ses seviyesi kısıldı."
                }
            }
        }.getOrElse {
            "BAŞARISIZ: Ses seviyesi değiştirilemedi (${it.message})."
        }
    }

    private suspend fun dispatchMediaCommand(command: String): String = withContext(Dispatchers.Main) {
        runCatching {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val keyCode = when (command.lowercase()) {
                "play", "başlat", "devam" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause", "stop", "durdur", "duraklat" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                "next", "sonraki", "ileri" -> KeyEvent.KEYCODE_MEDIA_NEXT
                "previous", "prev", "önceki", "geri" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            }
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            "BAŞARILI: Medya komutu ('$command') çalıştırıldı."
        }.getOrElse {
            "BAŞARISIZ: Medya komutu gönderilemedi (${it.message})."
        }
    }

    private suspend fun setTorchState(enabled: Boolean): String = withContext(Dispatchers.Main) {
        runCatching {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return@withContext "BAŞARISIZ: Cihazda flaş/el feneri donanımı bulunamadı."

            cameraManager.setTorchMode(torchCameraId, enabled)
            "BAŞARILI: El feneri ${if (enabled) "açıldı" else "kapatıldı"}."
        }.getOrElse {
            "BAŞARISIZ: El feneri kontrol edilemedi (${it.message})."
        }
    }

    private suspend fun openSystemSettings(section: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val actionIntent = when (section.lowercase()) {
                "wifi", "wi-fi", "internet" -> Settings.ACTION_WIFI_SETTINGS
                "bluetooth", "bt" -> Settings.ACTION_BLUETOOTH_SETTINGS
                "sound", "audio", "ses" -> Settings.ACTION_SOUND_SETTINGS
                "display", "screen", "brightness", "ekran", "parlaklık" -> Settings.ACTION_DISPLAY_SETTINGS
                "battery", "pil" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
                "location", "gps", "konum" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
                "apps", "applications", "uygulamalar" -> Settings.ACTION_APPLICATION_SETTINGS
                "accessibility", "erişilebilirlik" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
                else -> Settings.ACTION_SETTINGS
            }
            val intent = Intent(actionIntent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "BAŞARILI: '$section' sistem ayarları ekranı açıldı."
        }.getOrElse {
            "BAŞARISIZ: Sistem ayarları açılamadı (${it.message})."
        }
    }

    private suspend fun createSystemAlarm(hour: Int, minute: Int, message: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (message.isNotBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            val formattedTime = String.format("%02d:%02d", hour, minute)
            "BAŞARILI: Saat $formattedTime için alarm kuruldu${if (message.isNotBlank()) " ('$message')" else ""}."
        }.getOrElse {
            "BAŞARISIZ: Alarm kurulamadı (${it.message})."
        }
    }

    private suspend fun createSystemTimer(seconds: Int, message: String): String = withContext(Dispatchers.IO) {
        runCatching {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                if (message.isNotBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                }
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            "BAŞARILI: $seconds saniyelik zamanlayıcı (sayaç) başlatıldı."
        }.getOrElse {
            "BAŞARISIZ: Zamanlayıcı başlatılamadı (${it.message})."
        }
    }

    private suspend fun searchWebOrYouTube(query: String, platform: String): String = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return@withContext "BAŞARISIZ: Arama sorgusu boş."
        val encoded = URLEncoder.encode(trimmed, "UTF-8")
        val targetUrl = if (platform.contains("youtube", ignoreCase = true) || platform.contains("yt", ignoreCase = true)) {
            "https://www.youtube.com/results?search_query=$encoded"
        } else {
            "https://www.google.com/search?q=$encoded"
        }
        openUrlDirectly(targetUrl)
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
