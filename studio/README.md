# ParseHub Studio

[ParseHub](https://github.com/z-mio/ParseHub) 的 Windows 可视化桌面版：**Tauri 2 + Python sidecar**。
解析、下载完全复用上游 Python 库；保留 MIT 许可证和上游来源。

## 下载与安装

### 0.4.1 抖音原画修复

- 修复抖音视频下载拿不到原画的问题：App/移动端接口的顶层 `play_addr`（原画母带）重新被优先选取，不再误选 `bit_rate[]` 里体积更小的 bytevc1/bytevc2（H.266）转码档位。Web 接口仍按 `bit_rate[]` 选择最高档位。
- 补充抖音选流的回归测试：App 接口必须优先顶层原画，回退到 `bit_rate[]` 时必须过滤 bytevc1/bytevc2。

### 0.4.0 工作台整理与作者归档

- 桌面主窗口默认 `1040 × 680`（逻辑像素），居中打开，可缩小到 `820 × 560`；较矮窗口的侧栏保留滚动，不遮挡底部设置入口。
- 下载管理显示本次运行的任务、封面、正文标题、博主、作品 ID、进度和输出目录；不再把文件名或时间戳当标题。历史记录按「平台 → 博主」分组，两级都可折叠，折叠状态保存在本地，轮询刷新不会展开。
- Cookie 池和解析网站收藏与解析、下载、历史一样，使用侧栏主页面，不再弹窗。
- 旧历史缺少博主或正文时明确显示「未知作者 / 无标题作品」；点击「更新信息」重新解析原帖可补全平台实际返回的信息，不推测缺失字段。
- 下载时顶部显示当前文件的字节进度；未知文件大小显示等待状态。多媒体作品逐文件更新进度。
- 视频卡片显示接口返回的分辨率；作者、作品 ID 和发布时间在作品信息中展示，缺失数据不推测。当前补充了抖音和微博的作者 ID / 发布时间。
- 独立「Cookie 池」按平台添加备注、启停和删除账户，每次解析轮换一个启用条目；显式 Cookie 优先，池为空或全部停用时使用 CLI 默认 Cookie。不自动重试失效账户，不绕过平台限制。
- Cookie 池保存在 CLI Cookie 文件同目录的 `studio_cookie_pool.json`，本机明文存储，API 仅返回脱敏提示；请勿分享配置目录。
- 「解析网站收藏」支持添加、编辑、删除和分组，预置 KuKuTool；手动在外部浏览器使用，不传送本地 Cookie、不自动导入结果。
- 大文件沿用已有多连接分片下载（默认 4 路，设置中可调 1–32）；每片至少约 10 MiB，服务端不支持 Range 时回退单连接。
- 文件夹和文件名模板新增 `{publish_date}`（发布日期），格式 `YYYY-MM-DD`。秒/毫秒时间戳及带时区日期按本机时区转换，缺失时使用「未知发布日期」。`{date}` 仍表示下载日期。例如 `{publish_date}_{author}_{title}`。已有历史若无发布时间，需重新解析。
- 默认开启「同名文件夹合并归档」：例如文件夹模板 `{platform}_{author}` 的多次下载进入同一个 `微博_岛田云溪`，不再另建 `_2` 目录；文件、实况视频及元数据重名时只为对应文件加后缀，已有文件不覆盖。关闭该选项则保留每次新建目录的行为。
- 跨平台归档：在「设置 → 文件命名 → 跨平台作者映射」中，把各平台作者 ID（推荐）或准确昵称映射到同一个归档作者，然后将文件夹模板设为 `{creator}`，文件名建议 `{platform}_{id}_{index:03d}`。不自动判断不同账号是否同一人，也不做跨平台内容去重。
- 合并归档先在下载根目录的 `.parsehub-*` 临时目录下载，完成后安全归档；失败或取消时保留可恢复的临时文件，可从下载任务打开目录。已有 `_2` 等目录不会自动移动或删除。
- 微博历史封面优先走本地媒体代理，使用该条历史的来源地址与 CDN 回退；过期封面会尝试原图，仍失败则显示「封面失效」。

打开本仓库的 [Releases](https://github.com/obihs2501/ParseHub-Studio/releases)，选择最新测试版：

- `*-setup.exe`：推荐。当前用户安装，无需管理员权限；缺少 WebView2 时自动联网安装运行时。
- `ParseHubStudio-windows-x64-portable.zip`：解压整个目录，运行 `ParseHubStudio.exe`，不要单独移动 EXE。
- `SHA256SUMS.txt`：安装器与免安装包的 SHA-256 校验值。

支持 Windows 10/11 x64。**用户不需要安装 Python、Node.js、Rust 或编译工具。**
免安装版需要系统已有 Microsoft Edge WebView2 Runtime。0.2.0 起提供轻量版，不再内置 FFmpeg/FFprobe、OpenCV、NumPy 和 pydub。
已有 FFmpeg 的电脑直接使用系统 PATH；也可在「设置 → 通用 → 本机 FFmpeg 目录」选择含 `ffmpeg.exe` 和 `ffprobe.exe` 的 bin 目录。
普通图片下载不需要 FFmpeg；部分视频合并/转码需要。视频尺寸与时长改用本机 FFprobe 读取，缺失时仍保留平台元数据。
当前为未签名预览版，Windows SmartScreen 可能提示未知发布者；请核对下载来源和校验值。
第三方平台的 Cookie、网络与风控要求仍然适用，不保证所有平台链接始终可解析。

## 界面功能

- 左侧导航分离解析、下载与历史记录，支持平台收纳到「关于」，内容工作区独立滚动。
- 粘贴多条链接或分享文案，支持不同平台混合解析：每批最多 50 条，自动去重，最多 3 路并发。
- 逐条查看成功或失败的结果，可取消剩余解析、重试失败项或整批解析并下载；平台 Cookie / 代理分别生效。
- 图片、视频、GIF、Live Photo 和富文本预览；微博网格使用 1024 像素高清图，点开灯箱始终加载原图。
- 竖图不再裁成方块，正文默认折叠、可展开全文，图文区不再嵌套正文滚动条。
- 图片查看器支持滚轮 / 按钮缩放、1:1 原尺寸、适应窗口、拖动平移、旋转与方向键翻页。
- 抖音在接口返回的可用视频中优先选择高分辨率、较大文件 / 较高码率的地址；不转码，也不保证所有作品都有 1080p / 4K。
- 下载队列、进度、取消任务、单媒体下载和打开输出目录。
- 历史记录、深浅色主题、可视化目录选择。
- 按平台设置代理和 Cookie，与 ParseHub CLI 的配置共用。
- 状态栏显示本地服务连接状态；连续三次健康检查失败才提示断连，短暂加载延迟不会闪烁报错。
- 关于页显示运行方式、FFmpeg 状态和配置位置。

### 元素式命名

在「设置 → 文件命名」分别编辑作品文件夹、媒体文件名；点击元素按钮插入，并实时预览示例。

| 元素 | 含义 |
| --- | --- |
| `{platform}` / `{author}` | 平台名 / 作者名（目前微博、抖音提供作者，其余未提供时用「未知作者」） |
| `{title}` / `{id}` | 标题（最多 40 个 UTF-16 单位）/ 作品 ID 或链接标识 |
| `{date}` / `{time}` | 本机下载日期 `2026-10-02` / 时间 `090807`，不是作品发布日期 |
| `{index:03d}` / `{type}` | 序号 `001` / 媒体类型，单项下载保留原序号 |

例如文件夹 `{platform}_{author}_{id}`，文件 `{index:03d}_{title}`。扩展名自动添加，不要写入模板。
同名文件 / 文件夹自动加后缀，Live Photo 视频添加 `_live`；非法字符、Windows 保留名称会清理。
模板只生成一层文件夹或文件名，不支持 `/`、`\\` 路径分隔符。新规则仅对之后创建的下载任务生效。

### 可选第三方解析网页

展开「解析选项与外部工具」，可打开用户指定的 `https://dy.kukutool.com/`。
桌面版使用独立 WebView 窗口，浏览器开发模式使用外部浏览器；需手动粘贴链接、按网站要求完成验证。
不会自动提交作品链接、传送本地 Cookie 或导入网站结果。网站自身联网、Cookie、下载流程由其提供，关闭主窗口也会关闭第三方窗口。

### 包体大小

上一版 0.2.0 轻量安装器约 43 MiB，已移除 FFmpeg、OpenCV、NumPy 和 pydub；本版未新增大型运行依赖。
保留独立 Python 运行时、全部平台解析器和网络 / 加密库的离线安装包不能合理压到 10 MB 以下；不以首次运行下载依赖来伪装小包体。
本界面只参考 x-spider 的通用侧栏和留白布局，不复制其 GPL 源码或引入它的前端依赖。

关闭桌面窗口会停止 sidecar 和正在运行的下载；未完成的任务不会自动恢复。
Python 服务只监听 `127.0.0.1` 的系统分配端口，每次启动创建独立随机会话。
Tauri 通过就绪握手打开页面，退出时关闭管道通知 Python 释放任务与端口。
HTTP API 与媒体代理需要会话凭据，拒绝跨站请求；富文本使用白名单清洗，远程页面没有 Tauri IPC 权限。

## GitHub 云端编译

工作流：`.github/workflows/studio-build.yml` → **Build ParseHub Studio**。
推送相关代码或在 Actions 页面点击 **Run workflow** 即可，无需本地编译环境。

1. Linux 运行离线 API、安全测试、源码 sidecar 启停测试和 JavaScript 语法检查。
2. Windows 安装锁定的 Python/Node 依赖和 Rust 工具链，不再下载或捆绑 FFmpeg。
3. PyInstaller 把 Python 和全部动态解析器打包成单文件 sidecar，并检查产物中没有被移除的重型依赖。
4. 对冻结后的 EXE 测试鉴权、平台发现、静态资源、父进程管道关闭后的退出。
5. Tauri 构建 NSIS 安装器；组装完整免安装包。
6. 真实启动 Tauri WebView，确认内置 sidecar 页面加载成功。
7. 上传 `ParseHubStudio-windows-x64` artifact，并自动发布带构建号的预发布 Release。

PR 只构建和测试，不发布 Release。失败时不会发布半成品。
构建依赖和桌面版本号分别位于 `uv.lock`、`package-lock.json`、`src-tauri/Cargo.toml` 和 `src-tauri/tauri.conf.json`。

## 源码浏览器开发模式

不编译 Tauri 也能修改和验证界面：

```powershell
cd studio
uv sync --locked
uv run parsehub-studio
```

Windows 可运行 `run.ps1` / `run.bat`。默认端口 `8765`；`--port 0` 自动选择空闲端口，
`--no-browser` 禁止自动打开浏览器。浏览器开发模式不使用桌面会话令牌，不适合暴露到网络。
旧的 pywebview / `--native` 模式已移除，原生窗口由 Tauri 统一负责。

```powershell
uv run python tests/smoke_test.py
uv run python -m unittest discover -s tests -p "test_*.py" -v
uv run python tests/sidecar_check.py
```

需要本地调试 Tauri 的开发者，可参考工作流准备 Rust/MSVC/WebView2，生成图标、sidecar 和 FFmpeg 资源后，
运行 `npm ci`、`npm run desktop:dev`。普通使用者直接下载安装包即可。

## 目录与排错

- `src/parsehub_studio/web/`：原生 HTML/CSS/JavaScript 界面，不依赖前端打包器。
- `src/parsehub_studio/sidecar.py`：随机端口、就绪握手和父进程退出监控。
- `src/parsehub_studio/security.py`：本地 API 会话、跨站防护和内容安全策略。
- `src-tauri/`：桌面窗口、子进程生命周期、安装器与资源配置。
- `packaging/`：PyInstaller 入口、动态模块收集与应用图标生成。
- `tests/`：API、安全与打包后生命周期测试。

首次启动需要解压 Python 环境，可能比后续启动慢；启动超时会在窗口显示原因和日志路径。
启动日志位于 `%LOCALAPPDATA%\io.github.obihs2501.parsehubstudio\sidecar.log`。
设置与历史由 `platformdirs` 保存，具体 CLI 配置路径可在「设置 → 关于」查看。
分享日志前请检查并移除其中可能出现的链接或账户信息；不要上传 Cookie 配置文件。

第三方组件说明见 `src-tauri/resources/THIRD_PARTY.md` 和安装包中的资源目录。

## 0.2.0 修复说明

- 微博原图下载与本地媒体代理遇到单个 `wx*.sinaimg.cn` 节点故障会切换同组 CDN，保留 `/large/` 原图路径，不用缩略图冒充原图；错误显示异常类型与失败主机。
- 抖音分享链接从第一跳的 `Location` 提取作品 ID，不再依赖可能连接失败的分享落地页；清洗与解析共用同一处理。
- 代理读取、HTTP 客户端初始化与本地媒体信息探测移出异步服务主循环；系统代理缓存 30 秒，自定义代理不会悄悄回退直连。
- 这些修复不绕过平台登录或风控；若平台接口拒绝请求，仍需有效 Cookie 或合适的网络配置。
