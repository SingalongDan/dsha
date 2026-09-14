package io.github.singalongdan.dsha

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API Key 的**加密存储**。
 *
 * 用 `EncryptedSharedPreferences`（AES-256-GCM，主密钥存在 Android Keystore / TEE，不可导出），
 * 取代此前的明文偏好。防护边界要说清楚：
 * - **能防**：拿到存储镜像 / 恢复模式 / 取证镜像时只得到密文；主密钥在 TEE 里取不出来。
 * - **不能防**：已 root 的设备。root 进程既可能以应用身份调用 Keystore 解密，也能从引擎进程的
 *   环境变量（`/proc/<pid>/environ`）读到同一把密钥 —— 所以这是"抬高门槛"，不是铜墙铁壁。
 *   文档里已如实说明，并建议用户使用可随时吊销的密钥。
 *
 * 健壮性约定：**解密失败绝不崩溃**。Keystore 在系统升级/恢复备份后损坏是已知问题，
 * 此时一律回退到"未设置"，让用户重新填写（`lostDueToFailure` 供 UI 提示一次）。
 */
object SecurePrefs {
    private const val TAG = "DshSecure"
    private const val SECURE_FILE = "dsh_host_secure"
    private const val LEGACY_FILE = "dsh_host"          // 旧的明文偏好（迁移来源）
    private const val KEY_API = "api_key"

    @Volatile
    private var decryptFailed = false

    /** 缓存实例：`apiKey()` 会被**组合期主线程**调用（设置页），每次重建要走 Keystore + 文件解密，
     * 那是主线程磁盘/加密开销（StrictMode diskRead 违规）。失败也缓存，避免每次重试。 */
    @Volatile
    private var cached: SharedPreferences? = null
    @Volatile
    private var tried = false

    /** 是否曾因解密失败而丢失过密钥（供 UI 提示"请重新填写"）。 */
    fun lostDueToFailure(): Boolean = decryptFailed

    /** 加密库文件是否存在（用于区分"从未设置"与"曾设置但解密失败"）。 */
    private fun storeExists(ctx: Context): Boolean =
        runCatching { java.io.File(ctx.filesDir.parentFile, "shared_prefs/$SECURE_FILE.xml").exists() }
            .getOrDefault(false)

    private fun secure(ctx: Context): SharedPreferences? {
        cached?.let { return it }
        if (tried) return null
        synchronized(this) {
            cached?.let { return it }
            if (tried) return null
            tried = true
            return runCatching {
                val master = MasterKey.Builder(ctx, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    ctx,
                    SECURE_FILE,
                    master,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
                )
            }.onSuccess { cached = it }
                .onFailure {
                    // 真实故障（Keystore 被重置 / 文件损坏）走的就是这条路径：
                    // create() 抛异常 → 返回 null，而调用方 `sp?.getString()` 不会抛，
                    // 所以**必须在这里**置失败标记，否则 UI 的"解密失败请重新填写"永不出现。
                    Log.e(TAG, "encrypted prefs unavailable", it)
                    if (storeExists(ctx)) decryptFailed = true
                }.getOrNull()
        }
    }

    /** 读取 API Key；首次调用会把旧的明文值迁移过来并删除明文。 */
    fun apiKey(ctx: Context): String {
        val sp = secure(ctx)
        val encrypted = runCatching { sp?.getString(KEY_API, null) }.getOrElse {
            decryptFailed = true
            Log.e(TAG, "read failed", it)
            null
        }
        if (!encrypted.isNullOrEmpty()) return encrypted

        // 迁移：旧版本把密钥明文存在 dsh_host 里
        val legacy = ctx.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)
        val plain = runCatching { legacy.getString(KEY_API, null) }.getOrNull()
        if (!plain.isNullOrEmpty()) {
            if (setApiKey(ctx, plain)) {
                legacy.edit().remove(KEY_API).apply()
                Log.d(TAG, "migrated plaintext api_key to encrypted store")
                return plain
            }
            return plain          // 加密存储不可用：至少别让用户重新填
        }
        return ""
    }

    /** 写入（空串表示清除）。返回是否成功写入加密存储。 */
    fun setApiKey(ctx: Context, key: String): Boolean {
        val sp = secure(ctx) ?: return false
        return runCatching {
            sp.edit().putString(KEY_API, key).commit()
        }.onFailure { Log.e(TAG, "write failed", it) }.getOrDefault(false)
    }
}
