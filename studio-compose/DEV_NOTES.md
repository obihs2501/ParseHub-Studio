# ParseHub Studio · Compose/miuix 前端开发文档

> 本文档面向接手排查的开发者/模型。当前分支：`feat/miuix-ui`。
> 最后状态：**UI 全量对齐 miuix 规范（排版/配色），图片/灯箱/批次/Cookie/设置编辑/主题切换全部接通；开发模式不再需要先打包 sidecar。**
> CI 侧：GitHub Actions 构建通过，产出免安装包（含 Python sidecar）；MSI/EXE 的 WiX `light.exe` 311 已定位并修复（元数据必须纯 ASCII，见第 6 节）。

---

## 1. 这是什么

在 `ParseHub-Studio` 仓库里**新增**的一套前端：Kotlin + Compose Multiplatform + [miuix](https://github.com/compose-miuix-ui/miuix)（小米 HyperOS 设计语言）桌面应用（Windows）。

**它复用现有 Python sidecar，完全不动后端。** 原有的 Tauri + HTML/JS 前端（`studio/`）保持原样，未做任何修改。

```
studio-compose/                 ← 新增的 Compose 前端（本分支的核心）
├── build.gradle.kts            ← Gradle 构建（compose 1.12.1 + miuix 0.9.4 + coil 3.6.3 + ktor 3.3.1）
├── settings.gradle.kts
├── gradle/wrapper/             ← Gradle 9.8 wrapper
├── src/main/resources/icon.ico
└── src/main/kotlin/io/github/obihs2501/parsehubstudio/
    ├── Main.kt                 ← 入口：启动 sidecar、开窗口、Mica、主题
    ├── AppState.kt             ← 应用状态（导航/任务/历史/批次/灯箱/对话框/轮询）
    ├── Screens.kt              ← 加载页 / 启动失败页
    ├── model/Models.kt         ← kotlinx.serialization 数据模型（对齐 API payload）
    ├── library/
    │   └── LocalLibrary.kt     ← 本地图库数据源：扫描下载目录 + ffmpeg 视频首帧缩略图
    ├── sidecar/
    │   ├── SidecarProcess.kt   ← 启动/管理 Python sidecar（exe 优先，回退 .venv python）
    │   ├── ApiClient.kt        ← Ktor 客户端，封装 28 个 /api 路由
    │   ├── WebAppWindow.kt     ← 用系统 Edge/Chrome 的 --app 模式开独立窗口（不内置浏览器）
    │   └── WindowsMica.kt      ← Win11 Mica 材质（JNA 调 DWM）
    └── ui/
        ├── AppRoot.kt          ← 外壳：侧栏 + 内容区 + Toast + 灯箱 + 对话框
        ├── Components.kt       ← miuix 风格通用组件（PageHero/SectionTitle/PrefRow/GroupHeader/MediaGrid…）
        ├── MediaImages.kt      ← Coil 加载器（带 sidecar 鉴权）+ RemoteThumb
        ├── Dialogs.kt          ← 自绘对话框（添加账户/文本/目录选择/选项/数字/收藏/别名/确认）
        ├── Lightbox.kt         ← 全屏媒体查看器（缩放/平移/旋转/切图/保存）
        └── screens/            ← 7 个页面
            ├── ParseScreen.kt     （解析工作台：输入 + 批次面板 + 结果 + 媒体网格）
            ├── TasksScreen.kt     （下载管理：进行中/已结束分区）
            ├── GalleryScreen.kt   （本地图库：文件夹 + 网格 + 筛选/排序/列数）
            ├── HistoryScreen.kt   （解析记录：两级折叠 + CSV 导出）
            ├── CookiesScreen.kt   （账户与代理 + BookmarksScreen）
            └── SettingsScreen.kt  （设置：全部可编辑）
```

---

## 2. 架构：前后端如何衔接

前端**不 import 任何 Python**，全部走 HTTP。与 Tauri 版完全一致，只是把「Rust 启动 sidecar + WebView 调 API」换成「Kotlin 启动 sidecar + Ktor 调 API」。

```
Compose App (JVM)
   │  ProcessBuilder 启动
   ▼
parsehub-sidecar(.exe)  ← 与 Tauri 版用的是同一个可执行文件
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

### sidecar 定位顺序（`SidecarProcess.locate()`）

1. classpath 同目录的 `resources/parsehub-sidecar.exe`（jpackage 打包后落点）
2. classpath 同目录 `parsehub-sidecar.exe`
3. classpath 父目录及其 `resources/`
4. `jpackage.app-path` 所在目录
5. 开发目录：`./parsehub-sidecar.exe`、`./dist/`、`../studio/dist/`、`studio/dist/`
6. **开发回退（新）**：`../studio/.venv`、`studio/.venv`、`.venv` 中的 python + `<studio>/src/parsehub_studio/sidecar.py` 存在时，直接 `python -m parsehub_studio.sidecar` 运行源码（venv 里是 editable 安装）。
   也可用 `PARSEHUB_SIDECAR_PYTHON` / `PARSEHUB_SIDECAR_CWD` / `PARSEHUB_SIDECAR_SRC` 显式指定。

> 这条回退让 `./gradlew run` 无需先 PyInstaller 打包，改 Python 代码即时生效。

### sidecar 的 stderr（诊断用）

`SidecarProcess` 起一个常驻线程读 stderr：写入 `%LOCALAPPDATA%/ParseHubStudio/sidecar.log`，并在内存里留最后 40 行。启动失败时错误信息带「退出码 + 这段输出 + 日志路径」。

早先 stderr 管道没人读（`redirectErrorStream(false)` 之后一直悬着）：子进程写满约 64KB 就永久阻塞，表现和「启动超时」一模一样；而且崩溃的真实原因（如 `ImportError: DLL load failed while importing _ctypes`）只剩一句「Python 服务启动失败或超时」，只能手动跑 exe 才能查出来。

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
| 媒体代理 | `GET /api/media?src=...&referer=...`（`ApiClient.mediaUrl()`） |

数据模型见 `model/Models.kt`。注意：`/api/platforms` 的字段是 **`supported_types`**（复数），不是 `supported_type`。

### 四个易踩的坑

- **`published_at` 是 `int | str`**：抖音给的是 `create_time`（epoch 秒，JSON 里是数字），微博给 ISO 日期串。Compose 模型必须用 `StringOrNumberSerializer` 兼容两种 —— 写死 String 时抖音解析结果直接抛 `Expected quotation mark '', but had '1' instead at path: $.published_at`，解析工作台整页不可用（2026-10-05 用户报障）。`/api/parse`、`/api/batches`、`/api/tasks` 三处的 payload 都带这个字段。回归测试：`ModelDecodeTest`。
- **Cookie 池不支持删除条目**：`/api/config/cookie-pool` 只支持「添加」和「启用/停用」（`entry_id` + `enabled`），所以 UI 只给开关，没有删除按钮。
- **历史里的媒体直链会过期**：历史保存的是解析当时的 `media[].url`。UI 里「下载」「保存到下载目录」都把原帖链接交给后端重新解析（`createTask` 内部会重新 parse），所以过期不影响下载；只有「大图」可能加载失败，此时用「更新信息」重新解析刷新记录。
- **metadata.json 是写死的固定文件名**：`src/parsehub/types/result.py` 往作品输出目录写死 `metadata.json`；归档时（`studio/src/parsehub_studio/archive.py`）遇到同名文件会改名为 `metadata_2.json`。若文件夹模板不含 `{id}`（例如 `{platform}_{author}`），同一作者的不同作品会进同一目录，于是出现 `metadata_2.json` —— **不会覆盖，但难以对应到具体作品**。Compose 版在文件夹模板对话框里对这种模板给出警告并提供「追加 {id}」一键修复；根治要改库（如 `metadata_{id}.json`），会影响 CLI 与 Tauri 版，本分支未动。

---

## 4. 构建与运行

### 本地开发（不需要先打包 sidecar）

```bash
# 依赖：studio/.venv 已 uv sync 过（parsehub_studio 是 editable 安装）
cd studio-compose
./gradlew run            # 会自动用 studio/.venv 的 python 起 sidecar
```

需要打包版 sidecar 时：

```bash
cd studio
uv sync --locked --group build
uv run python packaging/make_icon.py
uv run pyinstaller --noconfirm --clean packaging/parsehub_studio.spec
```

### 打包 sidecar 的 DLL 固定（2026-10-05 事故）

conda 把运行时 DLL 放在 `<prefix>/Library/bin`，PyInstaller 不会自动去那里找：不显式固定时 `bindepend` 按 PATH 抓到 `C:\Program Files\Git\mingw64\bin` 的 OpenSSL 和 MuMu 模拟器的 liblzma/libexpat，而 `_ctypes` 需要的 `ffi.dll` 因为找不到被**静默丢弃** —— 产物一启动就 `ImportError: DLL load failed while importing _ctypes`（本机 PATH 上恰好有 Git 的 ffi.dll，本机测试「假绿」，换台机器必挂）。

- `packaging/parsehub_studio.spec`：按「`<prefix>\Library\bin`（conda）→ 解释器根 → `DLLs`」的顺序把 7 组运行时 DLL（ffi / libssl / libcrypto / sqlite3 / liblzma / libbz2 / libexpat）显式固定，并剔除 `ucrtbase.dll`（系统级 CRT，不随包分发）—— conda 与 python.org 两种布局都生效，不再是 conda 专用；
- `packaging/check_bundle.py`：新增「扩展模块 ↔ 依赖 DLL 齐全性」校验与外来工具链来源拦截（git\mingw / msys / MuMu），构建期硬失败 —— 早先它只查「有没有混进禁止包」，拦不住这类问题。判定规则是「环境里有这个 DLL、包里却没有」才报错，所以静态链接的布局（python.org 的 _lzma/_bz2/pyexpat）不会误报，这三项降级为 `::warning::`；禁止包检查继续读 Analysis-00.toc（纯 Python 模块只在那里），DLL/来源检查读最终产物 EXE-00.toc；
- 本地打包用 uv 环境（`uv sync --locked --group build` 后 `uv run pyinstaller …`），**别用 anaconda 里的 PyInstaller**（本机打包曾绕开 `uv.lock`，打出的包才会带上这些外来 DLL）；
- 冒烟测试：`uv run python tests/sidecar_check.py --executable dist/parsehub-sidecar.exe`（真起 exe 打 API）。

### 打包

```bash
./gradlew createDistributable   # 免安装目录（不需要 WiX，最可靠）
./gradlew packageMsi packageExe # 安装包（需要 WiX）
```

- **只发独立安装包，不发便携版**（用户要求）：CI 的 compose 任务只把 `*.msi` / `*.exe` 收进 `release/`。
- **覆盖升级**：`upgradeUuid` 固定为 `8c1f2a44-9d3b-4e77-8a11-6f0e2b9c4d31`，`packageVersion` 每次发版都要往上抬（0.6.0 → 0.6.1 → 0.6.2 → 0.6.3，**版本号不变时 MSI 拒绝覆盖安装**，用户得先卸载）；同一系列可直接覆盖安装；装过旧 Tauri 版的建议先卸载一次（两套安装器身份不同）。CI 的 release tag 也要跟着改（`compose-v0.6.3-<run>`）。
- **自动发布**：推送 `feat/**` 后由 `release-compose` 任务发布到 GitHub Release（tag `compose-v0.6.2-<run>`，预发布）。

**`build` 目录可用环境变量覆盖**（用于规避 WiX 长路径问题）：

```bash
PARSEHUB_BUILD_DIR=C:/b ./gradlew packageMsi
```

### 依赖源（重要）

`build.gradle.kts` 里仓库顺序：

```kotlin
mavenCentral()                                                  // miuix / coil 在这里
maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") } // 覆盖 google/androidx
maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
```

> ⚠️ `dl.google.com` 在国内不可达。腾讯聚合镜像**部分覆盖** google/androidx；它缺 miuix 的 jar，所以 `mavenCentral()` 必须放**最前**，否则 Gradle 会在腾讯仓库锁定 artifact 后 404 失败。

> ⚠️ ktor 必须是 **3.3.1**：`coil-network-ktor3:3.6.3` 依赖 ktor-client-core ≥ 3.1.0，四个 ktor artifact 版本要一起改。

---

## 5. CI（`.github/workflows/studio-build.yml`）

- `push` 触发分支：`master`、`main`、`feat/**`、`fix/**`；paths 含 `studio-compose/**`。
- **特性分支只跑 `test` + `compose`**；`windows`（Tauri 版）只在 master/main 跑。
- `release` job 只在 master/main 且 `windows`+`compose` 都成功时发布。

### compose job 流程

1. 构建 Python sidecar（`studio/`，pyinstaller）
2. 把 sidecar 复制到 `studio-compose/resources/common/parsehub-sidecar.exe`
3. `compileKotlin`（编译检查）
4. `test`（Kotlin 单测，含 `published_at` 解码回归 `ModelDecodeTest`）
5. `createDistributable`（**不依赖 WiX**）
6. 组装免安装包 `dist/ParseHubStudioCompose-portable/`
7. `packageMsi packageExe`（**best-effort**，失败仅告警不阻塞）
8. 收集产物 + SHA256 → 上传 artifact `ParseHubStudioCompose-windows-x64`

### 关键环境变量

| 变量 | 值 | 作用 |
| --- | --- | --- |
| `PARSEHUB_BUILD_DIR` | `C:\b` | 把 build 输出移到短路径 |
| `TEMP` / `TMP` | `C:\t` | jpackage 临时目录移到短路径 |

---

## 6. ✅ 已解决（2026-10-06）：CI 上 MSI/EXE 打包失败（WiX light.exe 311 = 代码页 1252 装不下中文）

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

### 根因（2026-10-06 确认）

**不是 JDK、不是 WiX 版本、不是缓存 —— 是 MSI 数据库代码页装不下中文。**

- `build.gradle.kts` 里 `description = "ParseHub 可视化桌面应用：…"`，jpackage 把它写进 MSI 的
  **Summary Information（Subject）**；
- WiX 3 的 `light.exe` 按构建机 locale 选数据库代码页：CI 是 en-US → **1252**，中文直接
  `LGHT0311: A string was provided with characters that are not available in the specified
  database code page '1252'`，jpackage 只把它转成「exit code 311」；
- 本机 zh-CN → 936 能过，但**也是坏的**：本地 MSI 实测 `SummaryInfo Codepage = 1252`、
  `Subject = "ParseHub     :     "`（中文被替换成空格）。

修复：`description` 改成纯 ASCII（中文说明留在 README 与界面里）。**MSI/EXE 的元数据必须保持 ASCII。**

### CI 上的诊断（2026-10-06 加）

- 打包步骤加 `-Pcompose.desktop.verbose=true`（日志里能看到完整 jpackage 命令行）；
- `TEMP/TMP=C:\t`，jpackage 的临时目录（含生成的 `main.wxs` / `*.wxl`）落在 `C:\t\jdk.jpackage*`；
- 新增 always 运行的诊断步骤：打印 runner locale / ANSI 代码页、`where candle/light`、
  扫描 WiX 源文件里的非 ASCII 字符串，并把 jpackage 临时目录 + `C:\b\compose\logs` 收进
  `compose-build-diagnostics` artifact（早先那步上传的是 `studio-compose/build/reports/**`，
  而 CI 上 build 目录是 `C:\b`，永远是空的）。

### 本地复现（不用等 CI）

CI 与本机的差别只在 locale：本机 zh-CN → 936 能过，en-US → 1252 才报 311。把 jpackage 的
默认 locale 改成 en-US 并配 `--temp` 保留 WiX 源文件，就能在本机复现（2026-10-06 实测）：

```powershell
$env:JAVA_TOOL_OPTIONS = "-Duser.language=en -Duser.country=US"   # 模拟 CI 的 en-US
$env:PATH = "C:\b\wix311;$env:PATH"                               # Compose 插件下载的 WiX 3.11
& "C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin\jpackage.exe" `
  --type msi --app-image C:\b\compose\binaries\main\app\ParseHubStudio `
  --name ParseHubStudio --app-version 0.6.0 `
  --description "ParseHub 可视化桌面应用：…" `
  --vendor obihs2501 --win-menu --win-shortcut --win-dir-chooser --win-per-user-install `
  --win-upgrade-uuid 8c1f2a44-9d3b-4e77-8a11-6f0e2b9c4d31 `
  --dest C:\t\jp-out --temp C:\t\jp-temp --verbose
```

- 中文描述 + `-cultures:en-us` → `main.wxs(36)`、`main.wxs(82) : error LGHT0311 … code page '1252'`，
  `light.exe` 退出 311（与 CI 日志完全一致）；
- ASCII 描述 + 同样 en-us → 构建成功；
- `--temp` 指定的目录 jpackage 不会删，`.wxs` 留在 `C:\t\jp-temp\config\`，可以直接 grep 非 ASCII。

### 仍然可选的长期方案

- 升级 jpackage / WiX 4+，或换 Inno Setup（app-image → setup.exe）；
- 想保留中文 MSI 元数据：需要自定义 `--resource-dir` 让 MSI 走 zh-CN（代码页 936），
  本仓库未采用（安装器界面会变中文单语，且 Compose DSL 不透传该参数）。

---

## 7. 设计规范落地（miuix）

`studio/docs/ui-guidelines.md` 是规范原文。Compose 版的实际落地要点：

### 7.1 排版（必须用 `MiuixTheme.textStyles`）

miuix 主题**不提供** `LocalTextStyle`，`Text()` 不写样式就会掉到 Compose 默认值 —— 所以每个 `Text` 都要显式带样式。

| 用途 | 样式 | 字号 |
| --- | --- | --- |
| 页面大标题（PageHero） | `title1`，**不加粗** | 32 |
| 卡片标题 / 偏好行标题 | `headline1` + `FontWeight.Medium` | 17 |
| 输入框 / 正文 | `main` / `body1` | 17 / 16 |
| 次级说明（偏好行 summary） | `body2` | 14 |
| 分组小标题（SmallTitle） | `subtitle`（14 Bold，inside margin 28/8） | 14 |
| 辅助信息（时间/计数/元数据） | `footnote1` | 13 |
| 标签 / 徽章 / 状态字 | `footnote2` | 11 |
| 按钮文字 | `button` | 17 |
| 对话框标题 | `title4` + Medium | 18 |

> 不要再用 15sp / 12sp 这类不在字阶里的值；以前用 `FontWeight.Bold` 打大标题是错的（miuix 大标题不加粗，那是「看起来和 PixEz-MIUIX 差很多」的主因）。

### 7.2 颜色角色

| 用途 | 角色 |
| --- | --- |
| 标题 / 主要文字 | `onBackground` |
| 次级说明 | `onSurfaceVariantSummary`（浅色 60% 黑 / 深色 80% 白） |
| 占位符 / 最弱提示 | `onSurfaceVariantActions`（40%） |
| 卡片 | `surfaceContainer` / `onSurfaceContainer` |
| 选中态 | `tertiaryContainer` / `onTertiaryContainer` |
| 分隔线 | `dividerLine` |
| 输入框底色 | `secondaryVariant` |

> ⚠️ `onBackgroundVariant`（浅色 `#8C93B0`）在白底上对比度只有 ~3.3:1，**不要**用它做正文/说明文字，那是「浅色模式字体不清晰」的主因。

### 7.3 形状

自定义形状一律 `top.yukonga.miuix.kmp.squircle.*`：纯色背景用 `squircleBackground(color, radius)`，图片/裁剪用 `squircleClip(radius)`，可点击元素用 `squircleSurface(color, radius) + clickable{}`。3dp 小徽章仍用 `clip(RoundedCornerShape(3.dp))`。

### 7.4 字体

**已内置字体**（`src/main/resources/fonts/`，官方 MiSans.zip 的 `MiSans/ttf/` 三个字重，共约 23 MB）：

- `MiSans-Regular.ttf` → Normal(400)、`MiSans-Medium.ttf` → Medium(500)、`MiSans-Demibold.ttf` → Bold(700)。
- 优先级：**本机安装的 PingFang SC**（`%LOCALAPPDATA%\Microsoft\Windows\Fonts\PingFangSC-{Regular,Medium}.otf` 或系统字体目录，Apple 字体不随包分发）→ **内置 MiSans** → 系统默认。
- 生效方式：`ui/AppFont.kt` 的 `overrideResolver()` 包装 `FontFamily.Resolver`，把 `FontFamily.Default` 重定向到该字体族，在 `Main.kt` 里通过 `LocalFontFamilyResolver` 注入 —— **一处生效**：miuix 的 `textStyles` 与所有直接写 `fontSize` 的 `Text` 都会用上，不必在几十个调用点各加一次 `fontFamily`。
- Compose 的桌面 `Font(File)` 只吃文件（`androidx.compose.ui.text.platform.Font`），所以内置 ttf 首次使用时会落到 `%TEMP%/parsehub-fonts/`。
- 回归测试：`FontLoadTest`（Skia 能解析 + 族名为 MiSans）。

### 7.6 交互约定（按使用反馈调整）

- **解析工作台**：解析选项收进可折叠分组（默认收起），一级界面只留输入框 + 结果 + 批次面板。
- **抖音画质可选（0.6.1）**：解析结果里给出全部码流档位（`media[].variants`：分辨率 / 体积 / 码率 / 编码，按清晰度从高到低），结果卡片上用芯片选（`vN` 下标），下载时 `quality` 传 `"vN"`，后端 `_apply_quality` 换成对应档。取流策略也改了：**不再按接口类型区分来源、也不再过滤 bytevc1/H265**（4K 原档本身就是 H265），统一按「分辨率面积 → 体积 → 码率」取最高档 —— 移动端接口的顶层 `play_addr` 常常只是低清预览（实测 576x1024 5.4MB，而 `bit_rate[]` 里有 720x1280 的档），以前会优先它。**4K 原画不再依赖 cookie（0.6.2）**：只有网页接口返回高清档（2160x3840 / 14.4MB / H265），而网页接口除了 cookie 还要过 `a_bogus` + `x-secsdk-web-signature` 签名 —— 实测 CDN 直链本身不校验 cookie，卡点只在签名，所以让真实浏览器执行页面 JS 自己签最省事：系统 Edge/Chrome 跑 `--headless=new --dump-dom "https://www.douyin.com/discover?modal_id=<aweme_id>"`（临时 profile、无 cookie、无登录，实测退出码 0、HTML 约 1.38 MB、8~14 秒），从页面 SSR HTML 里抠 URL 编码的档位 JSON 补进 `bit_rate[]`（`provider_api/douyin_browser.py`；必须用 `/discover?modal_id=`，`/video/{id}` 的 dump 里一个档位都没有，viewport 2560x1440 才触发高画质档）。只在「是视频且当前最高档短边 < 1440」时才触发，移动端已有的档位按「(宽,高) 或 URL」去重不重复添加；**任何失败都静默跳过，绝不影响解析**（`PARSEHUB_DOUYIN_BROWSER=0` 可关闭，设为 exe 路径可覆盖，超时 45 秒、单次 dump 不重试（实测 `--virtual-time-budget` 重试更慢且档位结果相同；`--blink-settings=imagesEnabled=false` 屏蔽图片后 12.8s → 9.0s）。抓取结果按 aweme_id 缓存 10 分钟：下载任务会重新解析一遍，命中缓存保证画质芯片下标不错位、批量下载不重复起浏览器；同一进程最多同时跑 2 个无头浏览器，取消/超时残留的临时 profile 会被下次抓取清掉。没装浏览器或抓取失败时退回低清档，此时配置 cookie 仍是备选，UI 提示已相应改写。档位芯片可能有十几个，芯片行改为可横向滚动（早先会溢出窗口）。**客户端 HTTP 超时必须大于解析时长（0.6.3）**：0.6.2 发布后用户报「解析很久然后失败 Request timeout has expired」—— `ApiClient` 的 Ktor 客户端没装 `HttpTimeout`，CIO 引擎默认只有 15 秒，而补高清档的解析要十几秒到二十几秒；现在显式设为 180 秒（request/socket）、连接超时 15 秒。
- **可折叠分组头**：统一带底色（`secondaryVariant` + `squircleSurface` 12dp）+ `primary` 色箭头，和普通标题行明显区分；展开/收起状态看箭头方向与下方内容。
- **本地图库**：支持**多个根目录** —— 默认下载目录 + 用户添加的文件夹（持久化在 `%LOCALAPPDATA%/ParseHubStudio/gallery-roots.json`，不走后端设置接口，避免污染 settings.json）；左侧按根目录分组（可折叠、额外目录可移除），右侧内容区（面包屑 + 上一级 / 重新扫描 / 打开目录）；工具栏两行：筛选（全部 / 图片 / 视频）+ 文件名搜索 + 「显示设置」（展开后是排序、升降序、列数 2–10、裁剪 / 完整、视频首帧）；网格分页 120，子文件夹以卡片展示。视频首帧用本机 ffmpeg 抽帧缓存到 `%LOCALAPPDATA%/ParseHubStudio/videothumbs`。新下载仍然只写默认下载目录。
  - **栏宽可拖动**：左侧导航栏与图库文件夹栏都有 10dp 拖动把手（悬停变手型 + 高亮），宽度持久化在 `%LOCALAPPDATA%/ParseHubStudio/ui-prefs.json`（拖动过程不写盘，松手才存）。
  - **移除文件夹**：入口收进分组标题行右侧的小叉（无底色，悬停变红），点击后**二次确认**（只从列表移除，不删磁盘文件）——早先是一行「移除此文件夹」按钮，容易误触且深浅色下都不好看。
  - **进文件夹预取前 4 张 2048 预览图**：第一次点开图片不用等解码。
  - **未打开文件夹时**：两列文件夹卡片（名称可换行）铺满窗口，按「名称 / 时间 / 数量」排序、可升降序；打开文件夹后回到按列数（2–10）可调的媒体网格。**排序行常显**，列数 / 裁剪填充 / 视频首帧仍收在「显示设置」里。
  - **瓦片与滚动（对齐 x-spider 的画廊）**：方形瓦片、**文件名只在悬停时浮在底部**（平时不占高度，一屏多放一行）、视频角标；**滚到接近底部自动加载下一页**（`derivedStateOf` 监听列表末尾，去掉「加载更多」按钮）。
  - **不再每次重扫**：根目录用「mtime 签名」判断是否需要重扫，启动时先用持久化快照（`gallery-cache.json`）铺界面 —— 早先每次进图库都要把所有根目录重扫一遍，大目录要卡好几秒。
  - **视频首帧可取消**：卡片进视口才排队生成，滚出视口协程被取消时立刻 `destroy()` 掉 ffmpeg（参考 x-spider 的 IntersectionObserver 做法），不再让它在后台白跑完。
- **灯箱（统一查看器）**：远程媒体（解析结果 / 解析记录）与本地文件（图库）共用一套查看器，`ViewerItem` 区分来源。**两级解码**：2048 预览层先出画面，后台再解原尺寸叠上去（宽高比一致、切换无跳动），所以既没有打开延迟，放大后也是原图像素。顶栏显示 `序号 · 分辨率 · 大小/时间`；`1:1` 与百分比按**原图像素**换算（本地分辨率读文件头即得，不用等解码）；缩放上限统一为「显示比例 400%」（早先是固定 8× 乘数，每张图上限各不相同，出现 169% / 324% 那种怪值）。滚轮缩放 / 拖动平移（**限制在图片边界内**，拖不丢）/ 双击复位 / ← → 切换 / Esc 关闭，预取相邻图片（2048）。**拖动与双击在同一个 `awaitEachGesture` 循环里判定**：早先是两个独立 `pointerInput` 各挂一个检测器，单击判定结束后的 doubleTapTimeout（~300ms）内再按下会被 tap 检测器当成「第二击」立即触发双击缩放并吃掉事件，并行的拖拽检测器看到事件被消费就取消 —— 连续平移时频繁「拖不动、图反而自己缩放」。另外尺寸未知（本地文件还没读到文件头 / 预览层还没解码完）时 `clampOffset` **不做限制**（早先恒返回 `Offset(0,0)`，打开后头一两秒完全拖不动），参考尺寸变化后重新夹取边界。**本地视频只用 VLC 播放**（vlcj + `EmbeddedMediaPlayerComponent` 嵌进 Compose `SwingPanel`）—— 编解码覆盖最全（HEVC / .ts / MKV / AV1 都能放），需要本机安装 VLC（不随包分发）。**本机已装 VLC 3.0.24 并验证**（`VlcProbeTest`：libvlc 用 D3D11VA 硬解播放测试 mp4；`NativeDiscovery` 自动找安装位置，无需配置）。**内置 JavaFX 播放器已按反馈移除**（只能放 H.264/AAC，却白占约 35 MB native）；没装 VLC 时查看器给出「下载 VLC / 用系统播放器打开」。**打开查看器是同步构造、立即显示**：分辨率与视频首帧改成按当前项懒加载 —— 早先点开一个几百张的文件夹要等好几秒（逐个读文件头 + 抽首帧）。远程视频只显示首帧（需先保存到下载目录）。底部缩略图条可一键隐藏（「隐藏缩略图 / 显示缩略图」，持久化在 `ui-prefs.json`）。
- **命名模板重名提醒**：文件夹模板不含 `{id}` 时给出警告（同一作者的不同作品会进同一目录，重名文件会被归档逻辑改名 `xxx_2`；开启元数据时 `metadata.json` → `metadata_2.json`），并提供「追加 {id} 到末尾」一键修复。
- **解析记录**（原「历史记录」）：解析过的作品（含未下载的），两级折叠（平台 → 作者）+ 紧凑操作行（大图 / 看视频 · 更新信息 · 原帖 · 删除）；顶部「导出 CSV」把全部解析记录写到 `<下载目录>/parsehub-history.csv`（UTF-8 BOM，Excel 直接打开），每次解析成功也会自动追加一行 —— 比会被改名的 `metadata.json` 可靠。
- **账户与代理**：按平台折叠（默认收起），折叠行显示平台名（headline1 17 Medium）+ 账户数 + 配置摘要，顶部有「全部展开 / 全部收起」，避免为了找平台一直下滑。
- **命名模板**（文件夹 / 文件名）：对话框里把 `/api/naming/preview` 返回的 `tokens` 渲染成可点元素（带中文说明），点击插入到模板末尾；`{index}` 自动插入为 `{index:03d}`；上方实时预览渲染结果。
- **作者别名**：平台改成点选（chip 列表，来自 `/api/platforms`），不再手输平台 ID；「本地已有作者 / 本地已有文件夹」候选改成**内联下拉**（点一下展开、整行显示完整名称、可滚动、选中自动收起）——早先的胶囊排布会把长名字截断、末尾整块被切掉。
- **目录选择器**：顶部提供「此电脑 / 用户目录 / 下载 / 桌面 / 图片」快捷位置；空目录显示提示；目标目录不存在时回退到「此电脑」并提示（例如默认下载目录还没创建过）。
- **缩略图**：进入历史 / 下载页时对封面做 Coil 预取（`prefetch()`），减少滚动时的空白等待。

---

- 所有远程图片走 `ApiClient.mediaUrl()` → `GET /api/media?src=...&referer=...`（sidecar 代理，绕开防盗链 / CORS）。
- `MediaImages.build(token)` 单独建一个 Ktor(CIO) 客户端并带 `Authorization: Bearer`：`/api/media` 同样受 security 中间件保护。
- **排查图片问题**：`PARSEHUB_IMAGE_DEBUG=1 ./gradlew run`，Coil 的 `DebugLogger` 会打印每条请求的 URL 与结果（默认只报 WARN/ERROR）。
- 刚进入页面时缩略图可能短暂显示占位图标：重组会让 Coil 取消并重发请求（日志里 `Cancelled` → `Successful`），属正常现象。
- **页面滚动**：`AppRoot` 用 `key(screen)` 让每个页面各自持有滚动位置，切页从顶部开始（否则会继承上一页的滚动位置）。
- **本地文件必须用 `java.io.File` 作为 Coil model**：不要用 `File.toURI()` —— 它生成 `file:/C:/…`（单斜杠），Coil 的 Uri 解析会把盘符吃掉，实测加载失败（表现为「无法加载该媒体」）。实测可用：`File` 对象 / `file:///C:/…`（三斜杠）/ 原始绝对路径。回归测试见 `src/test/kotlin/io/github/obihs2501/parsehubstudio/LocalImageLoadTest.kt`。
- **给 Coil 请求显式设 `size`**：不设就是按原图解码 —— 手机照片动辄 40MP+（实测一张 5585×8377 的 jpg 解码约 187 MB）。缩略图 / 封面预取 `.size(512, 512)`；图库瓦片用 `RemoteThumb(size = 512)` 解码（高 DPI 屏上比按布局尺寸清晰）、查看器缩略图条 `size = 256`；查看器**预览层 `.size(2048, 2048)`、原图层 `.size(Size.ORIGINAL)`**（原图解码 40MP 约 1~2s，叠在预览层之上完成，用户无感）。
- **解码并发限到 2**：`MediaImages.build()` 里 `decoderCoroutineContext(Dispatchers.IO.limitedParallelism(2))` —— 本地大图解码是纯 CPU 活，默认按 CPU 数并行会把整机拖满，滚动图库时表现为「高占用 + 卡顿」；同时关掉了 crossfade（既费 GPU 又让「点了图要等一下」更明显）。

---

## 8. 功能评估（2026-10-05）

| 页面 | 结论 | 说明 / 缺口 |
| --- | --- | --- |
| 解析工作台 | 核心可用 | 单条解析 + 批量解析并下载（≤50 条、3 路并发）+ 批次面板（逐条下载/重试失败/取消）+ 媒体网格 + 灯箱。缺口：正文富文本（markdown/html）未渲染，只显示纯文本摘要 |
| 下载管理 | 有用，但信息密度不足 | 后端 Task 只给 status/stage/progress/current/total/unit/files/output_dir/thumbnail，**没有速度和剩余时间**。已按「进行中 / 已结束」分区，加「全部取消」「清除已完成」「打开目录」「重新下载」。缺口：任务级文件列表（`files` 已有数据，未展开显示）、整体进度汇总 |
| 本地图库 | 核心可用 | 直接浏览本机已下载的图片 / 视频：多根目录（下载目录 + 添加的文件夹）、文件夹折叠、筛选 / 排序 / 列数、ffmpeg 视频首帧、两级解码查看器（预览 → 原图）、**VLC 播放器（vlcj）/ 未装 VLC 时回退 JavaFX 播放器**、栏宽可拖动、缩略图条可隐藏。**解析记录页已按反馈删除**（改看本地画廊），解析结果落 CSV：`<下载目录>/parsehub-history.csv` |
| 账户与代理 | 可用 | Cookie 池增 / 启停 / **删除**（`entry_id` 不带 `enabled` 即删除）、主 Cookie、平台解析 / 下载代理 |
| 解析网站收藏 | 可用 | 增删改 + 以独立应用窗口打开（系统 Edge/Chrome `--app`，**不内置浏览器**，找不到则回退默认浏览器） |
| 设置 | 可用 | 目录选择器（走 `/api/fs/list`）、分片数、代理模式/地址、FFmpeg 目录、命名模板（带实时预览）、作者别名、主题切换（浅色/深色，Mica 跟随） |

### 与 Tauri 版的差距（按优先级）

1. **单实例 / 托盘**（Tauri 版有；Compose 版未实现）
2. **界面偏好持久化**（栏宽 / 缩略图条已存 `ui-prefs.json`；列数 / 排序 / 筛选仍是内存态）
3. **正文富文本渲染**（后端已返回 `html`/`markdown` 字段，UI 未用）
4. **CI 上的 MSI/EXE**（见第 6 节）

---

## 9. 与 Tauri 版的关系

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

## 10. 未完成 / 待办

- [x] 解决 CI 上 WiX `light.exe` 311：MSI 元数据代码页 1252 装不下中文描述（见第 6 节）
- [x] 解析工作台「清空」同时清掉下方解析结果（`AppState.clearParse()`）
- [x] 图片加载：缩略图网格 + 封面（Coil 3，走 `/api/media` 代理，带 Bearer 鉴权）
- [x] 灯箱/大图预览：缩放/平移/旋转/1:1/适应/切图/键盘/缩略图条/保存到下载目录
- [x] 批次解析 UI：创建/轮询/取消/重试失败/选中查看/逐条下载
- [x] Cookie 池「添加账户」交互 + 主 Cookie + 平台代理
- [x] 设置项编辑（目录选择器、分片数、代理、模板预览、作者别名）
- [x] 主题切换（浅色/深色 + Mica 跟随）
- [x] 错误重试、进度轮询节流（活动 1.2s / 空闲 5s，仅下载页轮询）
- [x] 排版与配色对齐 miuix 规范（见第 7 节）
- [x] 开发模式免打包（自动用 `.venv` 的 python 起 sidecar）
- [x] 本地图库：多根目录（下载目录 + 添加的文件夹，持久化）+ ffmpeg 视频首帧 + 筛选/排序/列数
- [x] 本地文件加载（`File` model；`File.toURI()` 会失败，见 7.5）
- [x] Cookie 池删除（`entry_id` 不带 `enabled` 即删除）
- [x] 解析记录 CSV 导出（设置 → 数据）
- [x] 查看器两级解码（2048 预览 → 原图）+ 顶栏显示分辨率 + 缩放上限统一 400% + 平移边界限制
- [x] 首选播放器换成 VLC（vlcj，需本机装 VLC）；未装时回退内置 JavaFX 播放器（JFXPanel，自带解码器）
- [x] 查看器底部缩略图条可隐藏（持久化）
- [x] 图库栏宽可拖动 + 持久化（`ui-prefs.json`）
- [x] 移除图库文件夹：小叉 + 悬停变红 + 二次确认
- [x] 作者别名候选改内联下拉（长名字不截断）
- [ ] 单实例 / 托盘
- [ ] 正文富文本（`html`/`markdown`）渲染
- [x] 内置字体（MiSans 三个字重 + 本机 PingFang 优先；见 §7.4）
- [ ] 已知现象：窗口最大化时点「打开目录」，资源管理器可能被应用窗口挡住 —— 代码里**没有**置顶逻辑（已 grep 确认无 `alwaysOnTop` / `TOPMOST` / `SetWindowPos`），待复现确认
- [ ] ⚠️ 未验证：打包版（`createDistributable` / MSI）里 JavaFX native 能否正常解压 —— 若打包版视频播放失败，优先查这里（开发模式走 classpath，已可用）
- [x] 修 `published_at` 数字/字符串兼容（抖音解析结果解码崩溃，见 §3；回归测试 `ModelDecodeTest`）
- [x] 灯箱拖动/双击手势合并 + 尺寸未知时的平移限制修复（见 §7.6）
- [x] sidecar stderr 常驻读取 + `sidecar.log` + 启动失败带真实原因（见 §2）
- [x] 打包 DLL 固定 + `check_bundle.py` 齐全性/来源校验（见 §4）
- [x] CI 补跑 Compose 单测（`gradlew test`，含 `published_at` 解码回归，见 §5）
- [x] 抖音画质可选（`media[].variants` + 画质芯片 + `quality="vN"`）；取流不再过滤 H265、不再无条件优先低清 `play_addr`（见 §7.6）
- [x] 抖音 4K 不再依赖 cookie：接口只给低清档时自动用系统 Edge/Chrome 抓 discover 页补高清档（临时 profile、无 cookie、失败静默跳过，`PARSEHUB_DOUYIN_BROWSER` 可关闭/覆盖）；画质芯片行改可横向滚动（见 §7.6）
- [x] 修 0.6.2 解析超时 + 抓取提速：`ApiClient` 显式 `HttpTimeout` 180 秒（CIO 默认 15 秒扛不住）；抓取屏蔽图片（12.8s → 9.0s）、去掉无效的 virtual-time 重试（见 §7.6）
- [x] 版本号规范：每次发版抬 `version` / `packageVersion`（本次 0.6.3），否则 MSI 拒绝覆盖安装

---

## 11. 参考

- miuix: https://github.com/compose-miuix-ui/miuix （Apache-2.0）
- JavaFX Media（`org.openjfx:javafx-*:21.0.5:win`）：内置播放器（VLC 未安装时的回退）；native 随 jar 打包，`JFXPanel` + Compose `SwingPanel` 嵌入，classpath 方式使用（非模块化）
- vlcj（`uk.co.caprica:vlcj:4.8.3`）：首选播放器，调用本机安装的 VLC；`EmbeddedMediaPlayerComponent` + Compose `SwingPanel` 嵌入，`NativeDiscovery` 探测 libvlc
- 参考应用 PixEz-MIUIX: https://github.com/137458/pixez-miuix （GPL-3.0，**只参考设计，未复制代码**）
- Windows Mica 方案参考其 `WindowsMica.kt`（本项目的实现是独立重写的 JNA 调用）
