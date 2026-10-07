import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
}

group = "io.github.obihs2501.parsehubstudio"
// 0.6.4：抖音「真·原画」（ratio=default 的未转码原始档，可能几 GB）+ 大文件断点续传
// （分片下到一半中断不再从 0 重下）。
// 每发新版都要抬版本号 —— 版本号不变时 MSI 会拒绝覆盖安装（用户得先卸载），0.6.0 就吃过这个亏。
version = "0.6.4"

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
    // ktor 版本需 >= 3.1.0：coil-network-ktor3 3.6.3 依赖 ktor-client-core 3.1.0
    val ktor = "3.3.1"
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

    // 图片加载（缩略图 / 灯箱），走 sidecar 的 /api/media 代理
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-ktor3:3.6.3")

    // Windows Mica（DWM API）
    implementation("net.java.dev.jna:jna:5.15.0")
    implementation("net.java.dev.jna:jna-platform:5.15.0")

    // 播放器：只用 vlcj（调用本机安装的 VLC / libvlc）—— 覆盖 HEVC / .ts / MKV 等。
    // 内置 JavaFX 播放器已按反馈移除：它只能放 H.264/AAC，还白占约 35 MB native。
    // 没装 VLC 时界面给出「下载 VLC / 用系统播放器打开」。
    implementation("uk.co.caprica:vlcj:4.8.3")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

// CI 上测试失败时把断言详情打进日志（否则只看到一行 FAILED，得去翻 HTML 报告）
tasks.withType<Test> {
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        events("failed")
    }
}

compose.desktop {
    application {
        mainClass = "io.github.obihs2501.parsehubstudio.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "ParseHubStudio"
            packageVersion = "0.6.4"
            // MSI/EXE 元数据必须保持纯 ASCII：jpackage 的 description 会写进 MSI 的
            // Summary Information，而 WiX 3 的 light.exe 按构建机 locale 选数据库代码页 ——
            // CI 是 en-US → 1252，中文描述直接 LGHT0311 退出 311；本机 zh-CN → 936 只是把
            // 中文悄悄替换成空格（本地 MSI 的 Subject 实测就是「ParseHub     :     」）。
            // 中文说明放在 README 与应用界面里。
            description = "ParseHub Studio: parse, preview and download media from social platforms"
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
                // 刻意**不设** installationPath：插件确实会把它当 jpackage 的 --install-dir 传下去，
                // 但 jpackage 在 Windows 只把它当「per-user 根目录下的相对目录名」—— 传绝对路径
                // （试过 C:\softwares\ParsehubStudio）被忽略并回落到 packageName，传相对名则得到
                // %LOCALAPPDATA%\<名字>，都到不了 C:\softwares\ParsehubStudio。模板覆盖那条路也堵死
                // （插件的 --resource-dir 会被自己清空）。详见 DEV_NOTES §4。
            }
        }
    }
}
