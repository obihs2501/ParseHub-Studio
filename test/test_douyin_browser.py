"""抖音浏览器补档的离线测试（不联网、不启动真浏览器）。

fixture 用 ``quote(json.dumps(gear))`` 造出与真实 SSR 页面同形的 ``%7B%22uri%22...`` 片段，
``subprocess.run`` 全部打桩。
"""

import asyncio
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from urllib.parse import quote

from parsehub.parsers.parser.douyin import parse_video_info
from parsehub.provider_api import douyin_browser
from parsehub.provider_api.douyin_browser import (
    current_top_short_side,
    enhance_with_browser,
    extract_discover_gears,
    find_browser,
    gears_to_bit_rate,
    merge_gears,
)

AWEME_ID = "7300000000000000000"
RAW_URL = f"https://www.douyin.com/video/{AWEME_ID}"


def _gear(
    uri: str = "v0300fg",
    width: int = 2160,
    height: int = 3840,
    size: int = 14403026,
    bitrate: int = 4743884,
    name: str = "adapt_lowest_4_1",
    h265: bool = True,
    fps: int = 60,
    host: str = "v3-web.douyinvod.com",
) -> dict:
    """一条 discover 页档位（字段名与实测 dump 一致，camelCase）。"""
    return {
        "uri": uri,
        "width": width,
        "height": height,
        "dataSize": size,
        "bitRate": bitrate,
        "gearName": name,
        "isH265": h265,
        "fps": fps,
        "qualityType": 1,
        "playAddr": [{"src": f"https://{host}/{uri}.mp4"}],
    }


def _discover_html(gears: list[dict]) -> str:
    """把档位塞进页面 HTML；末尾再放一个「有标记但没有 JSON」的噪音块。"""
    body = "".join(f'<script>window.__gd={quote(json.dumps(gear))}</script>' for gear in gears)
    return f"<html><body>{body}<i>noise %7B%22uri%22 nope</i></body></html>"


def _mobile_video() -> dict:
    """移动端接口常见的低清返回：顶层 576x1024 预览 + 一条 720P 档。"""
    return {
        "play_addr": {
            "url_list": ["https://v3-web.douyinvod.com/preview.mp4"],
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
                    "url_list": ["https://v3-web.douyinvod.com/720.mp4"],
                    "width": 720,
                    "height": 1280,
                    "data_size": 8000000,
                },
            }
        ],
        "cover": {"url_list": ["https://p3.douyinpic.com/cover.jpg"]},
        "duration": 12800,
    }


def _payload(video: dict | None = None) -> dict:
    return {"aweme_detail": {"aweme_id": AWEME_ID, "desc": "desc", "video": video or _mobile_video()}}


def _completed(html: str, returncode: int = 0) -> subprocess.CompletedProcess:
    return subprocess.CompletedProcess([], returncode, html.encode("utf-8"), b"")


class ExtractGearsTests(unittest.TestCase):
    def test_dedupes_and_skips_malformed(self):
        gears = extract_discover_gears(
            _discover_html(
                [
                    _gear(),
                    _gear(),  # 完全重复 → 去掉
                    _gear(uri="other", width=1080, height=1920, size=11000000, name="1080p"),
                    _gear(uri="broken", width=0),  # 没有分辨率 → 跳过
                ]
            )
        )
        self.assertEqual(len(gears), 2)
        self.assertEqual({gear["gearName"] for gear in gears}, {"adapt_lowest_4_1", "1080p"})

    def test_no_marker_returns_empty(self):
        self.assertEqual(extract_discover_gears("<html><body>nothing</body></html>"), [])

    def test_maps_to_api_bit_rate_shape(self):
        entry = gears_to_bit_rate(extract_discover_gears(_discover_html([_gear()])))[0]
        self.assertEqual(entry["gear_name"], "adapt_lowest_4_1")
        self.assertEqual(entry["bit_rate"], 4743884)
        self.assertEqual(entry["is_bytevc1"], 1)
        self.assertEqual(entry["FPS"], 60)
        address = entry["play_addr"]
        self.assertEqual((address["width"], address["height"]), (2160, 3840))
        self.assertEqual(address["data_size"], 14403026)
        self.assertEqual(address["url_list"], ["https://v3-web.douyinvod.com/v0300fg.mp4"])

    def test_h264_gear_is_not_bytevc1(self):
        entry = gears_to_bit_rate([_gear(h265=False)])[0]
        self.assertEqual(entry["is_bytevc1"], 0)

    def test_gear_without_playable_url_is_dropped(self):
        gear = {"uri": "x", "width": 1080, "height": 1920, "playAddr": [{"src": "bad"}]}
        self.assertEqual(gears_to_bit_rate([gear]), [])


