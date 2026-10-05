# ParseHub Studio · Compose/miuix 前端开发文档

> 本文档面向接手排查的开发者/模型。当前分支：`feat/miuix-ui`。
> 最后状态：**GitHub Actions 构建通过，产出免安装包 106 MB（含 Python sidecar）；MSI/EXE 打包在 CI 上因 WiX 报错被降级为 best-effort。**

---

## 1. 这是什么

在 `ParseHub-Studio` 仓库里**新增**的一套前端：Kotlin + Compose Multiplatform + [miuix](https://github.com/compose-miuix-ui/miuix)（小米 HyperOS 设计语言）桌面应用（Windows）。

**它复用现有 Python sidecar，完全不动后端。** 原有的 Tauri + HTML/JS 前端（`studio/`）保持原样，未做任何修改。

```
studio-compose/                 ← 新增的 Compose 前端（本分支的核心）
├── build.gradle.kts            ← Gradle 构建（compose 1.12.1 + miuix 0.9.4）
├── settings.gradle.kts
├── gradle/wrapper/             ← Gradle 9.8 wrapper
├── src/main/resources/icon.ico
└── src/main/kotlin/io/github/obihs2501/parsehubstudio/
    ├── Main.kt                 ← 入口：启动 sidecar、开窗口、应用 Mica
    ├── AppState.kt             ← 应用状态（导航、任务、历史、设置）
    ├── Screens.kt              ← 加载页 / 启动失败页
    ├── model/Models.kt         ← kotlinx.serialization 数据模型（对齐 API payload）
    ├── sidecar/
    │   ├── SidecarProcess.kt   ← 启动/管理 Python sidecar（stdio ready 协议）
    │   ├── ApiClient.kt        ← Ktor 客户端，封装 28 个 /api 路由
    │   └── WindowsMica.kt      ← Win11 Mica 材质（JNA 调 DWM，同 PixEz-MIUIX 方案）
    └── ui/
        ├── AppRoot.kt          ← 外壳：侧栏 + 内容区 + Toast
        ├── Components.kt       ← miuix 风格通用组件（PageHero/SectionCard/PrefRow…）
        └── screens/            ← 6 个页面
            ├── ParseScreen.kt
            ├── TasksScreen.kt
            ├── HistoryScreen.kt
            ├── CookiesScreen.kt  （含 BookmarksScreen）
            └── SettingsScreen.kt
```

---

## 2. 架构：前后端如何衔接

前端**不 import 任何 Python**，全部走 HTTP。这与 Tauri 版完全一致，只是把「Rust 启动 sidecar + WebView 调 API」换成「Kotlin 启动 sidecar + Ktor 调 API」。

```
Compose App (JVM)
   │  ProcessBuilder 启动
   ▼
parsehub-sidecar.exe  ← 与 Tauri 版用的是同一个可执行文件
   │  stdout 打印 {"event":"ready","port":N}
   │  环境变量 PARSEHUB_SESSION_TOKEN
   ▼
http://127.0.0.1:N/api/...  （Bearer token 鉴权）
```

### sidecar 协议（关键，勿改）

来自 `studio/src/parsehub_studio/sidecar.py`：

1. 必须由父进程设置环境变量 `PARSEHUB_SESSION_TOKEN`，否则 sidecar 拒绝启动。
2. 就绪后 stdout 打印一行 JSON：`{"event":"ready","port":<N>}`。
3. 所有请求带 `Authorization: Bearer <token>`（或同值 cookie）。
4. 关闭 sidecar 的 stdin 即通知它退出。

`SidecarProcess.kt` 的 `locate()` 按以下顺序找可执行文件：

1. classpath 同目录的 `resources/parsehub-sidecar.exe`（jpackage 打包后落点）
2. classpath 同目录 `parsehub-sidecar.exe`
3. classpath 父目录及其 `resources/`
4. `jpackage.app-path` 所在目录
5. 开发目录：`./parsehub-sidecar.exe`、`./dist/`、`../studio/dist/`、`studio/dist/`

---

## 3. API 契约（28 个路由）

`ApiClient.kt` 已全部封装。对应 `studio/src/parsehub_studio/app.py`：

| 分类 | 路由 |
| --- | --- |
| 基础 | `GET /api/health`、`GET /api/platforms`、`POST /api/detect`、`POST /api/clean` |
| 解析 | `POST /api/parse` |
| 批次 | `POST /api/batches`、`GET /api/batches/{id}`、`POST /api/batches/{id}/cancel`、`POST /api/naming/preview` |
| 任务 | `GET /api/tasks`、`POST /api/tasks`、`GET /api/tasks/{id}`、`POST /api/tasks/{id}/cancel`、`POST /api/tasks/clear` |
| 配置 | `GET /api/config`、`POST /api/config/proxy`、`POST /api/config/proxy/clear`、`POST /api/config/cookie`、`POST /api/config/cookie/clear`、`POST /api/config/cookie-pool` |
| 设置 | `GET /api/settings`、`POST /api/settings` |
| 历史 | `GET /api/history`、`DELETE /api/history/{id}`、`POST /api/history/clear` |
| 文件系统 | `GET /api/fs/list`、`POST /api/reveal`、`POST /api/open-url` |
| 媒体代理 | `GET /api/media?src=...`（`ApiClient.mediaUrl()`） |

数据模型见 `model/Models.kt`，字段名用 `@SerialName` 对齐后端 JSON（snake_case）。

---

## 4. 构建与运行

### 本地开发

```bash
# 1. 先构建 Python sidecar（放在 studio/dist/）
cd studio
uv sync --locked --group build
uv run python packaging/make_icon.py
uv run pyinstaller --noconfirm --clean packaging/parsehub_studio.spec

# 2. 运行 Compose 前端（会从 studio/dist 找到 sidecar）
cd ../studio-compose
./gradlew run            # 或 runDistributable
```

### 打包

```bash
./gradlew createDistributable   # 免安装目录（不需要 WiX，最可靠）
./gradlew packageMsi packageExe # 安装包（需要 WiX）
```

**`build` 目录可用环境变量覆盖**（用于规避 WiX 长路径问题）：

```bash
PARSEHUB_BUILD_DIR=C:/b ./gradlew packageMsi
```

### 依赖源（重要）

`build.gradle.kts` 里仓库顺序：

```kotlin
mavenCentral()                                                  // miuix 在这里
maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") } // 覆盖 google/androidx
maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
```

> ⚠️ `dl.google.com` 在国内不可达。腾讯聚合镜像**部分覆盖** google/androidx；它缺 miuix 的 jar，所以 `mavenCentral()` 必须放**最前**，否则 Gradle 会在腾讯仓库锁定 artifact 后 404 失败。

---

## 5. CI（`.github/workflows/studio-build.yml`）

- `push` 触发分支：`master`、`main`、`feat/**`、`fix/**`；paths 含 `studio-compose/**`。
- **特性分支只跑 `test` + `compose`**；`windows`（Tauri 版）只在 master/main 跑。
- `release` job 只在 master/main 且 `windows`+`compose` 都成功时发布。

### compose job 流程

1. 构建 Python sidecar（`studio/`，pyinstaller）
2. 把 sidecar 复制到 `studio-compose/resources/common/parsehub-sidecar.exe`
3. `compileKotlin`（编译检查）
4. `createDistributable`（**不依赖 WiX**）
5. 组装免安装包 `dist/ParseHubStudioCompose-portable/`
6. `packageMsi packageExe`（**best-effort**，失败仅告警不阻塞）
7. 收集产物 + SHA256 → 上传 artifact `ParseHubStudioCompose-windows-x64`

### 关键环境变量

| 变量 | 值 | 作用 |
| --- | --- | --- |
| `PARSEHUB_BUILD_DIR` | `C:\b` | 把 build 输出移到短路径 |
| `TEMP` / `TMP` | `C:\t` | jpackage 临时目录移到短路径 |

---

## 6. ⚠️ 未解决问题：CI 上 MSI/EXE 打包失败（WiX light.exe 311）

### 现象

- **本地**（Windows 11 + Temurin JDK 21.0.11）：`createDistributable`、`packageMsi`、`packageExe` **全部成功**，MSI ≈ 65.5 MB。
- **CI**（windows-latest + Temurin JDK 21.0.12）：`light.exe` 稳定返回 **exit code 311**，即使：
  - build 目录和 temp 目录都已移到短路径（`C:\b`、`C:\t`）
  - 移除了 36 MB 的 `material-icons-extended` 冗余依赖
  - 清 WiX 缓存重试一次
  - 排除 Windows Defender 扫描路径

### 错误原文（CI）

```
java.io.IOException: Command [light.exe, -nologo, -spdb, -ext, WixUtilExtension,
  -out, C:\b\compose\binaries\main\msi\ParseHubStudio-0.5.0.msi, ...]
  in C:\t\jdk.jpackage...\images\win-msi.image\ParseHubStudio exited with 311 code
```

### 已尝试且**无效**的方案

1. 短 build 路径（`C:\b`）
2. 短 temp 路径（`C:\t`）
3. 清 WiX 缓存重试
4. 移除冗余依赖减小体积
5. Windows Defender 排除

### 根因分析（未最终确认）

路径长度已排除（短路径仍失败）。**最可能是 CI runner 环境差异**：

- **JDK 版本差异**：CI 用 Temurin **21.0.12**，本地是 21.0.11。jpackage 的 WiX 集成在不同小版本可能有行为差异。
- **WiX 3.11 在 GitHub runner 上的已知问题**：`light.exe` 311 是 WiX 的 IO 错误码，GitHub runner 上偶发（实时扫描/文件锁/权限）。
- **cross-drive 操作**：`light.exe` 从 `C:\t` 读取、写 `C:\b`，本地也这样但成功。

### 建议的后续排查方向

1. **锁定 JDK 版本**：CI 改用 `java-version: "21.0.11"`（或 `temurin` 指定精确 build），验证是否版本相关。
2. **改用 WiX 4 / 换打包工具**：Compose 1.12 支持 `TargetFormat.Msi` 走 WiX 3；可考虑改用 **JPackage 的 app-image + 第三方安装器**，或直接只发免安装 zip。
3. **试 `--win-console`/`--win-menu` 精简**，或减少 `appResourcesRootDir` 里的大文件（42 MB sidecar 进 cabinet）。
4. **在 CI 上跑一次 `light.exe` 的详细日志**（`-v` 或在 `packageMsi.args.txt` 里加 verbose）。
5. **考虑不用 MSI**：免安装 zip（已稳定产出）对多数用户足够；MSI 仅作为「有则更好」。

### 当前交付状态

**免安装包（`ParseHubStudioCompose-windows-x64-portable.zip`，106 MB）是可靠产出**，包含：
- `ParseHubStudio.exe`（jpackage 启动器）
- `app/`（jar + skiko + sidecar）
- `runtime/`（JBR）
- `app/resources/parsehub-sidecar.exe`（Python 后端）

---

## 7. 与 Tauri 版的关系

| | Tauri 版（`studio/`） | Compose 版（`studio-compose/`） |
| --- | --- | --- |
| 前端 | HTML/CSS/JS | Kotlin + Compose + miuix |
| 外壳 | Rust (Tauri) | Kotlin/JVM (Compose Desktop) |
| 后端 | Python sidecar | **同一个** Python sidecar |
| 打包 | NSIS | jpackage (MSI/EXE) + 免安装 |
| 体积 | 42.7 MB | ~106 MB（含 JBR） |
| 触发 | master/main | feat/** 分支 |

两者**互不影响**，可长期共存。

---

## 8. 未完成 / 待办

- [ ] 解决 CI 上 WiX `light.exe` 311（见第 6 节）
- [ ] 图片加载：当前 `Media` 模型与 `mediaUrl()` 已就绪，但 UI 尚未渲染缩略图（`ParseScreen` 里是占位图标）
- [ ] 灯箱/大图预览（Tauri 版有，Compose 版未实现）
- [ ] 批次解析 UI（API 已封装 `createBatch`/`getBatch`，UI 未接）
- [ ] Cookie 池「添加账户」交互（`AppState.addCookie` 已就绪，UI 未接输入框）
- [ ] 设置项编辑（当前只读展示，`saveSettings` 已接开关类）
- [ ] 主题切换（当前固定深色 `darkColorScheme()`）
- [ ] 单实例 / 托盘（Tauri 版有）
- [ ] 错误重试、进度轮询的节流

---

## 9. 设计规范

`studio/docs/ui-guidelines.md` 是 miuix 设计规范（组件、squircle、毛玻璃、宽屏适配等），Compose 版应遵守。

---

## 10. 参考

- miuix: https://github.com/compose-miuix-ui/miuix （Apache-2.0）
- 参考应用 PixEz-MIUIX: https://github.com/137458/pixez-miuix （GPL-3.0，**只参考设计，未复制代码**）
- Windows Mica 方案参考其 `WindowsMica.kt`（本项目的实现是独立重写的 JNA 调用）
