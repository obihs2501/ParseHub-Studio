"""ParseHub Studio 的 FastAPI 后端。"""

from __future__ import annotations

import os
import subprocess
import sys
import time
import webbrowser
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any, AsyncIterator, Literal
from urllib.parse import quote, urlparse

import httpx
from fastapi import FastAPI, HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from fastapi.staticfiles import StaticFiles
from parsehub import ParseHub
from parsehub.cli_config import default_config_dir
from parsehub.config import GlobalConfig
from parsehub.errors import ParseError, UnknownPlatform
from parsehub.utils.helpers import match_url
from parsehub.utils.media_network import media_candidates, media_headers
from pydantic import BaseModel, ConfigDict, Field
from starlette.concurrency import run_in_threadpool

from . import __version__
from .batches import BatchManager, extract_links
from .history import HistoryStore
from .naming import TOKENS, naming_values, render_name, validate_template
from .network import resolve_default_proxy
from .platform_config import PlatformConfigBridge
from .security import install_security
from .serialize import result_to_payload
from .settings import AuthorAlias, Bookmark, Settings, SettingsStore
from .tasks import TaskManager

WEB_DIR = Path(__file__).resolve().parent / "web"

STREAM_HEADERS = ("content-type", "content-length", "accept-ranges", "content-range", "etag", "last-modified")


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="ignore")


class ParseRequest(StrictModel):
    text: str
    proxy: str | None = None
    cookie: str | None = None
    use_saved_config: bool = True


class DetectRequest(StrictModel):
    text: str = ""


class CleanRequest(StrictModel):
    text: str
    proxy: str | None = None
    use_saved_config: bool = True


class TaskRequest(StrictModel):
    text: str
    media_index: int | None = None
    quality: Literal["origin", "thumb"] = "origin"
    path: str | None = None
    proxy: str | None = None
    parse_proxy: str | None = None
    cookie: str | None = None
    use_saved_config: bool = True
    save_metadata: bool | None = None
    connections: int | None = None


class ProxyRequest(StrictModel):
    platform: str
    proxy: str
    target: str = "all"


class ProxyClearRequest(StrictModel):
    platform: str
    target: str = "all"


class CookieRequest(StrictModel):
    platform: str
    cookie: str


class PoolRequest(StrictModel):
    platform: str
    cookie: str = Field(default="", max_length=65536)
    name: str = Field(default="", max_length=100)
    entry_id: str = ""
    enabled: bool | None = None


class PathRequest(StrictModel):
    path: str


class SettingsPayload(StrictModel):
    merge_author_folders: bool | None = None
    author_aliases: list[AuthorAlias] | None = Field(default=None, max_length=500)
    bookmarks: list[Bookmark] | None = Field(default=None, max_length=200)
    default_save_dir: Path | None = None
    connections: int | None = Field(default=None, ge=1, le=32)
    save_metadata: bool | None = None
    theme: str | None = None
    open_after_download: bool | None = None
    preview_mode: bool | None = None
    download_quality: Literal["origin", "thumb"] | None = None
    proxy_mode: str | None = None
    proxy_url: str | None = None
    ffmpeg_dir: str | None = None
    folder_template: str | None = None
    file_template: str | None = None


class BatchRequest(StrictModel):
    text: str = Field(min_length=1, max_length=30000)
    use_saved_config: bool = True
    download: bool = False


class NamingRequest(StrictModel):
    folder_template: str
    file_template: str


