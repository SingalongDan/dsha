import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布版签名：**密钥与口令绝不入库**。
// 从 `keystore.properties`（已 gitignore）或环境变量读取；两者都没有时跳过 release 签名 ——
// 贡献者无需任何密钥即可 `assembleDebug`，只有出正式包的人才需要配置。见 keystore.properties.example。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secretOf(key: String, env: String): String? =
    (keystoreProps.getProperty(key) ?: System.getenv(env))?.takeIf { it.isNotBlank() }

val storePath = secretOf("storeFile", "DSH_KEYSTORE_FILE")
val storePass = secretOf("storePassword", "DSH_KEYSTORE_PASSWORD")
val keyAliasValue = secretOf("keyAlias", "DSH_KEY_ALIAS")
val keyPass = secretOf("keyPassword", "DSH_KEY_PASSWORD")

android {
    namespace = "dev.dsh.host"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dsh.host"
        minSdk = 24
        // 关键：学 Termux 压到 28，落在 untrusted_app_27 域，
        // 保留"从数据目录 exec 二进制"的 SELinux 权限（免 root、免 sepolicy 规则）。
        targetSdk = 28
        versionCode = 1
        versionName = "0.1"
    }

    signingConfigs {
        if (storePath != null && storePass != null && keyAliasValue != null && keyPass != null) {
            create("release") {
                storeFile = rootProject.file(storePath)
                storePassword = storePass
                keyAlias = keyAliasValue
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    aaptOptions {
        // 默认 ignoreAssetsPattern 含 ".*" 会丢掉 .manifest.json 等 dotfile；去掉它。
        ignoreAssetsPattern = "!.svn:!.git:!.ds_store:!*.scc:!CVS:!thumbs.db:!picasa.ini:!*~"
    }

    lint {
        // ExpiredTargetSdkVersion 是"上架 Google Play 需 targetSdk ≥ 33"的检查。
        // 本项目**刻意** targetSdk = 28（落在 untrusted_app_27 域以保留从数据目录 exec 的
        // SELinux 权限，这是"引擎跑在机内"的前提），且**明确侧载、不上架**（见设计文档边界一节）。
        // 该检查在本项目语境下是误报，故显式豁免；其余 lint 仍照常阻断 release 构建。
        disable += "ExpiredTargetSdkVersion"
        abortOnError = true
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // API Key 加密存储（AES-256-GCM，主密钥在 Android Keystore/TEE）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 网络（HTTP + WebSocket）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
