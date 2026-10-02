import asyncio
import json
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx

from parsehub.errors import DownloadError
from parsehub.parsers.parser.douyin import DouyinParser
from parsehub.types import ImageRef
from parsehub.utils.downloader import SegmentDownloader
from parsehub.utils.media_info import MediaInfoReader
from parsehub.utils.media_network import media_candidates
from parsehub_studio.app import create_app
from parsehub_studio.network import resolve_default_proxy
from parsehub_studio.serialize import media_to_dict


class NetworkRegressionTests(unittest.IsolatedAsyncioTestCase):
    async def test_douyin_stops_at_redirect_containing_id(self):
        visited = []

        def handle(request):
            visited.append(str(request.url))
            if request.url.host != "v.douyin.com":
                raise AssertionError("Must not visit the broken landing page")
            return httpx.Response(302, headers={"location": "https://www.iesdouyin.com/share/video/7691874698522529051/?tracking=remove"})

        client = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        with patch("parsehub.parsers.parser.douyin.httpx.AsyncClient", return_value=client):
            raw = await DouyinParser().get_raw_url("0.00 复制打开抖音，看看作品 https://v.douyin.com/sS04AWo12Jg/ 09/29 usR:/", clean_all=True)
        self.assertEqual(raw, "https://www.douyin.com/video/7691874698522529051")
        self.assertEqual(len(visited), 1)

    async def test_douyin_direct_urls_need_no_network(self):
        with patch("parsehub.parsers.parser.douyin.httpx.AsyncClient") as factory:
            for url in ("https://www.douyin.com/video/123", "https://www.iesdouyin.com/share/video/123/?from=app", "https://www.douyin.com/?modal_id=123"):
                self.assertEqual(await DouyinParser().get_raw_url(url), "https://www.douyin.com/video/123")
            self.assertEqual(await DouyinParser().get_raw_url("https://www.douyin.com/note/456"), "https://www.douyin.com/note/456")
            factory.assert_not_called()

    async def test_weibo_download_rotates_cdn_but_keeps_original(self):
        for status in (403, "connect"):
            visited = []

            def handle(request):
                visited.append(request.url.host)
                self.assertEqual(request.url.path, "/large/test.jpg")
                self.assertEqual(request.headers["referer"], "https://weibo.com/")
                if request.url.host == "wx1.sinaimg.cn":
                    if status == "connect":
                        raise httpx.ConnectError("", request=request)
                    return httpx.Response(403)
                return httpx.Response(200, content=b"original-image", headers={"Content-Length": "14"})

            with tempfile.TemporaryDirectory() as directory:
                target = Path(directory) / "test.jpg"
                downloader = SegmentDownloader("https://wx1.sinaimg.cn/large/test.jpg", target, connections=1)
                with patch.object(downloader, "_client", side_effect=lambda: httpx.AsyncClient(transport=httpx.MockTransport(handle), headers=downloader.headers)), patch("parsehub.utils.downloader.asyncio.sleep", new=AsyncMock()):
                    await downloader.run()
                self.assertEqual(target.read_bytes(), b"original-image")
            self.assertIn("wx2.sinaimg.cn", visited)

    async def test_network_error_names_exception_and_host(self):
        downloader = SegmentDownloader("https://cdn.example/test.jpg", "test.jpg", max_retries=0)
        with patch.object(downloader, "_download_once", new=AsyncMock(side_effect=httpx.ConnectError(""))):
            with self.assertRaisesRegex(DownloadError, "ConnectError, cdn.example"):
                await downloader.run()

    async def test_preview_proxy_uses_same_cdn_failover(self):
        app = create_app()
        app.state.ctx.default_proxy = AsyncMock(return_value=None)

        def handle(request):
            if request.url.host == "wx1.sinaimg.cn":
                raise httpx.ConnectError("", request=request)
            self.assertEqual(request.headers["range"], "bytes=0-3")
            return httpx.Response(206, headers={"content-type": "image/jpeg", "content-range": "bytes 0-3/100"}, content=b"test")

        upstream = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost") as client:
            with patch("parsehub_studio.app.httpx.AsyncClient", return_value=upstream):
                response = await client.get("/api/media", params={"src": "https://wx1.sinaimg.cn/large/test.jpg", "referer": "https://weibo.com/"}, headers={"Range": "bytes=0-3"})
            self.assertEqual(response.status_code, 206)
            self.assertEqual(response.content, b"test")
            self.assertTrue(upstream.is_closed)

    async def test_slow_proxy_lookup_does_not_block_health(self):
        app = create_app()

        def slow_lookup(*args):
            time.sleep(0.3)
            return None

        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost") as client:
            with patch("parsehub_studio.app.resolve_default_proxy", side_effect=slow_lookup) as resolver:
                lookup = asyncio.create_task(app.state.ctx.default_proxy())
                await asyncio.sleep(0.02)
                response = await asyncio.wait_for(client.get("/api/health"), timeout=0.2)
                self.assertEqual(response.status_code, 200)
                await lookup
                await app.state.ctx.default_proxy()
                resolver.assert_called_once()


class MediaRegressionTests(unittest.TestCase):
    def test_hd_preview_preserves_original_and_thumbnail(self):
        media = media_to_dict(ImageRef(url="https://wx1.sinaimg.cn/large/test.jpg", thumb_url="https://wx1.sinaimg.cn/wap180/test.jpg"))
        self.assertIn("/mw1024/", media["preview_url"])
        self.assertIn("/large/", media["url"])
        self.assertIn("/wap180/", media["thumb_url"])
        for url in ("https://evil.sinaimg.cn/large/a.jpg", "https://wx1.sinaimg.cn.evil.test/a.jpg", "https://cdn.example/a.jpg?sig=secret"):
            self.assertEqual(media_candidates(url), [url])

    def test_proxy_selection_never_probes_or_silently_bypasses(self):
        with patch("parsehub_studio.network.socket.create_connection") as probe:
            self.assertEqual(resolve_default_proxy("custom", "127.0.0.1:7890"), "http://127.0.0.1:7890")
            self.assertIsNone(resolve_default_proxy("none", "http://127.0.0.1:7890"))
            probe.assert_not_called()

    def test_video_metadata_uses_system_ffprobe(self):
        data = {"streams": [{"width": 1920, "height": 1080, "duration": "N/A"}], "format": {"duration": "3.2"}}
        with patch("parsehub.utils.media_info.shutil.which", return_value="ffprobe"), patch("parsehub.utils.media_info.subprocess.run", return_value=SimpleNamespace(stdout=json.dumps(data))) as run:
            info = MediaInfoReader.read_video("video.mp4")
            self.assertEqual((info.width, info.height, info.duration), (1920, 1080, 4))
            self.assertEqual(run.call_args.kwargs["timeout"], 10)
        with patch("parsehub.utils.media_info.shutil.which", return_value=None):
            self.assertEqual(MediaInfoReader.read_video("video.mp4").width, 0)


if __name__ == "__main__":
    unittest.main()
