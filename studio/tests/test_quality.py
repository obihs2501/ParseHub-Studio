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


if __name__ == "__main__":
    unittest.main()
