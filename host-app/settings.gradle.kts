// 依赖仓库顺序：**官方源优先**。
//
// 为什么顺序重要：
// - 安全：Gradle 按顺序命中第一个提供该模块的仓库。把第三方镜像排在官方源之前，
//   等于让依赖来自非权威渠道（供应链信任边界的扩展）；若镜像被投毒或同步了不同字节，
//   构建出的 APK 用的就是被篡改的依赖。
// - 可复现：后续要做 Reproducible Builds（让 F-Droid 分发我们自己签名的 APK），
//   要求逐字节一致 —— 多一个可变来源就多一个"两次构建结果不同"的理由。
// - F-Droid：其构建机在境外，阿里云镜像只会更慢、更不可控，且审查会关注依赖来源。
//
// 国内开发者的加速镜像保留为**显式开关**，不进默认路径：
//     DSH_CN_MIRROR=1 ./gradlew assembleRelease      （或 -DcnMirror=1）
//
// 注意：`pluginManagement {}` 块的作用域里**看不到**本脚本的顶层变量与函数
// （实测 `Unresolved reference`），所以两个块里各自内联一次判断 —— `System` 是 Java 类，
// 在任何作用域都能解析。

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        if (System.getenv("DSH_CN_MIRROR") != null || System.getProperty("cnMirror") != null) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (System.getenv("DSH_CN_MIRROR") != null || System.getProperty("cnMirror") != null) {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
        }
    }
}

rootProject.name = "DSHA"
include(":app")