class AppContext:
    """应用级状态。"""

    def __init__(self) -> None:
        self.settings_store = SettingsStore()
        self.settings: Settings = self.settings_store.load()
        self.history = HistoryStore()
        self.bridge = PlatformConfigBridge()
        self.parsehub = ParseHub()
        self._proxy_cache: tuple[float, str | None] = (0, None)
        self._original_path = os.environ.get("PATH", "")
        self.tasks = TaskManager(self.bridge, on_success=self._remember)
        self.batches = BatchManager(self.parse_link, self.queue_download)
        self.apply_settings()

    def apply_settings(self) -> None:
        GlobalConfig.default_save_dir = Path(self.settings.default_save_dir)
        self._proxy_cache = (0, None)
        directory = self.settings.ffmpeg_dir.strip()
        os.environ["PATH"] = os.pathsep.join([directory, self._original_path]) if directory else self._original_path

    async def default_proxy(self) -> str | None:
        checked, proxy = self._proxy_cache
        if time.monotonic() - checked > 30:
            proxy = await run_in_threadpool(resolve_default_proxy, self.settings.proxy_mode, self.settings.proxy_url)
            self._proxy_cache = (time.monotonic(), proxy)
        return proxy

    async def parse_link(self, text: str, options: dict) -> dict:
        platform = self.parsehub.get_platform(text)
        proxy, cookie = options.get("proxy"), options.get("cookie")
        if options.get("use_saved_config", True):
            proxy, _, cookie = self.bridge.resolve(platform.id if platform else None, parse_proxy=proxy,
                                                   cookie=cookie, default_proxy=options.get("default_proxy"))
        result = await self.parsehub.parse(text, proxy=proxy, cookie=cookie)
        payload = result_to_payload(result)
        self.history.add(payload)
        return payload

    def queue_download(self, text: str, options: dict):
        return self.tasks.create(text, {**options, "text": text})

    def update_settings(self, payload: SettingsPayload) -> Settings:
        data = self.settings.model_dump()
        for key, value in payload.model_dump(exclude_none=True).items():
            data[key] = value
        self.settings = self.settings_store.save(Settings.model_validate(data))
        self.apply_settings()
        return self.settings

    def _remember(self, task: Any) -> None:
        if task.payload:
            self.history.add(task.payload)

    def platforms(self) -> list[dict[str, Any]]:
        result = []
        for item in self.parsehub.get_platforms():
            merged = dict(item)
            merged.update(self.bridge.describe(item["id"]))
            result.append(merged)
        return result


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    yield
    await app.state.ctx.batches.shutdown()
    await app.state.ctx.tasks.shutdown()


