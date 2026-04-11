package com.tarslauncher.app.service

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tarslauncher.app.R
import com.tarslauncher.app.repository.AppRepository
import kotlinx.coroutines.*
import kotlin.math.log10
import kotlin.math.sqrt

class AdaptiveVolumeService : Service() {

    private lateinit var appRepository: AppRepository
    private lateinit var audioManager: AudioManager
    private lateinit var usageStatsManager: UsageStatsManager
    
    private var serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)
    
    private var isMonitoring = false
    private var currentForegroundApp: String? = null
    
    private val NOTIFICATION_ID = 1002
    private val CHANNEL_ID = "adaptive_volume_channel"

    override fun onCreate() {
        super.onCreate()
        appRepository = AppRepository(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("TARS: Ses Hazırlanıyor..."))
        
        startMonitoringLoop()
    }

    private fun startMonitoringLoop() {
        serviceScope.launch {
            while (isActive) {
                val foregroundApp = getForegroundApp()
                val selectedApps = appRepository.getAdaptiveApps()
                
                if (foregroundApp != currentForegroundApp) {
                    currentForegroundApp = foregroundApp
                    val shouldActivate = selectedApps.contains(foregroundApp)
                    
                    if (shouldActivate && !isMonitoring) {
                        startNoiseCompensation()
                    } else if (!shouldActivate && isMonitoring) {
                        stopNoiseCompensation()
                    }
                }
                
                delay(2000) // Poll every 2 seconds
            }
        }
    }

    private fun getForegroundApp(): String? {
        val time = System.currentTimeMillis()
        val usageEvents = usageStatsManager.queryEvents(time - 5000, time)
        val event = UsageEvents.Event()
        var lastPackage: String? = null
        
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPackage = event.packageName
            }
        }
        return lastPackage
    }

    private var noiseJob: Job? = null

    private fun startNoiseCompensation() {
        if (isMonitoring) return
        isMonitoring = true
        updateNotification("TARS: Ses Optimize Ediliyor")
        
        noiseJob = serviceScope.launch(Dispatchers.Default) {
            val sampleRate = 44100
            val bufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            
            if (bufferSize <= 0) return@launch

            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: SecurityException) {
                null
            } ?: return@launch

            try {
                audioRecord.startRecording()
                val buffer = ShortArray(bufferSize)
                var smoothedDb = 40.0 // Starting baseline (quiet room)
                val alpha = 0.1 // Smoothing factor

                while (isMonitoring && isActive) {
                    val read = audioRecord.read(buffer, 0, bufferSize)
                    if (read > 0) {
                        // RMS calculation
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i] * buffer[i]
                        }
                        val rms = sqrt(sum / read)
                        val db = if (rms > 0) 20 * log10(rms) else 0.0
                        
                        // Exponential smoothing
                        smoothedDb = (1 - alpha) * smoothedDb + alpha * db
                        
                        adjustVolume(smoothedDb)
                    }
                    delay(1000) // Adjust every second
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }
        }
    }

    private fun stopNoiseCompensation() {
        isMonitoring = false
        noiseJob?.cancel()
        updateNotification("TARS: Ses İzleniyor (Hazır)")
    }

    private fun adjustVolume(db: Double) {
        val sensitivity = appRepository.getAdaptiveSensitivity()
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        
        // Simple mapping: 40dB (quiet) -> Min Volume, 90dB (loud) -> Max Volume
        // Formula: Volume = ((db - 40) / (90 - 40)) * maxVolume * sensitivity
        
        val normalizedDb = ((db - 35.0) / 45.0).coerceIn(0.0, 1.0)
        val targetVolume = (normalizedDb * maxVolume * sensitivity * 1.5).toInt().coerceIn(2, maxVolume)
        
        val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        // Don't adjust if the change is too small (prevent jumping)
        if (kotlin.math.abs(targetVolume - currentVolume) >= 1) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Adaptive Volume Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        val intent = Intent(this, com.tarslauncher.app.MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TARS Adaptive Volume")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        stopNoiseCompensation()
        super.onDestroy()
    }
}
