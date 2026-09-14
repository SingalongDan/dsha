package dev.dsh.host

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启：仅在设置里开启「自启引擎」时拉起前台服务（引擎随即启动）。
 * 开机后系统可能仍在用户锁定状态 —— 服务自身有退避重启，延迟失败也会随后重试。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val enabled = context.getSharedPreferences("dsh_host", Context.MODE_PRIVATE)
            .getBoolean("autostart", false)
        if (!enabled) {
            Log.d("DshHost", "boot: autostart disabled, skip")
            return
        }
        runCatching {
            val svc = Intent(context, DshHostService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
            Log.d("DshHost", "boot: autostart engine requested")
        }.onFailure { Log.e("DshHost", "boot autostart failed", it) }
    }
}