class MergeGearsTests(unittest.TestCase):
    def test_adds_only_missing_tiers_and_parse_picks_4k(self):
        video = _mobile_video()
        added = merge_gears(
            video,
            [
                _gear(uri="g720", width=720, height=1280, size=8000000, name="720p"),  # 分辨率已有 → 跳过
                _gear(uri="g1080", width=1080, height=1920, size=11000000, name="1080p"),
                _gear(uri="g2160"),
            ],
        )
        self.assertEqual(added, 2)
        info = parse_video_info(video)
        self.assertEqual(info["width"], 2160)
        self.assertEqual(info["video_url"], "https://v3-web.douyinvod.com/g2160.mp4")
        self.assertEqual(
            [(variant["width"], variant["height"]) for variant in info["variants"]],
            [(2160, 3840), (1080, 1920), (720, 1280), (576, 1024)],
        )

    def test_skips_duplicate_url_with_different_size(self):
        video = {
            "bit_rate": [
                {"play_addr": {"url_list": ["https://v3-web.douyinvod.com/dup.mp4"], "width": 1280, "height": 720}}
            ]
        }
        self.assertEqual(merge_gears(video, [_gear(uri="dup", width=2160, height=3840)]), 0)

    def test_creates_bit_rate_bucket_when_missing(self):
        video = {"play_addr": {"url_list": ["https://v3-web.douyinvod.com/low.mp4"], "width": 576, "height": 1024}}
        self.assertEqual(merge_gears(video, [_gear()]), 1)
        self.assertEqual(len(video["bit_rate"]), 1)

    def test_empty_gears_is_noop(self):
        video = _mobile_video()
        self.assertEqual(merge_gears(video, []), 0)
        self.assertEqual(len(video["bit_rate"]), 1)

    def test_current_top_short_side(self):
        self.assertEqual(current_top_short_side(_mobile_video()), 720)
        self.assertEqual(current_top_short_side({}), 0)


class FindBrowserTests(unittest.TestCase):
    def test_env_zero_disables(self):
        with patch.dict(os.environ, {douyin_browser.BROWSER_ENV: "0"}):
            self.assertIsNone(find_browser())

    def test_env_path_overrides(self):
        with tempfile.TemporaryDirectory() as tmp:
            fake = Path(tmp) / "fake-browser.exe"
            fake.write_text("", encoding="utf-8")
            with patch.dict(os.environ, {douyin_browser.BROWSER_ENV: str(fake)}):
                self.assertEqual(find_browser(), str(fake))

    def test_returns_none_without_any_candidate(self):
        env = {key: value for key, value in os.environ.items() if key != douyin_browser.BROWSER_ENV}
        with (
            patch.dict(os.environ, env, clear=True),
            patch.object(douyin_browser, "CANDIDATE_PATHS", ()),
            patch.object(douyin_browser, "CANDIDATE_NAMES", ()),
            patch.object(douyin_browser.shutil, "which", return_value=None),
        ):
            self.assertIsNone(find_browser())


