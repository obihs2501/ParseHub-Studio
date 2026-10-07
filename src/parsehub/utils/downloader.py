import asyncio
import json
import math
import os
import re
import shutil
import time
import uuid
from collections.abc import Awaitable, Callable, Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urlparse

import aiofiles
import httpx

from ..errors import DownloadError
from .media_network import media_candidates, media_headers

ProgressCallback = Callable[..., Awaitable[None]]


@dataclass(frozen=True, slots=True)
class RangeProbe:
    supports_range: bool
    total_size: int | None = None
    etag: str | None = None
    last_modified: str | None = None
    content_encoding: str | None = None


@dataclass(frozen=True, slots=True)
class RangePart:
    index: int
    start: int
    end: int
    path: Path

    @property
    def size(self) -> int:
        return self.end - self.start + 1


class FallbackToSingle(Exception):
    """服务端忽略 Range 时回退到普通单连接下载。"""


# 断点身份文件：大文件（分片下载）会在临时目录里留这个，续传前用它确认「还是同一份内容」。
RESUME_META_NAME = "resume.json"

# 断点临时目录的保留上限：超过这段时间没再被写过，就当「放弃的下载」清掉 —— 取消/失败会留下
# 几 GB 分片，没有这个兜底它们会永远占着下载目录（对齐 douyin_browser 的临时 profile 清理）。
STALE_TEMP_SECONDS = 7 * 24 * 3600
TEMP_SUFFIX = ".parsehub-tmp"


