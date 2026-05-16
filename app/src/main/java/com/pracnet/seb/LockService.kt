package com.pracnet.seb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class LockService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var isRunning = false

    private val checkTask = object : Runnable {
        override fun run() {
            if (!isRunning) return

            // Bawa app ke foreground terus-menerus
            val intent = Intent(this@LockService, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(intent)

            // Cek setiap 500ms
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(1, notification)
        isRunning = true
        handler.post(checkTask)
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacks(checkTask)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "seb_lock_channel",
                "Mode Ujian Aktif",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menjaga aplikasi ujian tetap aktif"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "seb_lock_channel")
            .setContentTitle("Pracnet SEB")
            .setContentText("Mode ujian sedang aktif")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