def create_app(session_token: str | None = None) -> FastAPI:
    app = FastAPI(title="ParseHub Studio", version=__version__, lifespan=lifespan)
    install_security(app, session_token)
    app.state.ctx = AppContext()

    def ctx(request: Request) -> AppContext:
        return request.app.state.ctx

    # ---------------------------------------------------------------- 基础信息
    @app.get("/api/health")
    def health() -> dict[str, Any]:
        import shutil

        return {"ok": True, "version": __version__, "desktop": bool(session_token), "ffmpeg": bool(shutil.which("ffmpeg")), "ffprobe": bool(shutil.which("ffprobe"))}

    @app.get("/api/platforms")
    async def platforms(request: Request) -> dict[str, Any]:
        return {"platforms": ctx(request).platforms()}

    @app.post("/api/detect")
    async def detect(request: Request, payload: DetectRequest) -> dict[str, Any]:
        context = ctx(request)
        url = match_url(payload.text)
        platform = context.parsehub.get_platform(payload.text) if payload.text else None
        return {
            "url": url,
            "platform": {"id": platform.id, "name": platform.display_name} if platform else None,
        }

    @app.post("/api/clean")
    async def clean(request: Request, payload: CleanRequest) -> dict[str, Any]:
        context = ctx(request)
        matched_url = match_url(payload.text)
        if not matched_url:
            raise HTTPException(status_code=400, detail="未在文案中找到链接")

        platform = context.parsehub.get_platform(payload.text)
        parse_proxy = payload.proxy
        if payload.use_saved_config:
            parse_proxy, _, _ = context.bridge.resolve(
                platform.id if platform else None,
                parse_proxy=parse_proxy,
                default_proxy=await context.default_proxy(),
            )

        try:
            cleaned_url = await context.parsehub.get_raw_url(matched_url, proxy=parse_proxy, clean_all=True)
        except UnknownPlatform:
            cleaned_url = matched_url
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=502, detail=f"链接清洗失败: {exc}") from exc

        cleaned_platform = context.parsehub.get_platform(cleaned_url)
        return {
            "url": cleaned_url,
            "original_url": matched_url,
            "platform": (
                {"id": cleaned_platform.id, "name": cleaned_platform.display_name}
                if cleaned_platform
                else ({"id": platform.id, "name": platform.display_name} if platform else None)
            ),
        }

    # ---------------------------------------------------------------- 解析
    @app.post("/api/parse")
    async def parse(request: Request, payload: ParseRequest) -> dict[str, Any]:
        context = ctx(request)
        platform = context.parsehub.get_platform(payload.text)
        proxy = payload.proxy
        cookie = payload.cookie
        if payload.use_saved_config:
            proxy, _, cookie = context.bridge.resolve(
                platform.id if platform else None,
                parse_proxy=proxy,
                cookie=cookie,
                default_proxy=await context.default_proxy(),
            )
        try:
            result = await context.parsehub.parse(payload.text, proxy=proxy, cookie=cookie)
        except UnknownPlatform as exc:
            raise HTTPException(status_code=400, detail="暂不支持该平台或未识别到链接") from exc
        except ParseError as exc:
            raise HTTPException(status_code=502, detail=str(exc) or "解析失败") from exc
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=502, detail=f"{type(exc).__name__}: {exc}") from exc
        result_payload = result_to_payload(result)
        context.history.add(result_payload)
        return result_payload

    # ---------------------------------------------------------------- 下载任务
    @app.post("/api/batches")
    async def start_batch(request: Request, payload: BatchRequest):
        context = ctx(request)
        try:
            links = await run_in_threadpool(extract_links, payload.text)
            options = {**payload.model_dump(), "default_proxy": await context.default_proxy(),
                       "path": str(context.settings.default_save_dir), "connections": context.settings.connections,
                       "save_metadata": context.settings.save_metadata, "quality": context.settings.download_quality,
                       "folder_template": context.settings.folder_template, "file_template": context.settings.file_template,
                       "merge_author_folders": context.settings.merge_author_folders,
                       "author_aliases": [alias.model_dump() for alias in context.settings.author_aliases]}
            return context.batches.create(links, options)
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error

    @app.get("/api/batches/{batch_id}")
    async def get_batch(request: Request, batch_id: str):
        batch = ctx(request).batches.batches.get(batch_id)
        if batch is None:
            raise HTTPException(status_code=404, detail="该批次已清理，请重新解析")
        return batch

    @app.post("/api/batches/{batch_id}/cancel")
    async def cancel_batch(request: Request, batch_id: str):
        ctx(request).batches.cancel(batch_id)
        return {"ok": True}

    @app.post("/api/naming/preview")
    async def preview_naming(payload: NamingRequest):
        values = naming_values({"platform": {"name": "抖音"}, "author": "示例作者", "title": "山间日落",
                                "post_id": "7691874698522529051", "published_at": "2026-09-28"})
        try:
            return {"folder": render_name(payload.folder_template, values),
                    "file": render_name(payload.file_template, values) + ".jpg", "tokens": TOKENS}
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error

    @app.get("/api/tasks")
    async def list_tasks(request: Request) -> dict[str, Any]:
        return {"tasks": ctx(request).tasks.list()}

    @app.post("/api/tasks")
    async def create_task(request: Request, payload: TaskRequest) -> dict[str, Any]:
        context = ctx(request)
        options = payload.model_dump()
        options["folder_template"] = context.settings.folder_template
        options["file_template"] = context.settings.file_template
        options["merge_author_folders"] = context.settings.merge_author_folders
        options["author_aliases"] = [alias.model_dump() for alias in context.settings.author_aliases]
        if options.get("path") is None:
            options["path"] = str(context.settings.default_save_dir)
        if options.get("save_metadata") is None:
            options["save_metadata"] = context.settings.save_metadata
        if options.get("connections") is None:
            options["connections"] = context.settings.connections
        if options.get("default_proxy") is None:
            options["default_proxy"] = await context.default_proxy()
        task = context.tasks.create(payload.text, options)
        return task.to_dict()

    @app.get("/api/tasks/{task_id}")
    async def get_task(request: Request, task_id: str) -> dict[str, Any]:
        task = ctx(request).tasks.get(task_id)
        if task is None:
            raise HTTPException(status_code=404, detail="任务不存在")
        return task.to_dict()

    @app.post("/api/tasks/{task_id}/cancel")
    async def cancel_task(request: Request, task_id: str) -> dict[str, Any]:
        if not ctx(request).tasks.cancel(task_id):
            raise HTTPException(status_code=400, detail="任务无法取消")
        return {"ok": True}

    @app.post("/api/tasks/clear")
    async def clear_tasks(request: Request) -> dict[str, Any]:
        ctx(request).tasks.clear_finished()
        return {"ok": True}

    # ---------------------------------------------------------------- 配置
    @app.get("/api/config")
    async def get_config(request: Request) -> dict[str, Any]:
        context = ctx(request)
        return {
            "platforms": context.platforms(),
            "config_path": str(context.bridge.config_path),
            "cookie_path": str(context.bridge.cookie_path),
            "parsehub_config_dir": str(default_config_dir()),
        }

    @app.post("/api/config/proxy")
    async def set_proxy(request: Request, payload: ProxyRequest) -> dict[str, Any]:
        target = payload.target if payload.target in {"parse", "download", "all"} else "all"
        ctx(request).bridge.set_proxy(payload.platform, payload.proxy.strip(), target)  # type: ignore[arg-type]
        return ctx(request).bridge.describe(payload.platform)

    @app.post("/api/config/proxy/clear")
    async def clear_proxy(request: Request, payload: ProxyClearRequest) -> dict[str, Any]:
        context = ctx(request)
        target = payload.target if payload.target in {"parse", "download", "all"} else "all"
        context.bridge.clear_proxy(payload.platform, target)  # type: ignore[arg-type]
        return context.bridge.describe(payload.platform)

    @app.post("/api/config/cookie")
    async def set_cookie(request: Request, payload: CookieRequest) -> dict[str, Any]:
        context = ctx(request)
        cookie = payload.cookie.strip()
        if not cookie:
            raise HTTPException(status_code=400, detail="Cookie 不能为空")
        context.bridge.set_cookie(payload.platform, cookie)
        return context.bridge.describe(payload.platform)

    @app.post("/api/config/cookie/clear")
    async def clear_cookie(request: Request, payload: CookieRequest) -> dict[str, Any]:
        context = ctx(request)
        context.bridge.clear_cookie(payload.platform)
        return context.bridge.describe(payload.platform)

    @app.post("/api/config/cookie-pool")
    async def update_cookie_pool(request: Request, payload: PoolRequest) -> dict:
        context = ctx(request)
        if payload.platform not in {item["id"] for item in context.parsehub.get_platforms()}:
            raise HTTPException(status_code=400, detail="未知平台")
        if payload.entry_id:
            context.bridge.change_cookie(payload.platform, payload.entry_id, payload.enabled)
        elif payload.cookie.strip():
            context.bridge.add_cookie(payload.platform, payload.cookie.strip(), payload.name.strip())
        else:
            raise HTTPException(status_code=400, detail="Cookie 不能为空")
        return context.bridge.describe(payload.platform)

    # ---------------------------------------------------------------- 设置
    @app.get("/api/settings")
    async def get_settings(request: Request) -> dict[str, Any]:
        return ctx(request).settings.model_dump(mode="json")

    @app.post("/api/settings")
    async def update_settings(request: Request, payload: SettingsPayload) -> dict[str, Any]:
        try:
            for template in (payload.folder_template, payload.file_template):
                if template is not None:
                    validate_template(template)
        except ValueError as error:
            raise HTTPException(status_code=400, detail=str(error)) from error
        return ctx(request).update_settings(payload).model_dump(mode="json")

    # ---------------------------------------------------------------- 历史
    @app.get("/api/history")
    async def get_history(request: Request) -> dict[str, Any]:
        return {"entries": ctx(request).history.load()}

    @app.delete("/api/history/{entry_id}")
    async def delete_history(request: Request, entry_id: str) -> dict[str, Any]:
        return {"ok": ctx(request).history.remove(entry_id)}

    @app.post("/api/history/clear")
    async def clear_history(request: Request) -> dict[str, Any]:
        ctx(request).history.clear()
        return {"ok": True}

    # ---------------------------------------------------------------- 文件系统
    @app.get("/api/fs/list")
    async def list_dirs(request: Request, path: str | None = None) -> dict[str, Any]:
        return await run_in_threadpool(_list_dirs, path)

    @app.post("/api/reveal")
    async def reveal(payload: PathRequest) -> dict[str, Any]:
        await run_in_threadpool(_reveal, payload.path)
        return {"ok": True}

    @app.post("/api/open-url")
    async def open_url(payload: PathRequest) -> dict[str, Any]:
        parsed = urlparse(payload.path)
        if parsed.scheme not in {"https", "http"} or not parsed.hostname or parsed.username or parsed.password:
            raise HTTPException(status_code=400, detail="仅允许打开 HTTP/HTTPS 链接")
        await run_in_threadpool(webbrowser.open, payload.path)
        return {"ok": True}

    # ---------------------------------------------------------------- 媒体代理
    @app.api_route("/api/media", methods=["GET", "HEAD"])
    async def media_proxy(
        request: Request,
        src: str = Query(..., min_length=1),
        referer: str | None = None,
        download: int = 0,
        filename: str | None = None,
    ) -> StreamingResponse:
        return await _stream_media(
            request,
            src,
            referer=referer,
            download=bool(download),
            filename=filename,
            proxy=await ctx(request).default_proxy(),
        )

    app.mount("/", StaticFiles(directory=str(WEB_DIR), html=True), name="web")
    return app


