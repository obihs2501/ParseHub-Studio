import contextlib
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import ClassVar

from parsehub.errors import DownloadError
from parsehub.utils.downloader import download


class RangeTestHandler(BaseHTTPRequestHandler):
    content: ClassVar[bytes] = b""
    support_range: ClassVar[bool] = True
    fail_all: ClassVar[bool] = False
    fail_part_ranges: ClassVar[bool] = False
    requests: ClassVar[list[tuple[str, str | None]]] = []

    def log_message(self, format: str, *args: object) -> None:
        return

    def do_HEAD(self) -> None:
        self.__class__.requests.append(("HEAD", self.headers.get("Range")))
        if self.fail_all:
            self.send_response(500)
            self.end_headers()
            return

        self.send_response(200)
        self.send_header("Content-Length", str(len(self.content)))
        self.send_header("Accept-Ranges", "bytes" if self.support_range else "none")
        self.end_headers()

    def do_GET(self) -> None:
        range_header = self.headers.get("Range")
        self.__class__.requests.append(("GET", range_header))
        if self.fail_all:
            self.send_response(500)
            self.end_headers()
            return

        if self.fail_part_ranges and range_header and range_header != "bytes=0-0":
            # 探针（bytes=0-0）照常成功，真正的分片 GET 全失败 —— 用来验证「断点被保留」
            self.send_response(500)
            self.end_headers()
            return

        if range_header and self.support_range:
            start, end = self._parse_range(range_header)
            if start >= len(self.content):
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{len(self.content)}")
                self.end_headers()
                return

            end = min(end, len(self.content) - 1)
            body = self.content[start : end + 1]
            self.send_response(206)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Content-Range", f"bytes {start}-{end}/{len(self.content)}")
            self.send_header("Accept-Ranges", "bytes")
            self.end_headers()
            self.wfile.write(body)
            return

        self.send_response(200)
        self.send_header("Content-Length", str(len(self.content)))
        self.send_header("Accept-Ranges", "none")
        self.end_headers()
        self.wfile.write(self.content)

    @staticmethod
    def _parse_range(header: str) -> tuple[int, int]:
        prefix = "bytes="
        if not header.startswith(prefix):
            return 0, 0
        start_text, end_text = header.removeprefix(prefix).split("-", 1)
        return int(start_text), int(end_text)


@contextlib.contextmanager
def range_server(*, content: bytes, support_range: bool = True, fail_all: bool = False, fail_part_ranges: bool = False):
    class Handler(RangeTestHandler):
        pass

    Handler.content = content
    Handler.support_range = support_range
    Handler.fail_all = fail_all
    Handler.fail_part_ranges = fail_part_ranges
    Handler.requests = []

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"http://127.0.0.1:{server.server_port}/file.bin", Handler
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


