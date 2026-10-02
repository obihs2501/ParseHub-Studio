import asyncio
import tempfile
import unittest
from datetime import UTC, datetime
from unittest.mock import patch

from parsehub.types import ImageParseResult, ImageRef, LivePhotoRef

from parsehub_studio.naming import apply_naming, naming_values, render_name, safe_name, validate_template


class NamingTests(unittest.IsolatedAsyncioTestCase):
    def test_publication_date_formats_and_missing_values(self):
        now = datetime(2026, 10, 2, tzinfo=UTC)
        timestamp = datetime(2026, 9, 28, 8, tzinfo=UTC).timestamp()
        for value in (timestamp, timestamp * 1000, str(int(timestamp)), "2026-09-28",
                      "2026-09-28T08:00:00Z", "Mon Sep 28 16:00:00 +0800 2026"):
            values = naming_values({"published_at": value}, now)
            self.assertEqual(render_name("{publish_date}_{date}", values), "2026-09-28_2026-10-02")
        for value in (None, "", "not-a-date", 0, -1, True, float("inf")):
            self.assertEqual(naming_values({"published_at": value}, now)["publish_date"], "未知发布日期")
        self.assertEqual(naming_values({"published_at": "2026-09-28T01:00:00+08:00"}, now)["publish_date"], "2026-09-27")

    def test_elements_and_validation(self):
        values = naming_values(
            {"platform": {"name": "微博"}, "author": "作者", "title": "标题", "post_id": "123", "type_label": "视频"},
            datetime(2026, 10, 2, 9, 8, 7, tzinfo=UTC),
        )
        self.assertEqual(
            render_name("{platform}_{author}_{id}_{date}_{time}_{index:03d}_{type}", values),
            "微博_作者_123_2026-10-02_090807_001_视频",
        )
        for template in (
            "",
            " ",
            "../{title}",
            "a\\b",
            "{missing}",
            "{title.upper}",
            "{index:999d}",
            "{title!r}",
            "{title",
            "{title:>5}",
            "{index:{title}}",
            "*bad*",
            "a" * 201,
        ):
            with self.subTest(template=template), self.assertRaises(ValueError):
                validate_template(template)

    def test_windows_paths_and_missing_metadata(self):
        for name in ("CON", "aux.txt", "Lpt9", "NUL"):
            self.assertTrue(safe_name(name).startswith("_"))
        self.assertEqual(safe_name(" ..a/b:c?*\x00. "), "a_b_c___")
        self.assertEqual(safe_name("..."), "未命名")
        self.assertLessEqual(len(safe_name("🙂" * 100).encode("utf-16-le")), 200)
        values = naming_values({"raw_url": "https://example.com/?modal_id=123"})
        self.assertEqual(values["id"], "123")
        self.assertEqual(values["author"], "未知作者")

    def test_collision_livephoto_and_original_index(self):
        result = ImageParseResult(photo=[])
        apply_naming(result, {"title": "作品"}, "{title}", "same")
        photo = ImageRef(url="https://example.com/a.jpg")
        self.assertEqual(result.file_name_builder(1, photo, False), "same.jpg")
        self.assertEqual(result.file_name_builder(2, photo, False), "same_2.jpg")
        live = LivePhotoRef(url="https://example.com/a.jpg", video_url="https://example.com/a.mp4")
        self.assertEqual(result.file_name_builder(2, live, True), "same_live.mp4")
        apply_naming(result, {}, "{title}", "{index:03d}_{type}", 6)
        self.assertEqual(result.file_name_builder(1, photo, False), "007_图片.jpg")

    async def test_download_uses_templates_and_preserves_existing_folders(self):
        paths = []

        async def write_file(url, path, **kwargs):
            path.write_bytes(b"image")
            paths.append(path)
            return path

        def make_result():
            result = ImageParseResult(photo=[ImageRef(url="https://example.com/a.jpg")])
            apply_naming(result, {"title": "sample"}, "{title}", "{index:03d}")
            return result

        with (
            tempfile.TemporaryDirectory() as directory,
            patch("parsehub.types.result.download", side_effect=write_file),
            patch("parsehub.types.result.ImageFile"),
        ):
            await asyncio.gather(make_result().download(directory), make_result().download(directory))
            self.assertEqual(len(paths), 2)
            self.assertNotEqual(paths[0].parent, paths[1].parent)
            self.assertTrue(all(path.name == "001.jpg" and path.is_relative_to(directory) for path in paths))

    async def test_multi_media_reports_byte_progress(self):
        events = []

        async def progress(current, total, unit):
            events.append((current, total, unit))

        async def write_file(url, path, **kwargs):
            await kwargs["progress"](5, 10)
            path.write_bytes(b"image")
            return path

        with (tempfile.TemporaryDirectory() as directory,
              patch("parsehub.types.result.download", side_effect=write_file),
              patch("parsehub.types.result.ImageFile")):
            result = ImageParseResult(photo=[ImageRef(url="https://example.com/a.jpg"),
                                             ImageRef(url="https://example.com/b.jpg")])
            await result.download(directory, callback=progress)
        self.assertEqual(events, [(5, 10, "bytes"), (1, 2, "count"), (5, 10, "bytes"), (2, 2, "count")])


if __name__ == "__main__":
    unittest.main()