async def _stream_media(
    request: Request,
    src: str,
    *,
    referer: str | None,
    download: bool,
    filename: str | None,
    proxy: str | None = None,
) -> StreamingResponse:
    """代理远程媒体，用于浏览器预览（绕过防盗链 / 跨域限制）。"""
    parsed = urlparse(src)
    if parsed.scheme not in {"http", "https"}:
        raise HTTPException(status_code=400, detail="仅支持 http/https 媒体地址")

    headers: dict[str, str] = {**media_headers(src), "Accept": "*/*"}
    if referer:
        ref = urlparse(referer)
        if ref.scheme and ref.netloc:
            headers["Referer"] = f"{ref.scheme}://{ref.netloc}/"
    else:
        headers["Referer"] = f"{parsed.scheme}://{parsed.netloc}/"
    range_header = request.headers.get("range")
    if range_header:
        headers["Range"] = range_header

    client = await run_in_threadpool(
        httpx.AsyncClient, proxy=proxy, trust_env=False, follow_redirects=True, timeout=httpx.Timeout(60.0, connect=8.0)
    )
    response = None
    last_error = ""
    try:
        for candidate in media_candidates(src):
            try:
                upstream = client.build_request(request.method, candidate, headers=headers)
                response = await client.send(upstream, stream=True)
                if response.status_code < 400:
                    break
                last_error = f"上游返回 {response.status_code}"
                await response.aclose()
                response = None
            except httpx.HTTPError as error:
                last_error = f"{type(error).__name__}: {str(error) or '连接中断或超时'}"
        if response is None:
            raise HTTPException(status_code=502, detail=f"媒体获取失败: {last_error}")
    except BaseException:
        await client.aclose()
        raise

    out_headers = {key: value for key in STREAM_HEADERS if (value := response.headers.get(key))}
    out_headers["Cache-Control"] = "private, max-age=3600"
    if download:
        name = filename or os.path.basename(parsed.path) or "download"
        out_headers["Content-Disposition"] = f"attachment; filename*=UTF-8''{quote(name)}"

    async def body() -> AsyncIterator[bytes]:
        try:
            async for chunk in response.aiter_bytes(64 * 1024):
                yield chunk
        finally:
            await response.aclose()
            await client.aclose()

    media_type = response.headers.get("content-type") or "application/octet-stream"
    return StreamingResponse(body(), status_code=response.status_code, headers=out_headers, media_type=media_type)