class DownloaderTest(unittest.IsolatedAsyncioTestCase):
    async def test_download_uses_range_parts_when_server_supports_range(self):
        content = bytes(range(251)) * 20
        progresses: list[tuple[int, int]] = []

        async def progress(current: int, total: int) -> None:
            progresses.append((current, total))

        with TemporaryDirectory() as tmp, range_server(content=content, support_range=True) as (url, handler):
            target = Path(tmp) / "video.bin"

            path = await download(
                url,
                target,
                progress=progress,
                connections=4,
                min_split_size=512,
                chunk_size=128,
            )

            self.assertEqual(Path(path), target)
            self.assertEqual(target.read_bytes(), content)
            self.assertEqual(progresses[-1], (len(content), len(content)))
            range_gets = [range_header for method, range_header in handler.requests if method == "GET" and range_header]
            self.assertGreaterEqual(len(range_gets), 2)
            self.assertFalse(list(Path(tmp).glob(".*.parsehub-tmp")))

    async def test_download_falls_back_to_single_request_when_range_is_ignored(self):
        content = b"fallback-body" * 100

        with TemporaryDirectory() as tmp, range_server(content=content, support_range=False) as (url, handler):
            target = Path(tmp) / "image.bin"

            await download(url, target, connections=4, min_split_size=10, chunk_size=32)

            self.assertEqual(target.read_bytes(), content)
            self.assertIn(("GET", "bytes=0-0"), handler.requests)
            self.assertIn(("GET", None), handler.requests)
            self.assertFalse(list(Path(tmp).glob(".*.parsehub-tmp")))

    async def test_download_keeps_existing_file_when_request_fails(self):
        with TemporaryDirectory() as tmp, range_server(content=b"new", support_range=True, fail_all=True) as (url, _):
            target = Path(tmp) / "video.bin"
            target.write_bytes(b"old")

            with self.assertRaises(DownloadError):
                await download(url, target, connections=4, max_retries=0)

            self.assertEqual(target.read_bytes(), b"old")
            self.assertFalse(list(Path(tmp).glob(".*.parsehub-tmp")))

    async def test_connections_one_uses_single_request(self):
        content = b"single" * 200

        with TemporaryDirectory() as tmp, range_server(content=content, support_range=True) as (url, handler):
            target = Path(tmp) / "single.bin"

            await download(url, target, connections=1, min_split_size=10)

            self.assertEqual(target.read_bytes(), content)
            range_gets = [range_header for method, range_header in handler.requests if method == "GET" and range_header]
            self.assertEqual(range_gets, [])

    async def test_resumes_completed_parts_and_skips_their_ranges(self):
        # 大文件断点续传：上次已下完的分片不该重下（几 GB 的原画档断一次重下整份不可接受）
        content = bytes(range(256)) * 16  # 4096 字节 → 4 片 × 1024
        with TemporaryDirectory() as tmp, range_server(content=content, support_range=True) as (url, handler):
            target = Path(tmp) / "big.bin"
            temp = Path(tmp) / f".{target.name}.parsehub-tmp"
            parts = temp.joinpath("parts")
            parts.mkdir(parents=True)
            temp.joinpath("resume.json").write_text(
                json.dumps({"total": len(content), "etag": None, "last_modified": None, "parts": 4}),
                encoding="utf-8",
            )
            for index in range(3):  # 假装前三片上次已经下完
                parts.joinpath(f"{index:06d}.part").write_bytes(content[index * 1024 : (index + 1) * 1024])

            progresses: list[tuple[int, int]] = []

            async def progress(current: int, total: int) -> None:
                progresses.append((current, total))

            await download(
                url,
                target,
                progress=progress,
                connections=4,
                min_split_size=512,
                chunk_size=256,
            )

            self.assertEqual(target.read_bytes(), content)
            ranges = [range_header for method, range_header in handler.requests if method == "GET" and range_header]
            self.assertNotIn("bytes=0-1023", ranges)
            self.assertNotIn("bytes=1024-2047", ranges)
            self.assertNotIn("bytes=2048-3071", ranges)
            self.assertIn("bytes=3072-4095", ranges)
            # 续传先把已下完的 3072 字节报出来，进度不从 0 重新爬
            self.assertEqual(progresses[0], (3072, len(content)))
            self.assertEqual(progresses[-1], (len(content), len(content)))
            self.assertFalse(list(Path(tmp).glob(".*.parsehub-tmp")))

    async def test_resumes_partial_part_from_its_offset(self):
        content = bytes(range(256)) * 16
        with TemporaryDirectory() as tmp, range_server(content=content, support_range=True) as (url, handler):
            target = Path(tmp) / "big.bin"
            temp = Path(tmp) / f".{target.name}.parsehub-tmp"
            parts = temp.joinpath("parts")
            parts.mkdir(parents=True)
            temp.joinpath("resume.json").write_text(
                json.dumps({"total": len(content), "etag": None, "last_modified": None, "parts": 4}),
                encoding="utf-8",
            )
            parts.joinpath("000000.part").write_bytes(content[:400])  # 第 1 片上次写到 400 字节

            await download(url, target, connections=4, min_split_size=512, chunk_size=256)

            self.assertEqual(target.read_bytes(), content)
            ranges = [range_header for method, range_header in handler.requests if method == "GET" and range_header]
            self.assertIn("bytes=400-1023", ranges)

    async def test_discards_stale_parts_when_content_changed(self):
        # 同名目标但断点是另一份内容（大小对不上）→ 必须丢弃重下，不能把旧字节拼进来
        content = bytes(range(256)) * 16
        with TemporaryDirectory() as tmp, range_server(content=content, support_range=True) as (url, _):
            target = Path(tmp) / "big.bin"
            temp = Path(tmp) / f".{target.name}.parsehub-tmp"
            parts = temp.joinpath("parts")
            parts.mkdir(parents=True)
            temp.joinpath("resume.json").write_text(
                json.dumps({"total": 999, "etag": None, "last_modified": None}), encoding="utf-8"
            )
            parts.joinpath("000000.part").write_bytes(b"x" * 1024)

            await download(url, target, connections=4, min_split_size=512, chunk_size=256)

            self.assertEqual(target.read_bytes(), content)
            self.assertFalse(list(Path(tmp).glob(".*.parsehub-tmp")))

    async def test_failed_large_download_keeps_parts_for_resume(self):
        # 探针成功（支持 Range）但分片 GET 全失败 → 这是**可续传**路径，断点目录必须留下来。
        # 不能用 fail_all：那样连 HEAD 都失败，_probe 判定不支持 Range，走的是不可续传的路径
        # （临时目录照旧删掉），测试就成了空跑（早先就是这么写的，等于没测）。
        content = b"payload" * 2000
        with TemporaryDirectory() as tmp, range_server(
            content=content, support_range=True, fail_part_ranges=True
        ) as (url, _):
            target = Path(tmp) / "big.bin"

            with self.assertRaises(DownloadError):
                await download(url, target, connections=4, min_split_size=512, max_retries=0)

            self.assertFalse(target.exists())
            kept = list(Path(tmp).glob(".*.parsehub-tmp"))
            self.assertEqual(len(kept), 1)
            self.assertTrue(kept[0].joinpath("resume.json").exists())

    async def test_falls_back_to_next_candidate_url(self):
        # 抖音原画档有多条 CDN 线路：主线路连不上时要能换下一条
        content = b"payload" * 500
        with TemporaryDirectory() as tmp, range_server(content=content, support_range=True) as (url, handler):
            target = Path(tmp) / "c.bin"
            dead = "http://127.0.0.1:9/dead.bin"

            await download(
                dead,
                target,
                candidates=[dead, url],
                connections=2,
                min_split_size=10,
                max_retries=1,
            )

            self.assertEqual(target.read_bytes(), content)
            self.assertTrue(any(method == "GET" for method, _ in handler.requests))


if __name__ == "__main__":
    unittest.main()
