import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

import httpx

from parsehub_studio.app import create_app
from parsehub_studio.history import HistoryStore
from parsehub_studio.settings import SettingsStore


class WorkspaceApiTests(unittest.IsolatedAsyncioTestCase):
    async def test_batch_lifecycle_and_naming_persistence(self):
        with tempfile.TemporaryDirectory() as directory:
            settings = SettingsStore(Path(directory) / "settings.json")
            with (
                patch("parsehub_studio.app.SettingsStore", return_value=settings),
                patch("parsehub_studio.app.HistoryStore", return_value=HistoryStore(Path(directory) / "history.json")),
            ):
                app = create_app()
            context = app.state.ctx
            context.default_proxy = AsyncMock(return_value=None)
            context.batches.parse = AsyncMock(return_value={"title": "sample"})
            async with (
                app.router.lifespan_context(app),
                httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost") as client,
            ):
                response = await client.post(
                    "/api/settings", json={"folder_template": "{author}_{id}", "file_template": "{index:03d}_{title}",
                        "merge_author_folders": True,
                        "author_aliases": [{"platform": "weibo", "account": "123", "creator": "云溪", "match_by": "id"}]}
                )
                self.assertEqual(response.status_code, 200)
                response = await client.post("/api/settings", json={"folder_template": "../bad"})
                self.assertEqual(response.status_code, 400)
                self.assertEqual(settings.load().folder_template, "{author}_{id}")
                preview = await client.post(
                    "/api/naming/preview",
                    json={"folder_template": "{author}_{id}", "file_template": "{index:03d}_{title}"},
                )
                self.assertEqual(preview.status_code, 200)
                self.assertEqual(preview.json()["file"], "001_山间日落.jpg")
                response = await client.post(
                    "/api/batches", json={"text": "https://weibo.com/123/456\nhttps://v.douyin.com/abcd/"}
                )
                self.assertEqual(response.status_code, 200)
                batch_id = response.json()["id"]
                await context.batches.running[batch_id]
                response = await client.get("/api/batches/" + batch_id)
                self.assertEqual(response.json()["status"], "complete")
                self.assertEqual(len(response.json()["items"]), 2)
                self.assertEqual(context.batches.parse.call_args.args[1]["folder_template"], "{author}_{id}")
                self.assertTrue(context.batches.parse.call_args.args[1]["merge_author_folders"])
                self.assertEqual(context.batches.parse.call_args.args[1]["author_aliases"][0]["creator"], "云溪")
                self.assertEqual((await client.post("/api/batches", json={"text": "no link"})).status_code, 400)
                self.assertEqual((await client.get("/api/batches/missing")).status_code, 404)


if __name__ == "__main__":
    unittest.main()
