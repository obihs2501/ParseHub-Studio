"""抖音「真·原画」解析的离线测试（不联网、不启动浏览器）。

``httpx.AsyncClient`` 换成 ``MockTransport``：``/aweme/v1/play/`` 按 ``line`` 返回 302
落点，落点上的 ``Range: bytes=0-0`` 返回 206 + ``Content-Range``（就是真实 CDN 的行为）。
"""

import os
import unittest
from unittest.mock import patch

import httpx

from parsehub.parsers.parser.douyin import parse_video_info
from parsehub.provider_api import douyin_origin
from parsehub.provider_api.douyin_origin import (
    enhance_with_origin,
    origin_dimensions,
    origin_kind,
    pick_origin_uri,
    resolve_origin,
)

PLAY_PATH = "/aweme/v1/play/"

# 落点里带桶名（真实 CDN 路径形如 .../tos-cn-v-0015c000-ce/xxx）。
ORIGIN_URL = "https://v3-web.douyinvod.com/tos-cn-v-0015c000/master.mp4"
ORIGIN_URL_2 = "https://v5-web.douyinvod.com/tos-cn-v-0015c000/master.mp4"
EIGHT_K_URL = "https://v9-web.douyinvod.com/tos-cn-v-0015c800/master.mp4"
DEAD_URL = "https://cold-web.douyinvod.com/dead/master.mp4"

URI = "v0300fg10000abcdef"

MASTER_SIZE = 1610612736  # 1.5 GiB


class _Recorder:
    """MockTransport 的处理器 + 请求记录（断言「没联网」时用）。"""

    def __init__(
        self, locations: dict[int, str], sizes: dict[str, int], error_bodies: dict[str, int] | None = None
    ) -> None:
        self.locations = locations
        self.sizes = sizes
        self.error_bodies = error_bodies or {}
        self.requests: list[str] = []

    def __call__(self, request: httpx.Request) -> httpx.Response:
        self.requests.append(str(request.url))
        if request.url.path == PLAY_PATH:
            line = int(request.url.params.get("line", "0"))
            target = self.locations.get(line)
            if not target:
                return httpx.Response(404)
            return httpx.Response(302, headers={"Location": target})
        if (error_size := self.error_bodies.get(str(request.url))) is not None:
            # 坏线路返回错误页（带 Content-Length）：不能把这个长度当成文件体积
            return httpx.Response(500, content=b"x" * error_size)
        size = self.sizes.get(str(request.url))
        if size is None:
            return httpx.Response(500)
        return httpx.Response(206, headers={"Content-Range": f"bytes 0-0/{size}"})


