import asyncio
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

from parsehub.errors import ParseError
from parsehub.types import VideoParseResult

from parsehub_studio.tasks import TaskManager


class TaskTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.finished = Mock()
        self.manager = TaskManager(Mock(), on_success=self.finished)

    async def asyncTearDown(self):
        await self.manager.shutdown()

    async def test_download_success_and_partial_failure(self):
        for errors in ([], ["second file failed"]):
            result = VideoParseResult(title="Test video", video="https://example.com/video.mp4")
            result.download = AsyncMock(return_value=SimpleNamespace(
                media=[SimpleNamespace(path=Path("saved.mp4"))], output_dir=Path("downloads"), errors=errors,
            ))
            parser = Mock()
            parser.parse = AsyncMock(return_value=result)
            with patch("parsehub_studio.tasks.ParseHub", return_value=parser):
                task = self.manager.create("https://example.com", {"text": "https://example.com", "use_saved_config": False})
                await self.manager._running[task.id]
            self.assertEqual(task.status, "partial" if errors else "success")
            self.assertEqual(task.files, ["saved.mp4"])
            self.assertIsNotNone(task.finished_at)
        self.assertEqual(self.finished.call_count, 2)

    async def test_parse_failure(self):
        with patch.object(self.manager, "_execute", new=AsyncMock(side_effect=ParseError("offline test failure"))):
            task = self.manager.create("test", {})
            await self.manager._running[task.id]
        self.assertEqual(task.status, "error")
        self.assertIn("offline test failure", task.error)
        self.finished.assert_not_called()

    async def test_cancel_before_task_starts(self):
        task = self.manager.create("test", {})
        self.assertTrue(self.manager.cancel(task.id))
        await asyncio.gather(self.manager._running[task.id], return_exceptions=True)
        self.assertEqual(task.status, "cancelled")
        self.assertIsNotNone(task.finished_at)
        self.assertFalse(self.manager.cancel(task.id))
        self.manager.clear_finished()
        self.assertEqual(self.manager.list(), [])

    async def test_shutdown_cancels_running_download(self):
        started = asyncio.Event()

        async def slow_download(*args):
            started.set()
            await asyncio.Event().wait()

        with patch.object(self.manager, "_execute", new=slow_download):
            task = self.manager.create("test", {})
            await started.wait()
            await self.manager.shutdown()
        self.assertEqual(task.status, "cancelled")
        self.finished.assert_not_called()


if __name__ == "__main__":
    unittest.main()
