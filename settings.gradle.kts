pluginManagement {
    repositories {
        // 阿里云镜像 Gradle 插件门户，国内加速
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // 允许 Gradle 在本地找不到 JDK 17 时，从 Foojay 自动下载（供 build.gradle.kts 中的 toolchain 使用）
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "git-helper"
