import unittest

from parsehub.errors import ParseError
from parsehub.parsers.parser.douyin import DouyinApiResult, parse_video_info
from parsehub.types import VideoParseResult, VideoRef
from parsehub_studio.tasks import _apply_quality


class DouyinQualityTests(unittest.TestCase):
    def test_highest_resolution_then_bitrate(self):
        video = {
            "play_addr": {"url_list": ["https://example.com/low"], "width": 720, "height": 1280},
            "bit_rate": [
                {
                    "bit_rate": 8000,
                    "play_addr": {"url_list": ["https://example.com/medium"], "width": 1080, "height": 1920},
                },
                {
                    "bit_rate": 4000,
                    "play_addr": {"url_list": ["https://example.com/best"], "width": 2160, "height": 3840},
                },
                {
                    "bit_rate": 2000,
                    "play_addr": {"url_list": ["https://example.com/large"], "width": 2160, "height": 3840},
                },
            ],
            "duration": 12800,
        }
        result = parse_video_info(video)
        self.assertEqual(result["video_url"], "https://example.com/best")
        self.assertEqual(result["width"], 2160)
        self.assertEqual(result["duration"], 13)

    def test_fallback_and_missing_video(self):
        self.assertEqual(
            parse_video_info({"play_addr": {"url_list": ["bad", "https://example.com/only"]}})["video_url"],
            "https://example.com/only",
        )
        with self.assertRaises(ParseError):
            parse_video_info({"play_addr": {"url_list": []}})

    def test_author_and_post_id(self):
        result = DouyinApiResult.parse(
            {
                "aweme_detail": {
                    "aweme_id": "123",
                    "author": {"nickname": "author"},
                    "images": [{"url_list": ["https://example.com/image.jpg"]}],
                }
            }
        )
        self.assertEqual(result.author, "author")
        self.assertEqual(result.post_id, "123")

    def test_unknown_bitrate_preserves_larger_original(self):
        result = parse_video_info(
            {
                "bit_rate": [
                    {"bit_rate": 0, "play_addr": {"url_list": ["https://example.com/original"], "data_size": 7567631}},
                    {
                        "bit_rate": 632,
                        "play_addr": {"url_list": ["https://example.com/compressed"], "data_size": 783567},
                    },
                ]
            }
        )
        self.assertEqual(result["video_url"], "https://example.com/original")

    def test_app_interface_low_res_preview_loses_to_higher_gear(self):
        # 移动端接口的顶层 play_addr 常常只是低清自适应预览（实测某条视频 576x1024 5.4MB），
        # 而 bit_rate[] 里明明有更高分辨率的档位。按分辨率优先，不能再无条件取 play_addr
        # （2026-10-06：用户报「抖音高画质没了」，就是这条把 720P 让给了 540P）。
        result = parse_video_info(
            {
                "is_bytevc1": 0,
                "duration": 12000,
                "play_addr": {
                    "url_list": ["https://example.com/preview"],
                    "width": 576,
                    "height": 1024,
                    "data_size": 5_400_000,
                },
                "bit_rate": [
                    {
                        "is_bytevc1": 2,
                        "bit_rate": 1296,
                        "gear_name": "720_1_2",
                        "play_addr": {
                            "url_list": ["https://example.com/720p"],
                            "width": 720,
                            "height": 1280,
                            "data_size": 3_800_000,
                        },
                    }
                ],
            }
        )
        self.assertEqual(result["video_url"], "https://example.com/720p")
        self.assertEqual(result["height"], 1280)

    def test_h265_4k_gear_is_kept_and_listed_in_variants(self):
        # 网页接口（带 cookie）的 4K 档是 H265（is_bytevc1=1）：必须保留并可选，
        # 过滤 bytevc1 等于永远拿不到原画。variants 按清晰度从高到低给前端选画质。
        result = parse_video_info(
            {
                "bit_rate": [
                    {
                        "is_bytevc1": 0,
                        "bit_rate": 2328,
                        "gear_name": "normal_1080_0",
                        "play_addr": {
                            "url_list": ["https://example.com/1080p"],
                            "width": 1080,
                            "height": 1920,
                            "data_size": 7_071_979,
                        },
                    },
                    {
                        "is_bytevc1": 1,
                        "bit_rate": 4743,
                        "gear_name": "adapt_lowest_4_1",
                        "play_addr": {
                            "url_list": ["https://example.com/2160p"],
                            "width": 2160,
                            "height": 3840,
                            "data_size": 14_403_026,
                        },
                    },
                ]
            }
        )
        self.assertEqual(result["video_url"], "https://example.com/2160p")
        self.assertEqual(
            [variant["url"] for variant in result["variants"]],
            ["https://example.com/2160p", "https://example.com/1080p"],
        )
        self.assertEqual(result["variants"][0]["codec"], "h265")


class DownloadQualitySelectionTests(unittest.TestCase):
    """前端选画质 → 任务侧换流（quality="vN"）。"""

    @staticmethod
    def _result() -> VideoParseResult:
        return VideoParseResult(
            title="t",
            video=VideoRef(
                url="https://example.com/best.mp4",
                width=2160,
                height=3840,
                ext="mp4",
                variants=[
                    {
                        "url": "https://example.com/2160p.mp4",
                        "width": 2160,
                        "height": 3840,
                        "size": 14_403_026,
                        "bitrate": 4743,
                        "gear": "adapt_lowest_4_1",
                        "codec": "h265",
                    },
                    {
                        "url": "https://example.com/720p.mp4",
                        "width": 720,
                        "height": 1280,
                        "size": 2_100_000,
                        "bitrate": 730,
                        "gear": "720_3_2",
                        "codec": "h265",
                    },
                ],
            ),
        )

    def test_variant_index_selects_that_stream(self):
        result = _apply_quality(self._result(), "v1")
        self.assertEqual(result.media.url, "https://example.com/720p.mp4")
        self.assertEqual(result.media.height, 1280)
        self.assertEqual(result.media.width, 720)

    def test_origin_keeps_the_best_stream(self):
        self.assertEqual(_apply_quality(self._result(), "origin").media.url, "https://example.com/best.mp4")

    def test_out_of_range_variant_keeps_default(self):
        self.assertEqual(_apply_quality(self._result(), "v9").media.url, "https://example.com/best.mp4")


if __name__ == "__main__":
    unittest.main()
