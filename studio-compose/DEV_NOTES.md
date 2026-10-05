# ParseHub Studio · Compose/miuix 前端开发文档

> 本文档面向接手排查的开发者/模型。当前分支：`feat/miuix-ui`。
> 最后状态：**UI 全量对齐 miuix 规范（排版/配色），图片/灯箱/批次/Cookie/设置编辑/主题切换全部接通；开发模式不再需要先打包 sidecar。**
> CI 侧：GitHub Actions 构建通过，产出免安装包（含 Python sidecar）；MSI/EXE 仍受 WiX `light.exe` 311 影响（见第 6 节）。

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

### 三个易踩的坑

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

**免安装包（`ParseHubStudioCompose-windows-x64-portable.zip`）是可靠产出**，包含：
- `ParseHubStudio.exe`（jpackage 启动器）
- `app/`（jar + skiko + sidecar + coil/ktor 依赖）
- `runtime/`（JBR）
- `app/resources/parsehub-sidecar.exe`（Python 后端）

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
- **可折叠分组头**：统一带底色（`secondaryVariant` + `squircleSurface` 12dp）+ `primary` 色箭头，和普通标题行明显区分；展开/收起状态看箭头方向与下方内容。
- **本地图库**：支持**多个根目录** —— 默认下载目录 + 用户添加的文件夹（持久化在 `%LOCALAPPDATA%/ParseHubStudio/gallery-roots.json`，不走后端设置接口，避免污染 settings.json）；左侧按根目录分组（可折叠、额外目录可移除），右侧内容区（面包屑 + 上一级 / 重新扫描 / 打开目录）；工具栏两行：筛选（全部 / 图片 / 视频）+ 文件名搜索 + 「显示设置」（展开后是排序、升降序、列数 2–10、裁剪 / 完整、视频首帧）；网格分页 120，子文件夹以卡片展示。视频首帧用本机 ffmpeg 抽帧缓存到 `%LOCALAPPDATA%/ParseHubStudio/videothumbs`。新下载仍然只写默认下载目录。
  - **栏宽可拖动**：左侧导航栏与图库文件夹栏都有 10dp 拖动把手（悬停变手型 + 高亮），宽度持久化在 `%LOCALAPPDATA%/ParseHubStudio/ui-prefs.json`（拖动过程不写盘，松手才存）。
  - **移除文件夹**：入口收进分组标题行右侧的小叉（无底色，悬停变红），点击后**二次确认**（只从列表移除，不删磁盘文件）——早先是一行「移除此文件夹」按钮，容易误触且深浅色下都不好看。
  - **进文件夹预取前 4 张 2048 预览图**：第一次点开图片不用等解码。
- **灯箱（统一查看器）**：远程媒体（解析结果 / 解析记录）与本地文件（图库）共用一套查看器，`ViewerItem` 区分来源。**两级解码**：2048 预览层先出画面，后台再解原尺寸叠上去（宽高比一致、切换无跳动），所以既没有打开延迟，放大后也是原图像素。顶栏显示 `序号 · 分辨率 · 大小/时间`；`1:1` 与百分比按**原图像素**换算（本地分辨率读文件头即得，不用等解码）；缩放上限统一为「显示比例 400%」（早先是固定 8× 乘数，每张图上限各不相同，出现 169% / 324% 那种怪值）。滚轮缩放 / 拖动平移（**限制在图片边界内**，拖不丢）/ 双击复位 / ← → 切换 / Esc 关闭，预取相邻图片（2048）。**本地视频优先用 VLC 播放**（vlcj + `EmbeddedMediaPlayerComponent` 嵌进 Compose `SwingPanel`）—— 编解码覆盖最全（HEVC / .ts / MKV / AV1 都能放），代价是需要本机安装 VLC（不随包分发）；**没装 VLC 时回退内置 JavaFX 播放器**（JFXPanel + SwingPanel，自带 H.264/AAC 解码器，win 分类器 jar 含 native，约 35 MB）；两者都放不了时，在播放器内给出「用系统播放器打开」兜底。远程视频只显示首帧（需先保存到下载目录）。底部缩略图条可一键隐藏（「隐藏缩略图 / 显示缩略图」，持久化在 `ui-prefs.json`）。
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
- **给 Coil 请求显式设 `size`**：不设就是按原图解码 —— 手机照片动辄 40MP+（实测一张 5585×8377 的 jpg 解码约 187 MB）。缩略图 / 封面预取 `.size(512, 512)`；图库瓦片用 `RemoteThumb(size = 512)` 解码（高 DPI 屏上比按布局尺寸清晰）、查看器缩略图条 `size = 256`；查看器**预览层 `.size(2048, 2048)`、原图层 `.size(Size.ORIGINAL)`**（原图解码 40MP 约 1~2s，叠在预览层之上完成，用户无感；本地文件比远程更值，因为不用走网络）。

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

- [ ] 解决 CI 上 WiX `light.exe` 311（见第 6 节）
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

---

## 11. 参考

- miuix: https://github.com/compose-miuix-ui/miuix （Apache-2.0）
- JavaFX Media（`org.openjfx:javafx-*:21.0.5:win`）：内置播放器（VLC 未安装时的回退）；native 随 jar 打包，`JFXPanel` + Compose `SwingPanel` 嵌入，classpath 方式使用（非模块化）
- vlcj（`uk.co.caprica:vlcj:4.8.3`）：首选播放器，调用本机安装的 VLC；`EmbeddedMediaPlayerComponent` + Compose `SwingPanel` 嵌入，`NativeDiscovery` 探测 libvlc
- 参考应用 PixEz-MIUIX: https://github.com/137458/pixez-miuix （GPL-3.0，**只参考设计，未复制代码**）
- Windows Mica 方案参考其 `WindowsMica.kt`（本项目的实现是独立重写的 JNA 调用）