def _list_dirs(raw: str | None) -> dict[str, Any]:
    if not raw:
        if os.name == "nt":
            drives = [f"{letter}:\\" for letter in "CDEFGHIJKLMNOPQRSTUVWXYZ" if Path(f"{letter}:\\").exists()]
            return {"path": "", "parent": None, "dirs": [{"name": d, "path": d} for d in drives]}
        raw = str(Path.home())
    base = Path(raw).expanduser()
    if not base.is_dir():
        raise HTTPException(status_code=400, detail="目录不存在")
    dirs: list[dict[str, str]] = []
    try:
        for entry in sorted(base.iterdir(), key=lambda item: item.name.lower()):
            if entry.name.startswith(".") or not entry.is_dir():
                continue
            dirs.append({"name": entry.name, "path": str(entry)})
    except PermissionError as exc:
        raise HTTPException(status_code=403, detail="无权限访问该目录") from exc
    parent = str(base.parent) if base.parent != base else None
    return {"path": str(base), "parent": parent, "dirs": dirs}


def _reveal(path: str) -> None:
    target = Path(path).expanduser()
    if os.name == "nt":
        if target.is_dir():
            os.startfile(str(target))  # type: ignore[attr-defined]  # noqa: S606
        else:
            subprocess.Popen(["explorer", "/select,", str(target)])  # noqa: S603, S607
    elif sys.platform == "darwin":
        subprocess.Popen(["open", "-R" if target.is_file() else "", str(target)])  # noqa: S603, S607
    else:
        subprocess.Popen(["xdg-open", str(target)])  # noqa: S603, S607


app = create_app(os.environ.get("PARSEHUB_SESSION_TOKEN"))
