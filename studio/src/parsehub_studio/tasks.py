"""下载任务管理：在事件循环中执行下载并把进度暴露给前端。"""

from __future__ import annotations

import asyncio
import time
import uuid
from dataclasses import dataclass, field, replace
from pathlib import Path
from typing import Any, Callable, Sequence
from urllib.parse import urlparse

from parsehub import ParseHub
from parsehub.errors import ParseError, UnknownPlatform
from parsehub.types import LivePhotoRef, MultimediaParseResult

from .platform_config import PlatformConfigBridge
from .serialize import result_to_payload

FINISHED_STATUSES = {"success", "partial", "error", "cancelled"}


@dataclass
class Task:
    id: str
    text: str
    status: str = "pending"
    stage: str = "排队中"
    progress: float = 0.0
    current: int = 0
    total: int = 0
    unit: str = ""
    error: str | None = None
    output_dir: str | None = None
    files: list[str] = field(default_factory=list)
    platform: str | None = None
    title: str | None = None
    thumbnail: str | None = None
    created_at: float = field(default_factory=time.time)
    finished_at: float | None = None
    payload: dict[str, Any] | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "text": self.text,
            "status": self.status,
            "stage": self.stage,
            "progress": round(self.progress, 4),
            "current": self.current,
            "total": self.total,
            "unit": self.unit,
            "error": self.error,
            "output_dir": self.output_dir,
            "files": self.files,
            "platform": self.platform,
            "title": self.title,
            "thumbnail": self.thumbnail,
            "created_at": self.created_at,
            "finished_at": self.finished_at,
        }


def _as_media_list(media: Any) -> list[Any]:
    if media is None:
        return []
    if isinstance(media, Sequence) and not isinstance(media, (str, bytes)):
        return list(media)
    return [media]


def _pick_media(result: Any, index: int) -> Any:
    """把多媒体的某个下标单独构造成一个可下载的结果。"""
    items = _as_media_list(getattr(result, "media", None))
    if not items:
        raise ParseError("该内容没有可下载的媒体")
    if index < 0 or index >= len(items):
        raise ParseError(f"媒体下标越界: {index}")
    selected = MultimediaParseResult(title=result.title, media=[items[index]], content=result.content)
    selected.raw_url = getattr(result, "raw_url", "") or ""
    selected.platform = getattr(result, "platform", None)
    selected.name = f"{result.name}_{index + 1}"
    return selected


IMAGE_EXTS = {"jpg", "jpeg", "png", "webp", "gif", "bmp", "avif", "heic"}


def _guess_ext(url: str, fallback: str) -> str:
    suffix = Path(urlparse(url).path).suffix.lstrip(".").lower()
    if suffix in IMAGE_EXTS:
        return suffix
    return fallback if fallback in IMAGE_EXTS else "jpg"


def _to_thumb(media: Any) -> Any:
    """把媒体替换成它的缩略图版本。"""
    thumb = getattr(media, "thumb_url", None)
    if not thumb:
        return media
    ext = _guess_ext(thumb, getattr(media, "ext", None) or "jpg")
    if isinstance(media, LivePhotoRef):
        return replace(media, url=thumb, ext=ext, video_url=None)
    return replace(media, url=thumb, ext=ext)


def _apply_quality(result: Any, quality: str) -> Any:
    """按下载规格改写解析结果；``thumb`` 表示只下载预览图。"""
    if quality != "thumb":
        return result
    items = _as_media_list(getattr(result, "media", None))
    replaced = [_to_thumb(item) for item in items]
    if not replaced or replaced == items:
        return result
    new_result = MultimediaParseResult(title=result.title, media=replaced, content=result.content)
    new_result.raw_url = getattr(result, "raw_url", "") or ""
    new_result.platform = getattr(result, "platform", None)
    new_result.name = f"{result.name}_thumb"
    return new_result