def _use(recorder: _Recorder):
    """把模块内的 httpx 客户端换成 MockTransport。"""
    transport = httpx.MockTransport(recorder)

    def fake_client(proxy: str | None, timeout: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(transport=transport, trust_env=False, timeout=timeout)

    return patch.object(douyin_origin, "_client", fake_client)


def _video(width: int = 720, height: int = 1280, duration: int = 12800) -> dict:
    """移动端接口常见返回：顶层低清预览 + 一条 720P 档。"""
    return {
        "play_addr": {
            "uri": URI,
            "url_list": ["https://v3-web.douyinvod.com/low.mp4"],
            "width": 576,
            "height": 1024,
            "data_size": 5400000,
        },
        "bit_rate": [
            {
                "gear_name": "720p",
                "bit_rate": 1600000,
                "is_bytevc1": 0,
                "play_addr": {
                    "uri": URI,
                    "url_list": ["https://v3-web.douyinvod.com/720.mp4"],
                    "width": width,
                    "height": height,
                    "data_size": 8000000,
                },
            }
        ],
        "duration": duration,
    }


def _payload(video: dict | None = None) -> dict:
    return {"aweme_detail": {"aweme_id": "7300000000000000000", "desc": "desc", "video": video or _video()}}


class OriginKindTests(unittest.TestCase):
    def test_maps_buckets(self):
        self.assertEqual(origin_kind(EIGHT_K_URL), "8K")
        self.assertEqual(origin_kind(ORIGIN_URL), "原画")
        self.assertIsNone(origin_kind("https://v3-web.douyinvod.com/tos-cn-ve-0015c000/x.mp4"))
        self.assertIsNone(origin_kind(""))

    def test_8k_dimensions_keep_orientation(self):
        # 竖屏原画不能被压成横屏 7680x4320（早先直接 max() 会翻掉方向）
        self.assertEqual(origin_dimensions("8K", 2160, 3840), (4320, 7680))
        self.assertEqual(origin_dimensions("8K", 3840, 2160), (7680, 4320))
        # 非 8K：分辨率未知，沿用当前最高档
        self.assertEqual(origin_dimensions("原画", 2160, 3840), (2160, 3840))

    def test_pick_uri_prefers_play_addr_then_highest_bitrate(self):
        self.assertEqual(pick_origin_uri(_video()), URI)
        self.assertEqual(
            pick_origin_uri({
                "bit_rate": [
                    {"bit_rate": 100, "play_addr": {"uri": "low_uri_1234"}},
                    {"bit_rate": 900, "play_addr": {"uri": "high_uri_5678"}},
                ]
            }),
            "high_uri_5678",
        )
        self.assertEqual(pick_origin_uri({}), "")


class ResolveOriginTests(unittest.IsolatedAsyncioTestCase):
    async def test_collects_unique_lines_and_probes_size(self):
        recorder = _Recorder(
            {0: ORIGIN_URL, 2: ORIGIN_URL_2, 5: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE, ORIGIN_URL_2: MASTER_SIZE}
        )
        with _use(recorder):
            urls, size = await resolve_origin(URI)
        # line 5 与 line 0 同一落点 → 去重；顺序保持 line 顺序
        self.assertEqual(urls, [ORIGIN_URL, ORIGIN_URL_2])
        self.assertEqual(size, MASTER_SIZE)

    async def test_working_line_is_ordered_before_dead_one(self):
        # line 0 的落点探不通（500）→ 应当排到探得通的 line 2 后面
        recorder = _Recorder({0: DEAD_URL, 2: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE})
        with _use(recorder):
            urls, size = await resolve_origin(URI)
        self.assertEqual(urls, [ORIGIN_URL, DEAD_URL])
        self.assertEqual(size, MASTER_SIZE)

    async def test_error_response_body_is_not_mistaken_for_file_size(self):
        # 坏线路回 500 + 错误页（带 Content-Length）：不能把它当体积，否则这条线路会被排到
        # 探得通的线路前面，芯片上还显示一个假体积。
        recorder = _Recorder({0: DEAD_URL, 2: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE}, error_bodies={DEAD_URL: 512})
        with _use(recorder):
            urls, size = await resolve_origin(URI)
        self.assertEqual(urls, [ORIGIN_URL, DEAD_URL])
        self.assertEqual(size, MASTER_SIZE)

    async def test_all_lines_dead_keeps_urls_without_size(self):
        recorder = _Recorder({0: DEAD_URL}, {})
        with _use(recorder):
            urls, size = await resolve_origin(URI)
        self.assertEqual(urls, [DEAD_URL])
        self.assertEqual(size, 0)

    async def test_invalid_uri_does_not_hit_network(self):
        recorder = _Recorder({0: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE})
        with _use(recorder):
            self.assertEqual(await resolve_origin("short"), ([], 0))
            self.assertEqual(await resolve_origin(""), ([], 0))
        self.assertEqual(recorder.requests, [])

    async def test_no_redirect_returns_empty(self):
        recorder = _Recorder({}, {})
        with _use(recorder):
            self.assertEqual(await resolve_origin(URI), ([], 0))


class EnhanceWithOriginTests(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        os.environ.pop(douyin_origin.ORIGIN_ENV, None)
        douyin_origin._cache.clear()
        self.addCleanup(lambda: os.environ.pop(douyin_origin.ORIGIN_ENV, None))

    async def test_inserts_origin_at_head_but_keeps_default_safe(self):
        recorder = _Recorder({0: ORIGIN_URL, 2: ORIGIN_URL_2}, {ORIGIN_URL: MASTER_SIZE, ORIGIN_URL_2: MASTER_SIZE})
        payload = _payload()
        with _use(recorder):
            added = await enhance_with_origin(payload)
        self.assertEqual(added, 1)

        info = parse_video_info(payload["aweme_detail"]["video"])
        origin = info["variants"][0]
        self.assertTrue(origin["origin"])
        self.assertEqual(origin["gear"], "原画")
        self.assertEqual(origin["url"], ORIGIN_URL)
        # 原画档带全部线路，下载时可故障切换
        self.assertEqual(origin["urls"], [ORIGIN_URL, ORIGIN_URL_2])
        self.assertEqual(origin["size"], MASTER_SIZE)
        # 原画档接口不报编码，沿用接口最高档的（fixture 里 720P 是 h264）
        self.assertEqual(origin["codec"], "h264")
        # 默认 video_url **跳过原画**：批次 / 历史 / 灯箱这几条路径没有画质芯片、用户也看不到
        # 体积，静默拉一份几 GB 的原始档会一下吃掉几十 GB。原画只在显式选（芯片 0）时才用。
        self.assertEqual(info["video_url"], "https://v3-web.douyinvod.com/720.mp4")
        self.assertNotEqual(info["video_url"], ORIGIN_URL)
        # 接口给的 720P 档还在，只是排在原画后面
        self.assertEqual(info["variants"][1]["gear"], "720p")
        self.assertFalse(info["variants"][1]["origin"])

    async def test_origin_inherits_codec_from_top_gear(self):
        recorder = _Recorder({0: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE})
        video = _video()
        video["bit_rate"][0]["is_bytevc1"] = 1
        payload = _payload(video)
        with _use(recorder):
            await enhance_with_origin(payload)
        origin = parse_video_info(payload["aweme_detail"]["video"])["variants"][0]
        self.assertEqual(origin["codec"], "h265")

    async def test_8k_bucket_raises_variant_dimensions(self):
        recorder = _Recorder({0: EIGHT_K_URL}, {EIGHT_K_URL: MASTER_SIZE})
        payload = _payload()
        with _use(recorder):
            await enhance_with_origin(payload)
        origin = parse_video_info(payload["aweme_detail"]["video"])["variants"][0]
        self.assertEqual(origin["gear"], "8K")
        # 竖屏：短边 4320，不能被翻成横屏
        self.assertEqual((origin["width"], origin["height"]), (4320, 7680))

    async def test_origin_survives_when_size_probe_fails(self):
        # 探不到大小也要能用：落点仍然返回，只是没有体积（芯片不显示体积）
        recorder = _Recorder({0: DEAD_URL}, {})
        payload = _payload()
        with _use(recorder):
            self.assertEqual(await enhance_with_origin(payload), 1)
        origin = parse_video_info(payload["aweme_detail"]["video"])["variants"][0]
        self.assertEqual(origin["url"], DEAD_URL)
        self.assertEqual(origin["size"], 0)

    async def test_skips_image_posts_without_network(self):
        recorder = _Recorder({0: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE})
        payload = _payload()
        payload["aweme_detail"]["images"] = [{"url_list": ["https://x/a.jpg"]}]
        with _use(recorder):
            self.assertEqual(await enhance_with_origin(payload), 0)
        self.assertEqual(recorder.requests, [])

    async def test_returns_zero_without_video(self):
        recorder = _Recorder({0: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE})
        with _use(recorder):
            self.assertEqual(await enhance_with_origin({"aweme_detail": {"desc": "d"}}), 0)
            self.assertEqual(await enhance_with_origin({}), 0)
        self.assertEqual(recorder.requests, [])

    async def test_env_zero_disables(self):
        recorder = _Recorder({0: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE})
        with _use(recorder), patch.dict(os.environ, {douyin_origin.ORIGIN_ENV: "0"}):
            self.assertEqual(await enhance_with_origin(_payload()), 0)
        self.assertEqual(recorder.requests, [])

    async def test_no_lines_leaves_bit_rate_untouched(self):
        recorder = _Recorder({}, {})
        payload = _payload()
        video = payload["aweme_detail"]["video"]
        before = [dict(item) for item in video["bit_rate"]]
        with _use(recorder):
            self.assertEqual(await enhance_with_origin(payload), 0)
        self.assertEqual(video["bit_rate"], before)

    async def test_survives_unexpected_error(self):
        with patch.object(douyin_origin, "resolve_origin", side_effect=RuntimeError("boom")):
            self.assertEqual(await enhance_with_origin(_payload()), 0)

    async def test_estimated_bitrate_cannot_overflow_client_int(self):
        # 几 GB 的短片按「体积 / 时长」估码率能到 10^10 bps，而客户端的 MediaVariant.bitrate 是
        # Int（32 位）：超范围会让前端解码**整份解析结果**失败，必须压住。
        recorder = _Recorder({0: ORIGIN_URL}, {ORIGIN_URL: 4_000_000_000})
        payload = _payload(_video(duration=8000))  # 8 秒 4GB
        with _use(recorder):
            await enhance_with_origin(payload)
        origin = parse_video_info(payload["aweme_detail"]["video"])["variants"][0]
        self.assertGreater(origin["bitrate"], 0)
        self.assertLess(origin["bitrate"], 2**31 - 1)

    async def test_cached_result_keeps_variant_indexes_stable(self):
        # 下载任务会把作品重新解析一遍：两次必须拿到**一样的档位列表**，否则用户选的
        # 「v0 = 原画」会错位到别的流上（前端传的就是下标）。
        recorder = _Recorder({0: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE})
        with _use(recorder):
            self.assertEqual(await enhance_with_origin(_payload()), 1)
        self.assertGreater(len(recorder.requests), 0)

        broken = _Recorder({}, {})  # 第二次网络整个坏掉
        with _use(broken):
            second = _payload()
            self.assertEqual(await enhance_with_origin(second), 1)
            self.assertEqual(broken.requests, [])  # 命中缓存，没有联网
        origin = parse_video_info(second["aweme_detail"]["video"])["variants"][0]
        self.assertEqual(origin["gear"], "原画")
        self.assertEqual(origin["url"], ORIGIN_URL)

    async def test_cached_failure_also_keeps_indexes_stable(self):
        # 第一次没解析到（网络抖动）也要缓存住：第二次若突然成功，列表会凭空多一档、下标整体后移
        with _use(_Recorder({}, {})):
            self.assertEqual(await enhance_with_origin(_payload()), 0)
        recorder = _Recorder({0: ORIGIN_URL}, {ORIGIN_URL: MASTER_SIZE})
        with _use(recorder):
            self.assertEqual(await enhance_with_origin(_payload()), 0)
            self.assertEqual(recorder.requests, [])


if __name__ == "__main__":
    unittest.main()
