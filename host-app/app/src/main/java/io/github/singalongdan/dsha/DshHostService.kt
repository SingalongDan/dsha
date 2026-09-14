package io.github.singalongdan.dsha

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File
import java.io.IOException

/**
 * dsh 引擎宿主服务：前台常驻 + 首启抽取 assets + spawn node + 崩溃退避重启。
 */
class DshHostService : Service() {

    companion object {
        private const val CHANNEL_ID = "dsh_host"
        private const val NOTIF_ID = 1
        private const val RESTART_BASE_MS = 1_000L
        private const val RESTART_MAX_MS = 30_000L
        private const val EXTRACT_MARKER = ".extracted.v3"

        fun restart(context: android.content.Context) {
            val i = android.content.Intent(context, DshHostService::class.java)
                .putExtra("restart", true)
            context.startForegroundService(i)
        }
    }

    @Volatile private var proc: Process? = null
    @Volatile private var running = false
    @Volatile private var engineThread: Thread? = null
    @Volatile private var backoff: Long = RESTART_BASE_MS

    /**
     * 仅在服务真正被销毁时置真。**引擎以退出码 0 结束也必须重启**：
     * dsh CLI 收到 SIGTERM 会优雅退出（exit 0），若把 0 当成"用户主动停止"就会永久停摆看门狗
     * （实测：pkill 引擎后服务仍在、却再无 node 进程、3080 无监听）。
     */
    @Volatile private var serviceStopping = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        val restart = intent?.getBooleanExtra("restart", false) == true
        if (restart) {
            // 重启：**必须先确认旧进程真的退出**再拉起新的。
            // 原实现只 destroy()（SIGTERM）就立刻起新线程，而 Process.waitFor() 不响应 interrupt →
            // 旧 node 若忽略 SIGTERM（正在写会话日志/跑工具），3080 端口仍被占用，新进程直接
            // EADDRINUSE 退出 → 看门狗再退避重启，用户看到的是"点了重启，几分钟用不了"。
            backoff = RESTART_BASE_MS
            engineThread?.interrupt()
            Thread {
                runCatching {
                    proc?.let { p ->
                        p.destroy()
                        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            android.util.Log.w("DshHost", "engine ignored SIGTERM, killing")
                            p.destroyForcibly()
                            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                        }
                    }
                    engineThread?.join(2000)
                }
                proc = null
                if (engineThread?.isAlive != true) {
                    engineThread = Thread(::engineLoop, "dsh-host").also { it.start() }
                }
            }.start()
        } else if (!running) {
            running = true
            engineThread = Thread(::engineLoop, "dsh-host").also { it.start() }
        }
        return START_STICKY
    }

    /** root 模式：生成一个 `bash` 包装脚本，让 dsh 的 bash 工具经 su 跑（需 KSU 授权本 App）。 */
    private fun ensureRootWrapper(prefix: File): File {
        val rootBin = File(prefix, "bin-root").apply { mkdirs() }
        val wrapper = File(rootBin, "bash")
        // 用绝对路径 su（/system/bin/su，KernelSU）；每次重建（内容可能更新）
        wrapper.writeText("#!/system/bin/sh\nexec /system/bin/su 0 \"${prefix}/bin/bash\" \"\$@\"\n")
        wrapper.setExecutable(true, false)
        return rootBin
    }

    /** 首启：把 assets/usr 抽取到 files/usr 并给可执行位。幂等（靠 marker 文件）。 */
    private fun ensurePrefix(): File {
        val prefix = File(filesDir, "usr")
        val marker = File(filesDir, EXTRACT_MARKER)
        if (!marker.exists()) {
            copyAssetsDir("usr", prefix)
            // 给可执行文件 +x（assets 不保留权限位）
            File(prefix, "bin").walkTopDown().filter { it.isFile }.forEach { it.setExecutable(true, false) }
            File(prefix, "lib/node_modules")
                .walkTopDown().filter { it.isFile && it.name.endsWith(".node") }
                .forEach { it.setExecutable(true, false) }
            marker.createNewFile()
        }
        // dsh 需要 $PREFIX/tmp（spill 等 mkdtemp），assets 不带目录，这里补齐
        File(prefix, "tmp").mkdirs()
        return prefix
    }

    /** 递归抽取 assets 目录：先当文件 open，失败（IOException）说明是目录再递归。 */
    private fun copyAssetsDir(srcPath: String, destDir: File) {
        val names = assets.list(srcPath) ?: return
        destDir.mkdirs()
        for (name in names) {
            val childPath = if (srcPath.isEmpty()) name else "$srcPath/$name"
            val child = File(destDir, name)
            try {
                assets.open(childPath).use { input ->
                    child.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: IOException) {
                copyAssetsDir(childPath, child)
            }
        }
    }

    /**
     * API Key：**只使用用户在设置页填写并保存在本机的值**。
     *
     * 这里刻意**不提供任何内置/默认密钥** —— 把密钥写进源码等于随仓库公开泄露他人密钥，
     * 也会让"引擎的密钥从哪来"变得不可审计。未填写时返回空串，由调用方完全不注入该环境变量
     * （引擎会走它自己的凭据存储，或明确报错提示需要密钥）。
     */
    private fun apiKey(): String = SecurePrefs.apiKey(this).trim()

    private fun engineLoop() {
        while (running) {
            val prefix = ensurePrefix()
            val home = File(filesDir, "home").apply { mkdirs() }
            // root 模式：files/root_mode 标记存在时，bash 工具经 su 跑
            val rootMode = File(filesDir, "root_mode").exists()
            val path = if (rootMode) {
                "${ensureRootWrapper(prefix).absolutePath}:${prefix}/bin:${prefix}/bin/applets:/data/adb/ksu/bin:/system/xbin:/system/bin"
            } else {
                "${prefix}/bin:${prefix}/bin/applets"
            }
            // API Key：**仅当用户在本机设置过才注入**。没有任何内置默认值 ——
            // 未设置时干脆不传该变量，让引擎走自己的凭据存储或明确报错，而不是拿一个来路不明的密钥去请求。
            val userKey = apiKey()
            val env = buildMap {
                put("PREFIX", prefix.absolutePath)
                put("HOME", home.absolutePath)
                put("TMPDIR", File(prefix, "tmp").absolutePath)
                put("PATH", path)
                put("LD_LIBRARY_PATH", "${prefix}/lib")
                put("DSH_HOME", File(home, ".dsh").absolutePath)
                // node 的 openssl 把 Termux 路径烙进编译 → 用自带 CA 证书 + 配置覆盖
                put("OPENSSL_CONF", "${prefix}/etc/tls/openssl.cnf")
                put("SSL_CERT_FILE", "${prefix}/etc/tls/cert.pem")
                put("NO_COLOR", "1")
                if (userKey.isNotEmpty()) put("DEEPSEEK_API_KEY", userKey)
            }
            val cmd = listOf(
                "${prefix}/bin/node", "--expose-internals",
                "--require", "${prefix}/lib/dns-fix.js",
                "${prefix}/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
                "web", "--port", "3080", "--no-open",
            )
            try {
                val logFile = File(filesDir, "dsh-node.log")
                // 日志轮转：引擎 stdout/stderr 此前无限追加（持续吃存储，且内含引擎 token 与会话正文）。
                // 超过 2MB 即清空——新进程启动会立即写入新的 token 行，readTail 仍能取到。
                if (logFile.exists() && logFile.length() > 2L * 1024 * 1024) {
                    runCatching { logFile.delete() }
                }
                proc = ProcessBuilder(cmd)
                    .directory(prefix)
                    .apply { environment().putAll(env) }
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                    .start()
                val code = proc!!.waitFor()
                // 退出码 0 也重启（dsh 优雅退出 = 引擎停止服务，客户端需要它一直在线）；
                // 只有服务被销毁（serviceStopping）才结束监督循环。
                android.util.Log.i("DshHost", "engine exited code=$code, restart in ${backoff}ms")
                if (serviceStopping) break
            } catch (_: Exception) {
                // 启动失败，退避后重试
            } finally {
                proc?.destroy()
            }
            backoff = (backoff * 2).coerceAtMost(RESTART_MAX_MS)
            try {
                Thread.sleep(backoff)
            } catch (_: InterruptedException) {
                // 重启请求：立即重试
            }
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
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .build()
    }

    override fun onDestroy() {
        serviceStopping = true          // 只有这里才允许监督循环结束
        running = false
        engineThread?.interrupt()
        proc?.destroy()
        super.onDestroy()
    }
}