class EnhanceWithBrowserTests(unittest.TestCase):
    def setUp(self):
        douyin_browser._gear_cache.clear()

    def test_merges_gears_and_cleans_profile(self):
        html = _discover_html([_gear(uri="g1080", width=1080, height=1920, size=11000000), _gear()])
        payload = _payload()
        with (
            patch.object(douyin_browser, "find_browser", return_value="msedge.exe"),
            patch.object(douyin_browser.subprocess, "run", return_value=_completed(html)) as run,
        ):
            added = asyncio.run(enhance_with_browser(payload, RAW_URL, proxy="http://127.0.0.1:7890"))
        self.assertEqual(added, 2)
        self.assertEqual(parse_video_info(payload["aweme_detail"]["video"])["width"], 2160)
        command = run.call_args.args[0]
        self.assertEqual(command[0], "msedge.exe")
        self.assertIn("--dump-dom", command)
        self.assertIn(f"https://www.douyin.com/discover?modal_id={AWEME_ID}", command)
        self.assertIn("--window-size=2560,1440", command)
        self.assertIn("--blink-settings=imagesEnabled=false", command)
        self.assertIn("--proxy-server=http://127.0.0.1:7890", command)
        profile = next(arg for arg in command if arg.startswith("--user-data-dir=")).split("=", 1)[1]
        self.assertFalse(Path(profile).exists())

    def test_page_without_gears_is_not_retried(self):
        with (
            patch.object(douyin_browser, "find_browser", return_value="msedge.exe"),
            patch.object(douyin_browser.subprocess, "run", return_value=_completed("<html>no gears</html>")) as run,
        ):
            added = asyncio.run(enhance_with_browser(_payload(), RAW_URL))
        self.assertEqual(added, 0)
        self.assertEqual(run.call_count, 1)

    def test_skips_when_interface_already_gave_high_tier(self):
        payload = _payload({"play_addr": {"url_list": ["https://x/4k.mp4"], "width": 2160, "height": 3840}})
        with patch.object(douyin_browser, "find_browser") as finder:
            self.assertEqual(asyncio.run(enhance_with_browser(payload, RAW_URL)), 0)
        finder.assert_not_called()

    def test_skips_image_posts(self):
        payload = _payload()
        payload["aweme_detail"]["images"] = [{"url_list": ["https://x/a.jpg"]}]
        with patch.object(douyin_browser, "find_browser") as finder:
            self.assertEqual(asyncio.run(enhance_with_browser(payload, RAW_URL)), 0)
        finder.assert_not_called()

    def test_returns_zero_without_browser(self):
        with patch.object(douyin_browser, "find_browser", return_value=None):
            self.assertEqual(asyncio.run(enhance_with_browser(_payload(), RAW_URL)), 0)

    def test_returns_zero_without_aweme_detail(self):
        self.assertEqual(asyncio.run(enhance_with_browser({}, RAW_URL)), 0)

    def test_returns_zero_for_non_numeric_id(self):
        self.assertEqual(asyncio.run(enhance_with_browser(_payload(), "https://www.douyin.com/video/")), 0)

    def test_survives_subprocess_failure(self):
        with (
            patch.object(douyin_browser, "find_browser", return_value="msedge.exe"),
            patch.object(douyin_browser.subprocess, "run", side_effect=OSError("boom")),
        ):
            self.assertEqual(asyncio.run(enhance_with_browser(_payload(), RAW_URL)), 0)

    def test_survives_nonzero_exit(self):
        failed = _completed("<html></html>", returncode=1)
        with (
            patch.object(douyin_browser, "find_browser", return_value="msedge.exe"),
            patch.object(douyin_browser.subprocess, "run", return_value=failed) as run,
        ):
            self.assertEqual(asyncio.run(enhance_with_browser(_payload(), RAW_URL)), 0)
        # 只跑一次 dump，失败直接降级（不再重试）
        self.assertEqual(run.call_count, 1)

    def test_cache_reuses_gears_for_same_video(self):
        html = _discover_html([_gear()])
        with (
            patch.object(douyin_browser, "find_browser", return_value="msedge.exe") as finder,
            patch.object(douyin_browser.subprocess, "run", return_value=_completed(html)) as run,
        ):
            first = asyncio.run(enhance_with_browser(_payload(), RAW_URL))
            second = asyncio.run(enhance_with_browser(_payload(), RAW_URL))
        self.assertEqual((first, second), (1, 1))
        self.assertEqual(run.call_count, 1)
        self.assertEqual(finder.call_count, 1)

    def test_tolerates_string_numbers_and_skips_bad_gear(self):
        good = _gear(uri="g1080", width=1080, height=1920, size=11000000)
        good["bitRate"] = "4743884.0"
        good["dataSize"] = "11000000"
        bad = {"uri": "bad", "width": 1080, "height": 1920, "playAddr": "oops"}
        entries = gears_to_bit_rate([bad, good])
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["bit_rate"], 4743884)
        self.assertEqual(entries[0]["play_addr"]["data_size"], 11000000)


if __name__ == "__main__":
    unittest.main()
