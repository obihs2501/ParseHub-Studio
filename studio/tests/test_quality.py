import unittest

from parsehub.errors import ParseError
from parsehub.parsers.parser.douyin import DouyinApiResult, parse_video_info


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

    def test_app_interface_prefers_top_level_play_addr_original(self):
        # app 接口带 is_bytevc1，顶层 play_addr 是原画母带，即使 bit_rate[] 里有更高分辨率
        # 的转码档位（体积更小）也必须优先取原画。
        result = parse_video_info(
            {
                "is_bytevc1": 0,
                "duration": 12000,
                "play_addr": {
                    "url_list": ["https://example.com/original"],
                    "width": 576,
                    "height": 1024,
                    "data_size": 87_000_000,
                },
                "bit_rate": [
                    {
                        "is_bytevc1": 2,
                        "bit_rate": 549127,
                        "play_addr": {
                            "url_list": ["https://example.com/transcoded"],
                            "width": 1080,
                            "height": 1920,
                            "data_size": 874073,
                        },
                    }
                ],
            }
        )
        self.assertEqual(result["video_url"], "https://example.com/original")
        self.assertEqual(result["width"], 576)

    def test_app_interface_falls_back_to_h264_bit_rate(self):
        # app 接口顶层 play_addr 为空时，回退到 bit_rate[] 且必须过滤 bytevc1/2 转码档。
        result = parse_video_info(
            {
                "is_bytevc1": 0,
                "play_addr": {"url_list": [], "width": 0, "height": 0},
                "bit_rate": [
                    {
                        "is_bytevc1": 1,
                        "bit_rate": 6000,
                        "play_addr": {"url_list": ["https://example.com/hevc"], "data_size": 9_000_000},
                    },
                    {
                        "is_bytevc1": 0,
                        "bit_rate": 3000,
                        "play_addr": {"url_list": ["https://example.com/h264"], "data_size": 6_000_000},
                    },
                ],
            }
        )
        self.assertEqual(result["video_url"], "https://example.com/h264")


if __name__ == "__main__":
    unittest.main()
