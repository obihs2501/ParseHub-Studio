import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
}

group = "io.github.obihs2501.parsehubstudio"
version = "0.5.0"

// WiX light.exe 在 Windows 上对路径长度敏感（CI 上 D:\a\... 会触发 exit 311）。
// 允许通过环境变量把 build 目录指到短路径。
System.getenv("PARSEHUB_BUILD_DIR")?.let { layout.buildDirectory.set(file(it)) }

repositories {
    // miuix 及大部分依赖在 Maven Central
    mavenCentral()
    // google/androidx（compose 传递依赖）
    maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

dependencies {
    implementation(compose.desktop.currentOs)

    // miuix UI 组件库（Apache-2.0）
    val miuix = "0.9.4"
    implementation("top.yukonga.miuix.kmp:miuix-ui-desktop:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-preference-desktop:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-icons-desktop:$miuix")
    implementation("top.yukonga.miuix.kmp:miuix-blur-desktop:$miuix")

    // HTTP + JSON（调 Python sidecar 的 28 个 API）
    implementation("io.ktor:ktor-client-core:3.0.3")
    implementation("io.ktor:ktor-client-cio:3.0.3")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // Windows Mica（DWM API）
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

compose.desktop {
    application {
        mainClass = "io.github.obihs2501.parsehubstudio.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "ParseHubStudio"
            packageVersion = "0.5.0"
            description = "ParseHub 可视化桌面应用：解析社交平台内容并可视化预览、下载"
            copyright = "ParseHub and ParseHub Studio contributors"
            vendor = "obihs2501"

            // 随应用分发 parsehub-sidecar.exe（构建时放在 resources/）
            appResourcesRootDir.set(project.layout.projectDirectory.dir("resources"))

            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8c1f2a44-9d3b-4e77-8a11-6f0e2b9c4d31"
            }
        }
    }
}