class TaskManager:
    def __init__(
        self,
        bridge: PlatformConfigBridge,
        *,
        on_success: Callable[[Task], None] | None = None,
        on_finish: Callable[[Task], None] | None = None,
    ) -> None:
        self.bridge = bridge
        self._tasks: dict[str, Task] = {}
        self._running: dict[str, asyncio.Task[None]] = {}
        self._on_success = on_success
        self._on_finish = on_finish

    def create(self, text: str, options: dict[str, Any]) -> Task:
        task = Task(id=uuid.uuid4().hex[:12], text=text)
        self._tasks[task.id] = task
        self._running[task.id] = asyncio.create_task(self._run(task, options))
        return task

    def list(self) -> list[dict[str, Any]]:
        return [task.to_dict() for task in sorted(self._tasks.values(), key=lambda t: t.created_at, reverse=True)]

    def get(self, task_id: str) -> Task | None:
        return self._tasks.get(task_id)

    def cancel(self, task_id: str) -> bool:
        running = self._running.get(task_id)
        if running is None or running.done():
            return False
        running.cancel()
        return True

    def clear_finished(self) -> None:
        for task_id in [tid for tid, task in self._tasks.items() if task.status in FINISHED_STATUSES]:
            self._tasks.pop(task_id, None)
            self._running.pop(task_id, None)

    async def shutdown(self) -> None:
        for running in self._running.values():
            if not running.done():
                running.cancel()
        if self._running:
            await asyncio.gather(*self._running.values(), return_exceptions=True)

    async def _run(self, task: Task, options: dict[str, Any]) -> None:
        task.status = "running"
        task.stage = "解析中"
        try:
            await self._execute(task, options)
        except asyncio.CancelledError:
            task.status = "cancelled"
            task.stage = "已取消"
            task.finished_at = time.time()
            raise
        except UnknownPlatform:
            task.status = "error"
            task.stage = "失败"
            task.error = "暂不支持该平台或未识别到链接"
            task.finished_at = time.time()
        except ParseError as exc:
            task.status = "error"
            task.stage = "失败"
            task.error = str(exc) or "解析失败"
            task.finished_at = time.time()
        except Exception as exc:  # noqa: BLE001
            task.status = "error"
            task.stage = "失败"
            task.error = f"{type(exc).__name__}: {exc}"
            task.finished_at = time.time()
        finally:
            if task.status not in FINISHED_STATUSES:
                task.status = "success"
                task.stage = "完成"
                task.progress = 1.0
            if task.finished_at is None:
                task.finished_at = time.time()
            if self._on_finish is not None:
                self._on_finish(task)

    async def _execute(self, task: Task, options: dict[str, Any]) -> None:
        ph = ParseHub()
        text = options["text"]
        platform = ph.get_platform(text)
        if options.get("use_saved_config", True):
            parse_proxy, download_proxy, cookie = self.bridge.resolve(
                platform.id if platform else None,
                proxy=options.get("proxy"),
                parse_proxy=options.get("parse_proxy"),
                cookie=options.get("cookie"),
                default_proxy=options.get("default_proxy"),
            )
        else:
            parse_proxy = options.get("parse_proxy")
            download_proxy = options.get("proxy")
            cookie = options.get("cookie")
        result = await ph.parse(text, proxy=parse_proxy, cookie=cookie)

        task.payload = result_to_payload(result)
        task.title = result.title or result.name
        task.platform = result.platform.display_name if result.platform else None
        media_items = _as_media_list(getattr(result, "media", None))
        if media_items:
            first = task.payload["media"][0] if task.payload and task.payload.get("media") else None
            if first:
                task.thumbnail = first.get("thumb_url") or first.get("url")

        index = options.get("media_index")
        if index is not None:
            result = _pick_media(result, int(index))
        result = _apply_quality(result, str(options.get("quality") or "origin"))

        task.stage = "下载中"

        async def on_progress(current: int, total: int, unit: str, *args: Any, **kwargs: Any) -> None:
            task.current = int(current)
            task.total = int(total)
            task.unit = str(unit)
            task.progress = (current / total) if total else 0.0
            task.stage = "下载媒体" if unit == "bytes" else f"下载媒体 {current}/{total}"

        download_result = await result.download(
            path=options.get("path"),
            callback=on_progress,
            proxy=download_proxy,
            save_metadata=bool(options.get("save_metadata")),
            connections=int(options.get("connections") or 4),
        )

        files = _as_media_list(download_result.media)
        task.files = [str(f.path) for f in files]
        task.output_dir = str(download_result.output_dir)
        download_errors = list(getattr(download_result, "errors", []) or [])
        if download_errors:
            task.status = "partial"
            task.stage = "部分完成"
            task.error = "；".join(download_errors[:3])
            if len(download_errors) > 3:
                task.error += f" 等 {len(download_errors)} 项失败"
        else:
            task.progress = 1.0
            task.stage = "完成"
        if self._on_success is not None:
            self._on_success(task)
