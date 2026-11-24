plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.childeshu"
version = "1.0.0"

repositories {
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    mavenCentral()
    intellijPlatform {
        // 2.x 标准写法：同时包含 releases、marketplace 与 JBR 仓库
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 构建所基于的 IntelliJ 平台版本（IDEA Community 2024.1.4）
        // 注意：显式 useInstaller = false，使用 maven 坐标 com.jetbrains.intellij.idea:ideaIC
        // （默认 installer 坐标 idea:ideaIC 在 releases 仓库中无法解析）
        intellijIdeaCommunity("2024.1.4", useInstaller = false)
    }
    // 代码规范约定的判空工具：StrUtil / CollectionUtils
    implementation("cn.hutool:hutool-core:5.8.32")
    implementation("org.apache.commons:commons-collections4:4.4")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

intellijPlatform {
    pluginConfiguration {
        // 插件名必须为拉丁字符（Marketplace 校验），显示名用英文
        name = "Git Helper"
        version = project.version.toString()
        vendor {
            name = "锦舒"
            email = "jiansite@qq.com"
            url = "https://user.qzone.qq.com/1340582309/infocenter"
        }
        ideaVersion {
            // 与构建平台 2024.1（241）保持一致，避免 verifyPluginProjectConfiguration
            // 报 "since-build is lower than the target IntelliJ Platform major version"
            sinceBuild = "241"
        }
    }
    // 插件没有自定义 Settings，跳过 searchable options 任务，
    // 消除 "No searchable options found" 告警并省掉一次 IDE 启动
    buildSearchableOptions = false
    // 发布到 JetBrains Marketplace：
    // token 通过 -PintellijPlatformPublishingToken=xxx 或环境变量
    // ORG_GRADLE_PROJECT_intellijPlatformPublishingToken 注入
    publishing {
        token = providers.gradleProperty("intellijPlatformPublishingToken")
        channels = listOf("default")
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
    buildPlugin {
        // 产物输出到项目内 build/distributions/
        archiveBaseName = "git-helper"
    }
}
