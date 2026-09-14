package com.example.dshhost

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File

/**
 * dsh 引擎宿主服务：前台常驻 + spawn node + 崩溃退避重启。
 * 骨架代码，最终在 Android Studio 里按 applicationId 与通知样式补全。
 */
class DshHostService : Service() {

    companion object {
        private const val CHANNEL_ID = "dsh_host"
        private const val NOTIF_ID = 1
        private const val RESTART_BASE_MS = 1_000L
        private const val RESTART_MAX_MS = 30_000L
    }

    @Volatile private var proc: Process? = null
    @Volatile private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (!running) {
            running = true
            Thread(::engineLoop, "dsh-host").start()
        }
        return START_STICKY
    }

    private fun engineLoop() {
        var backoff = RESTART_BASE_MS
        while (running) {
            val prefix = File(filesDir, "usr")
            val home = File(filesDir, "home")
            val env = mapOf(
                "PREFIX" to prefix.absolutePath,
                "HOME" to home.absolutePath,
                "TMPDIR" to File(prefix, "tmp").absolutePath,
                "PATH" to "${prefix}/bin:${prefix}/bin/applets",
                "LD_LIBRARY_PATH" to "${prefix}/lib",
                "DSH_HOME" to File(home, ".dsh").absolutePath,
                "NO_COLOR" to "1",
            )
            val cmd = listOf(
                "${prefix}/bin/node", "--expose-internals",
                "${prefix}/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
                "web", "--port", "3080", "--no-open",
            )
            try {
                proc = ProcessBuilder(cmd)
                    .directory(prefix)
                    .apply { environment().putAll(env) }
                    .start()
                val code = proc!!.waitFor()
                if (code == 0) break // 干净退出
            } catch (_: Exception) {
                // 启动失败，退避后重试
            } finally {
                proc?.destroy()
            }
            backoff = (backoff * 2).coerceAtMost(RESTART_MAX_MS)
            Thread.sleep(backoff)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "dsh engine", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("dsh engine")
            .setContentText("DeepSeek Harness 运行中")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }

    override fun onDestroy() {
        running = false
        proc?.destroy()
        super.onDestroy()
    }
}