class SegmentDownloader:
    def __init__(
        self,
        url: str,
        save_path: str | Path | None = None,
        *,
        candidates: Sequence[str] | None = None,
        headers: Mapping[str, str] | None = None,
        proxy: str | httpx.Proxy | None = None,
        progress: ProgressCallback | None = None,
        progress_args: tuple = (),
        progress_kwargs: dict[str, Any] | None = None,
        max_retries: int = 3,
        chunk_size: int = 64 * 1024,
        connections: int = 4,
        min_split_size: int = 10 * 1024 * 1024,
        timeout: float | httpx.Timeout | None = None,
    ):
        self.url = url
        self.candidates = list(candidates) if candidates else media_candidates(url)
        self.save_path = save_path
        self.headers = dict(httpx.Headers(media_headers(url)))
        self.headers.update(dict(httpx.Headers(headers or {})))
        self.proxy = proxy
        self.progress = progress
        self.progress_args = progress_args
        self.progress_kwargs = progress_kwargs or {}
        self.max_retries = max(0, max_retries)
        self.chunk_size = max(1, chunk_size)
        self.connections = max(1, connections)
        self.min_split_size = max(1, min_split_size)
        self.timeout = timeout if timeout is not None else httpx.Timeout(60.0, connect=10.0)

        self.resolved_path: Path | None = None
        self.temp_dir: Path | None = None
        self.complete_path: Path | None = None
        self._keep_temp_dir = False
        self._progress_lock = asyncio.Lock()
        self._downloaded = 0
        self._part_downloaded: dict[int, int] = {}

    async def run(self) -> str:
        last_error: Exception | None = None
        for attempt in range(self.max_retries + 1):
            self.url = self.candidates[attempt % len(self.candidates)]
            self._reset_progress()
            try:
                async with await asyncio.to_thread(self._client) as client:
                    self.resolved_path = await self._resolve_path(client)
                    await self._download_once(client)
                    return str(self.resolved_path)
            except DownloadError as e:
                last_error = e
                if attempt == self.max_retries:
                    raise
            except httpx.HTTPStatusError as e:
                last_error = e
                retryable = _is_retryable_status(e.response.status_code) or (
                    len(self.candidates) > 1 and e.response.status_code == 403
                )
                if attempt == self.max_retries or not retryable:
                    raise DownloadError(f"HTTP错误: {e.response.status_code}") from e
            except (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError, httpx.ReadError) as e:
                last_error = e
                if attempt == self.max_retries:
                    detail = str(e) or "连接中断或超时"
                    host = urlparse(self.url).hostname
                    raise DownloadError(f"网络连接错误 ({type(e).__name__}, {host}): {detail}") from e
            except Exception as e:
                last_error = e
                if attempt == self.max_retries:
                    raise DownloadError(f"下载失败: {e}") from e

            await asyncio.sleep(2**attempt)

        raise DownloadError(f"达到最大重试次数，下载失败: {last_error}")

    def _client(self) -> httpx.AsyncClient:
        limits = httpx.Limits(
            max_connections=max(self.connections + 2, 10),
            max_keepalive_connections=max(self.connections, 1),
        )
        kwargs: dict[str, Any] = {
            "headers": self.headers,
            "proxy": self.proxy,
            "limits": limits,
            "trust_env": False,
        }
        if self.timeout is not None:
            kwargs["timeout"] = self.timeout
        return httpx.AsyncClient(**kwargs)

    async def _resolve_path(self, client: httpx.AsyncClient) -> Path:
        save_dir, filename = _parse_save_path(self.save_path)
        if not filename:
            filename = await get_filename_by_url(self.url, client)
        if not filename:
            raise DownloadError("无法获取文件名")

        resolved_path = save_dir.joinpath(filename)
        resolved_path.parent.mkdir(parents=True, exist_ok=True)
        return resolved_path

    async def _download_once(self, client: httpx.AsyncClient) -> None:
        resolved_path = self._require_resolved_path()
        probe = await self._probe(client)
        # 只有大文件（走分片下载）才留断点：几 GB 的原画档断一次就重下整份不可接受，
        # 而小文件重下代价低，保持「跑完即清」的老行为。
        resumable = self._should_use_multipart(probe)
        self._prepare_temp_dir(resolved_path, resumable=resumable, probe=probe)
        try:
            if resumable:
                try:
                    await self._download_multipart(client, probe.total_size or 0)
                except FallbackToSingle:
                    resumable = False
                    self._reset_progress()
                    self._prepare_temp_dir(resolved_path, resumable=False, probe=probe, fresh=True)
                    await self._download_single(client, probe.total_size)
            else:
                await self._download_single(client, probe.total_size)

            os.replace(self._require_complete_path(), resolved_path)
        except BaseException:
            # 保留断点，本轮重试（或下次调用）接着下
            self._keep_temp_dir = resumable
            raise
        finally:
            if not self._keep_temp_dir:
                self._cleanup_temp_dir()
            self._keep_temp_dir = False

    async def _probe(self, client: httpx.AsyncClient) -> RangeProbe:
        total_size: int | None = None
        etag: str | None = None
        last_modified: str | None = None
        content_encoding: str | None = None

        try:
            response = await client.head(
                self.url,
                headers=self._headers({"Accept-Encoding": "identity"}),
                follow_redirects=True,
            )
            if response.status_code < 400:
                total_size = _parse_int(response.headers.get("Content-Length"))
                etag = response.headers.get("ETag")
                last_modified = response.headers.get("Last-Modified")
                content_encoding = response.headers.get("Content-Encoding")
        except httpx.HTTPError:
            pass

        if self.connections <= 1 or (total_size is not None and total_size < self.min_split_size):
            return RangeProbe(False, total_size, etag, last_modified, content_encoding)
        if _has_non_identity_encoding(content_encoding):
            return RangeProbe(False, total_size, etag, last_modified, content_encoding)

        try:
            response = await client.get(
                self.url,
                headers=self._headers({"Accept-Encoding": "identity", "Range": "bytes=0-0"}),
                follow_redirects=True,
            )
        except httpx.HTTPError:
            return RangeProbe(False, total_size, etag, last_modified, content_encoding)

        response_encoding = response.headers.get("Content-Encoding")
        if _has_non_identity_encoding(response_encoding):
            return RangeProbe(False, total_size, etag, last_modified, response_encoding)

        if response.status_code == 206:
            parsed_range = _parse_content_range(response.headers.get("Content-Range", ""))
            if parsed_range:
                start, end, range_total = parsed_range
                if start == 0 and end == 0 and range_total and range_total > 0:
                    return RangeProbe(True, range_total, etag, last_modified, response_encoding)
        if response.status_code == 200:
            probed_size = _parse_int(response.headers.get("Content-Length"))
            return RangeProbe(False, probed_size or total_size, etag, last_modified, response_encoding)
        if response.status_code == 416:
            parsed_range = _parse_content_range(response.headers.get("Content-Range", ""))
            range_total = parsed_range[2] if parsed_range else total_size
            return RangeProbe(False, range_total, etag, last_modified, response_encoding)

        return RangeProbe(False, total_size, etag, last_modified, response_encoding or content_encoding)

    async def _download_single(self, client: httpx.AsyncClient, total_size: int | None) -> None:
        complete_path = self._require_complete_path()
        async with client.stream(
            "GET",
            self.url,
            headers=self._headers({"Accept-Encoding": "identity"}),
            follow_redirects=True,
        ) as response:
            response.raise_for_status()
            content_encoding = response.headers.get("Content-Encoding")
            response_total = _parse_int(response.headers.get("Content-Length"))
            expected_size = response_total if not _has_non_identity_encoding(content_encoding) else None
            total = expected_size or total_size or 0
            current = 0

            async with aiofiles.open(complete_path, "wb") as f:
                async for chunk in response.aiter_bytes(chunk_size=self.chunk_size):
                    if not chunk:
                        continue
                    await f.write(chunk)
                    current += len(chunk)
                    await self._report_single(current, total)

        if expected_size is not None and current != expected_size:
            raise DownloadError(f"下载不完整: 期望 {expected_size} 字节, 实际 {current} 字节")
        await self._report_finish(total)

    async def _download_multipart(self, client: httpx.AsyncClient, total_size: int) -> None:
        temp_dir = self._require_temp_dir()
        parts_dir = temp_dir.joinpath("parts")
        parts_dir.mkdir(parents=True, exist_ok=True)
        parts = self._build_parts(total_size, parts_dir)
        # 断点续传：已经下完的分片直接跳过，并计入进度（进度条不从 0 重来）
        pending: list[RangePart] = []
        for part in parts:
            if self._part_is_complete(part):
                self._part_downloaded[part.index] = part.size
                self._downloaded += part.size
            else:
                pending.append(part)
        await self._report_resumed(total_size)
        tasks = [asyncio.create_task(self._download_part(client, part, total_size)) for part in pending]

        try:
            await asyncio.gather(*tasks)
        except BaseException:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            raise

        await self._merge_parts(parts, total_size)
        await self._report_finish(total_size)

    @staticmethod
    def _part_is_complete(part: RangePart) -> bool:
        try:
            return part.path.stat().st_size == part.size
        except OSError:
            return False

    async def _download_part(self, client: httpx.AsyncClient, part: RangePart, total_size: int) -> None:
        for attempt in range(self.max_retries + 1):
            try:
                # 续传：这一片上次写到一半就从那里接着下（起点由服务端回显校验）
                have = part.path.stat().st_size if part.path.exists() else 0
                if have >= part.size:
                    have = 0
                    part.path.unlink(missing_ok=True)
                start = part.start + have
                received = have
                async with client.stream(
                    "GET",
                    self.url,
                    headers=self._headers({"Accept-Encoding": "identity", "Range": f"bytes={start}-{part.end}"}),
                    follow_redirects=True,
                ) as response:
                    if response.status_code == 200:
                        raise FallbackToSingle
                    response.raise_for_status()
                    if response.status_code != 206:
                        raise DownloadError(f"分片下载失败: HTTP {response.status_code}")
                    if _has_non_identity_encoding(response.headers.get("Content-Encoding")):
                        raise FallbackToSingle
                    self._validate_part_response(part, response.headers, total_size, expected_start=start)

                    async with aiofiles.open(part.path, "ab" if have else "wb") as f:
                        async for chunk in response.aiter_bytes(chunk_size=self.chunk_size):
                            if not chunk:
                                continue
                            await f.write(chunk)
                            received += len(chunk)
                            await self._report_part(part.index, received, total_size)

                if received != part.size:
                    raise DownloadError(f"分片大小不匹配: 期望 {part.size} 字节, 实际 {received} 字节")
                return
            except FallbackToSingle:
                raise
            except httpx.HTTPStatusError as e:
                if attempt == self.max_retries or not _is_retryable_status(e.response.status_code):
                    raise DownloadError(f"分片下载失败: HTTP {e.response.status_code}") from e
            except (httpx.TimeoutException, httpx.NetworkError, httpx.RemoteProtocolError, httpx.ReadError) as e:
                if attempt == self.max_retries:
                    raise DownloadError(f"分片网络错误: {e}") from e
            except DownloadError:
                if attempt == self.max_retries:
                    raise
            await asyncio.sleep(2**attempt)

    async def _merge_parts(self, parts: list[RangePart], total_size: int) -> None:
        complete_path = self._require_complete_path()
        async with aiofiles.open(complete_path, "wb") as target:
            for part in parts:
                if not part.path.exists() or part.path.stat().st_size != part.size:
                    actual = part.path.stat().st_size if part.path.exists() else 0
                    raise DownloadError(f"分片文件不完整: 期望 {part.size} 字节, 实际 {actual} 字节")
                async with aiofiles.open(part.path, "rb") as source:
                    while chunk := await source.read(self.chunk_size):
                        await target.write(chunk)

        actual_size = complete_path.stat().st_size
        if actual_size != total_size:
            raise DownloadError(f"合并后文件大小不匹配: 期望 {total_size} 字节, 实际 {actual_size} 字节")

    def _part_count(self, total_size: int) -> int:
        """分片数量。

        **必须和断点身份一起记录**：连接数变了分片边界就跟着变，旧断点的 ``NNNNNN.part``
        会被当成「另一段范围」的半成品接着写 —— 长度检查全过、内容却是坏的（静默损坏）。
        """
        if total_size <= 0:
            return 1
        return max(1, min(self.connections, math.ceil(total_size / self.min_split_size)))

    def _build_parts(self, total_size: int, parts_dir: Path) -> list[RangePart]:
        part_count = self._part_count(total_size)
        part_size = math.ceil(total_size / part_count)
        parts = []
        for index in range(part_count):
            start = index * part_size
            end = min(start + part_size - 1, total_size - 1)
            parts.append(RangePart(index=index, start=start, end=end, path=parts_dir.joinpath(f"{index:06d}.part")))
        return parts

    def _validate_part_response(
        self, part: RangePart, headers: httpx.Headers, total_size: int, *, expected_start: int | None = None
    ) -> None:
        parsed_range = _parse_content_range(headers.get("Content-Range", ""))
        if not parsed_range:
            raise DownloadError("分片响应缺少 Content-Range")
        start, end, response_total = parsed_range
        want_start = part.start if expected_start is None else expected_start
        if start != want_start or end != part.end:
            raise DownloadError(f"分片范围不匹配: 期望 {want_start}-{part.end}, 实际 {start}-{end}")
        if response_total != total_size:
            raise DownloadError(f"远端文件大小变化: 期望 {total_size}, 实际 {response_total}")

    def _should_use_multipart(self, probe: RangeProbe) -> bool:
        return (
            self.connections > 1
            and probe.supports_range
            and probe.total_size is not None
            and probe.total_size > 0
            and probe.total_size >= self.min_split_size
            and not _has_non_identity_encoding(probe.content_encoding)
        )

    def _prepare_temp_dir(
        self, resolved_path: Path, *, resumable: bool, probe: RangeProbe, fresh: bool = False
    ) -> None:
        """准备临时目录。

        ``resumable`` 时用固定目录名（``.<文件名>.parsehub-tmp``）并保留断点，供续传复用；
        否则沿用「随机名 + 用完即清」的老行为。复用前会用 ``probe`` 校验断点身份
        （大小 / ETag / Last-Modified），不是同一份内容就丢弃重下。
        """
        if fresh and self.temp_dir is not None:
            shutil.rmtree(self.temp_dir, ignore_errors=True)
            self.temp_dir = None
        self.complete_path = None
        self._cleanup_stale_temp_dirs(resolved_path.parent)
        if resumable:
            target = resolved_path.parent.joinpath(f".{resolved_path.name}{TEMP_SUFFIX}")
            if target.exists() and not self._resume_identity_matches(target, probe):
                shutil.rmtree(target, ignore_errors=True)
            target.mkdir(parents=True, exist_ok=True)
            self.temp_dir = target
            self._write_resume_meta(target, probe)
        else:
            if self.temp_dir is not None:
                shutil.rmtree(self.temp_dir, ignore_errors=True)
            self.temp_dir = resolved_path.parent.joinpath(f".{resolved_path.name}.{uuid.uuid4().hex}{TEMP_SUFFIX}")
            self.temp_dir.mkdir(parents=True, exist_ok=False)
        self.complete_path = self.temp_dir.joinpath("complete.tmp")

    def _write_resume_meta(self, temp_dir: Path, probe: RangeProbe) -> None:
        meta = {
            "total": probe.total_size,
            "etag": probe.etag,
            "last_modified": probe.last_modified,
            # 分片布局也要记：连接数变了边界就变，旧分片不能当半成品接着写
            "parts": self._part_count(probe.total_size or 0),
        }
        try:
            temp_dir.joinpath(RESUME_META_NAME).write_text(json.dumps(meta), encoding="utf-8")
        except OSError:
            pass

    def _resume_identity_matches(self, temp_dir: Path, probe: RangeProbe) -> bool:
        """断点是不是同一份内容（且分片布局一致）：全对得上才敢续传。"""
        try:
            meta = json.loads(temp_dir.joinpath(RESUME_META_NAME).read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return False
        return (
            meta.get("total") == probe.total_size
            and meta.get("etag") == probe.etag
            and meta.get("last_modified") == probe.last_modified
            and meta.get("parts") == self._part_count(probe.total_size or 0)
        )

    def _cleanup_stale_temp_dirs(self, parent: Path) -> None:
        """清掉同目录下很久没动过的断点临时目录。

        正在下载的目录 mtime 是新的，不会被误删；只有被放弃（取消 / 重试用尽）且放置超过
        ``STALE_TEMP_SECONDS`` 的才会回收。
        """
        now = time.time()
        try:
            candidates = list(parent.glob(f".*{TEMP_SUFFIX}"))
        except OSError:
            return
        for path in candidates:
            if path == self.temp_dir:
                continue
            try:
                if now - path.stat().st_mtime < STALE_TEMP_SECONDS:
                    continue
            except OSError:
                continue
            shutil.rmtree(path, ignore_errors=True)

    def _cleanup_temp_dir(self) -> None:
        if self.temp_dir:
            shutil.rmtree(self.temp_dir, ignore_errors=True)
        self.temp_dir = None
        self.complete_path = None

    async def _report_part(self, index: int, downloaded: int, total: int) -> None:
        if not self.progress:
            return
        async with self._progress_lock:
            previous = self._part_downloaded.get(index, 0)
            if downloaded <= previous:
                return
            self._part_downloaded[index] = downloaded
            self._downloaded += downloaded - previous
            await self.progress(self._downloaded, total, *self.progress_args, **self.progress_kwargs)

    async def _report_single(self, downloaded: int, total: int) -> None:
        if not self.progress:
            return
        async with self._progress_lock:
            if downloaded <= self._downloaded:
                return
            self._downloaded = downloaded
            await self.progress(downloaded, total, *self.progress_args, **self.progress_kwargs)

    async def _report_resumed(self, total: int) -> None:
        """续传时先把「已下完的部分」报给 UI，否则进度条会从 0 重新爬。"""
        if not self.progress or self._downloaded <= 0 or total <= 0:
            return
        async with self._progress_lock:
            await self.progress(self._downloaded, total, *self.progress_args, **self.progress_kwargs)

    async def _report_finish(self, total: int) -> None:
        if not self.progress or total <= 0:
            return
        async with self._progress_lock:
            if self._downloaded >= total:
                return
            self._downloaded = total
            await self.progress(total, total, *self.progress_args, **self.progress_kwargs)

    def _headers(self, extra: Mapping[str, str]) -> dict[str, str]:
        merged = dict(self.headers)
        merged.update(extra)
        return merged

    def _reset_progress(self) -> None:
        self._downloaded = 0
        self._part_downloaded.clear()

    def _require_resolved_path(self) -> Path:
        if self.resolved_path is None:
            raise DownloadError("下载路径尚未初始化")
        return self.resolved_path

    def _require_temp_dir(self) -> Path:
        if self.temp_dir is None:
            raise DownloadError("临时目录尚未初始化")
        return self.temp_dir

    def _require_complete_path(self) -> Path:
        if self.complete_path is None:
            raise DownloadError("临时文件尚未初始化")
        return self.complete_path


async def download(
    url: str,
    save_path: str | Path | None = None,
    *,
    candidates: Sequence[str] | None = None,
    headers: dict[str, str] | None = None,
    proxy: str | httpx.Proxy | None = None,
    progress: ProgressCallback | None = None,
    progress_args: tuple = (),
    progress_kwargs: dict[str, Any] | None = None,
    max_retries: int = 3,
    chunk_size: int = 64 * 1024,
    connections: int = 4,
    min_split_size: int = 10 * 1024 * 1024,
    timeout: float | httpx.Timeout | None = None,
) -> str:
    """
    下载单个文件。服务端支持 Range 时使用多连接分片下载；不支持时回退普通单连接下载。

    :param url: 下载链接
    :param save_path: 保存路径, 默认保存到 downloads 文件夹, 如果路径以 / 结尾，则自动获取文件名
    :param candidates: 同一份内容的其它落点，按顺序在重试时切换（默认只有 url 本身）
    :param headers: 请求头
    :param proxy: 代理
    :param progress: 下载进度回调函数
    :param progress_args: 下载进度回调函数的参数
    :param progress_kwargs: 下载进度回调函数的关键字参数
    :param max_retries: 最大重试次数
    :param chunk_size: 分块大小
    :param connections: 单文件最大并发连接数，1 表示禁用分片
    :param min_split_size: 文件小于该值时不分片
    :param timeout: httpx 超时配置
    :return: 文件路径

    .. note::
        下载进度回调函数签名: async def progress(current: int, total: int, *args, **kwargs) -> None:
    """
    downloader = SegmentDownloader(
        url,
        save_path,
        candidates=candidates,
        headers=headers,
        proxy=proxy,
        progress=progress,
        progress_args=progress_args,
        progress_kwargs=progress_kwargs,
        max_retries=max_retries,
        chunk_size=chunk_size,
        connections=connections,
        min_split_size=min_split_size,
        timeout=timeout,
    )
    return await downloader.run()


async def get_filename_by_url(url: str, client: httpx.AsyncClient) -> str | None:
    """从 URL 或 HTTP 响应头中获取文件名"""
    try:
        response = await client.head(url, follow_redirects=True)
        response.raise_for_status()
    except httpx.HTTPError:
        pass
    else:
        if content_disposition := response.headers.get("content-disposition"):
            if filename := _parse_content_disposition(content_disposition):
                return _sanitize_filename(filename)

    parsed = urlparse(url)
    path = unquote(parsed.path).removesuffix("/")
    filename = path.split("/")[-1] if path else None
    return _sanitize_filename(filename) if filename else None


def _parse_save_path(save_path: str | Path | None) -> tuple[Path, str | None]:
    """解析保存路径，返回 (目录, 文件名或 None)"""
    if not save_path:
        return Path.cwd().joinpath("downloads"), None

    save_path_str = str(save_path)
    save_dir_str, filename = os.path.split(save_path_str)
    save_dir = Path(os.path.abspath(save_dir_str)) if save_dir_str else Path.cwd().joinpath("downloads")
    return save_dir, filename if filename else None


def _parse_int(value: str | None) -> int | None:
    if not value:
        return None
    try:
        parsed = int(value)
    except ValueError:
        return None
    return parsed if parsed >= 0 else None


def _parse_content_range(header: str) -> tuple[int | None, int | None, int | None] | None:
    if match := re.fullmatch(r"bytes\s+(\d+)-(\d+)/(\d+|\*)", header.strip(), flags=re.IGNORECASE):
        start = int(match.group(1))
        end = int(match.group(2))
        total = None if match.group(3) == "*" else int(match.group(3))
        return start, end, total
    if match := re.fullmatch(r"bytes\s+\*/(\d+|\*)", header.strip(), flags=re.IGNORECASE):
        total = None if match.group(1) == "*" else int(match.group(1))
        return None, None, total
    return None


def _has_non_identity_encoding(content_encoding: str | None) -> bool:
    if not content_encoding:
        return False
    return content_encoding.lower().strip() not in {"identity", ""}


def _is_retryable_status(status_code: int) -> bool:
    return status_code == 429 or 500 <= status_code < 600


def _parse_content_disposition(header: str) -> str | None:
    """解析 Content-Disposition 头中的文件名，支持 filename*= 和带引号的 filename="""
    if match := re.search(r"filename\*\s*=\s*[\w-]+'[^']*'(.+?)(?:;|$)", header):
        return unquote(match.group(1).strip())

    if match := re.search(r'filename\s*=\s*"([^"]+)"', header):
        return match.group(1).strip()

    if match := re.search(r"filename\s*=\s*([^\s;]+)", header):
        return match.group(1).strip()

    return None


def _sanitize_filename(filename: str) -> str:
    """清理文件名中不安全的字符"""
    filename = re.sub(r'[<>:"/\\|?*]', "_", filename)
    return filename[:255] if filename else filename
