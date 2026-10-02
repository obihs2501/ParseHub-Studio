import asyncio
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from parsehub.types import ImageParseResult, ImageRef, Platform
from parsehub_studio.archive import publish_archive
from parsehub_studio.naming import naming_values
from parsehub_studio.serialize import display_title
from parsehub_studio.settings import Settings, SettingsStore
from parsehub_studio.tasks import TaskManager


class ArchiveTests(unittest.IsolatedAsyncioTestCase):
    def make_download(self, root, name, content):
        staging = root / name
        source = staging / "作品"
        source.mkdir(parents=True)
        for filename in ("001.jpg", "001_live.mp4", "metadata.json"):
            (source / filename).write_bytes(content)
        result = SimpleNamespace(output_dir=source, media=[SimpleNamespace(
            path=source / "001.jpg", video_path=source / "001_live.mp4")], errors=[])
        return staging, result

    async def test_concurrent_merge_preserves_files_metadata_and_livephoto(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            target = root / "微博_岛田云溪"
            target.mkdir()
            (target / "001.jpg").write_bytes(b"existing")
            first = self.make_download(root, "staging1", b"first")
            second = self.make_download(root, "staging2", b"second")
            await asyncio.gather(*(asyncio.to_thread(publish_archive, result, staging, target)
                                   for staging, result in (first, second)))
            self.assertEqual((target / "001.jpg").read_bytes(), b"existing")
            self.assertEqual({path.read_bytes() for path in target.glob("*.jpg")}, {b"existing", b"first", b"second"})
            self.assertEqual(len(list(target.glob("metadata*.json"))), 2)
            for staging, result in (first, second):
                self.assertFalse(staging.exists())
                self.assertEqual(result.output_dir, target.resolve())
                self.assertTrue(result.media[0].video_path.is_file())
                self.assertEqual(result.media[0].path.read_bytes(), result.media[0].video_path.read_bytes())
            self.assertFalse((root / "微博_岛田云溪_2").exists())

    def test_copy_fallback_and_external_paths_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            staging, result = self.make_download(root, "staging", b"test")
            with patch("parsehub_studio.archive.os.link", side_effect=OSError("not supported")):
                publish_archive(result, staging, root / "archive")
            self.assertEqual(result.media[0].path.read_bytes(), b"test")
            staging, result = self.make_download(root, "staging2", b"safe")
            result.output_dir = root
            with self.assertRaises(ValueError):
                publish_archive(result, staging, root / "archive")
            self.assertTrue(staging.exists())

    def test_cross_platform_aliases_id_priority_and_persistence(self):
        aliases = [
            {"platform": "weibo", "account": "123", "creator": "云溪", "match_by": "id"},
            {"platform": "douyin", "account": "456", "creator": "云溪", "match_by": "id"},
            {"platform": "weibo", "account": "岛田云溪", "creator": "另一个昵称规则", "match_by": "name"},
        ]
        for platform, author_id in (("weibo", "123"), ("douyin", "456")):
            values = naming_values({"platform": {"id": platform}, "author_id": author_id, "author": "岛田云溪"}, aliases=aliases)
            self.assertEqual(values["creator"], "云溪")
        self.assertEqual(naming_values({"platform": {"id": "weibo"}, "author": "岛田云溪"}, aliases=aliases)["creator"], "另一个昵称规则")
        self.assertEqual(naming_values({"author": "未映射博主"}, aliases=aliases)["creator"], "未映射博主")
        with tempfile.TemporaryDirectory() as directory:
            store = SettingsStore(Path(directory) / "settings.json")
            store.save(Settings(author_aliases=aliases))
            self.assertTrue(store.load().merge_author_folders)
            self.assertEqual(store.load().author_aliases[1].creator, "云溪")

    def test_display_title_never_uses_filename_or_bare_id(self):
        self.assertEqual(display_title({"title": "1790941949421561700", "content": " 洋兰\n出镜:清妙 ", "name": "file-slug"}), "洋兰 出镜:清妙")
        self.assertEqual(display_title({"title": "\u200b", "content": "\u200b\u200b", "name": "1790941949421561700"}), "无标题作品")

    async def test_task_downloads_merge_and_use_readable_card_metadata(self):
        manager = TaskManager(Mock())

        async def parse(*args, **kwargs):
            result = ImageParseResult(content="洋兰 出镜：清妙", photo=[ImageRef(url="https://example.com/original.jpg")])
            result.platform = Platform.WEIBO
            result.author = "岛田云溪"
            result.author_id = "123"
            result.post_id = "post-id"
            result.raw_url = "https://weibo.com/123/post-id"
            return result

        async def download(url, path, **kwargs):
            path.write_bytes(b"image")
            return path

        with (tempfile.TemporaryDirectory() as directory,
              patch("parsehub_studio.tasks.ParseHub") as parser,
              patch("parsehub.types.result.download", side_effect=download),
              patch("parsehub.types.result.ImageFile", side_effect=lambda path, **kwargs: SimpleNamespace(path=path))):
            parser.return_value.parse.side_effect = parse
            tasks = [manager.create("source", {"text": "source", "use_saved_config": False,
                     "path": directory, "folder_template": "{platform}_{author}", "file_template": "{index:03d}",
                     "merge_author_folders": True}) for _ in range(2)]
            await asyncio.gather(*(manager._running[task.id] for task in tasks))
            self.assertTrue(all(task.status == "success" for task in tasks), [task.error for task in tasks])
            self.assertEqual(tasks[0].output_dir, tasks[1].output_dir)
            self.assertNotEqual(tasks[0].files, tasks[1].files)
            self.assertEqual(tasks[0].title, "洋兰 出镜：清妙")
            self.assertEqual(tasks[0].to_dict()["author"], "岛田云溪")
            self.assertTrue(tasks[0].thumbnail)
        await manager.shutdown()
