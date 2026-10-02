# ParseHub Studio

[ParseHub](https://github.com/z-mio/ParseHub) 的 Windows 可视化桌面版：**Tauri 2 + Python sidecar**。
解析、下载完全复用上游 Python 库；保留 MIT 许可证和上游来源。

## 下载与安装

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

- 粘贴链接或分享文案，自动识别平台，清洗链接、解析并下载。
- 图片、视频、GIF、Live Photo 和富文本预览；微博网格使用 1024 像素高清图，点开灯箱始终加载原图。
- 竖图不再裁成方块，正文默认折叠、可展开全文，图文区不再嵌套正文滚动条。
- 下载队列、进度、取消任务、单媒体下载和打开输出目录。
- 历史记录、深浅色主题、可视化目录选择。
- 按平台设置代理和 Cookie，与 ParseHub CLI 的配置共用。
- 状态栏显示本地服务连接状态；连续三次健康检查失败才提示断连，短暂加载延迟不会闪烁报错。
- 关于页显示运行方式、FFmpeg 状态和配置位置。

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
