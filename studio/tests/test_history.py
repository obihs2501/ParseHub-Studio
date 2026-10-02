import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from fastapi.testclient import TestClient

from parsehub_studio.app import create_app
from parsehub_studio.history import HistoryStore


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.store = HistoryStore(Path(self.directory.name) / "history.json", limit=2)

    def tearDown(self):
        self.directory.cleanup()

    def test_history_deduplicates_and_keeps_limit(self):
        self.store.add({"raw_url": "https://example.com/1", "content": "first", "html": "<p>html</p>"})
        self.store.add({"raw_url": "https://example.com/1", "content": "updated"})
        entries = self.store.load()
        self.assertEqual(len(entries), 1)
        self.assertEqual(entries[0]["content"], "updated")
        self.assertNotIn("html", entries[0])
        self.store.add({"raw_url": "https://example.com/2"})
        self.store.add({"raw_url": "https://example.com/3"})
        self.assertEqual(len(self.store.load()), 2)
        self.assertEqual(self.store.load()[0]["raw_url"], "https://example.com/3")

    def test_parse_without_download_saves_history(self):
        app = create_app()
        app.state.ctx.history = self.store
        payload = {"raw_url": "https://example.com/1", "title": "Test", "content": "Content", "media": []}
        with TestClient(app) as client, patch.object(app.state.ctx.parsehub, "parse", new=AsyncMock(return_value=object())), patch("parsehub_studio.app.result_to_payload", return_value=payload):
            response = client.post("/api/parse", json={"text": "https://example.com/1", "use_saved_config": False})
            self.assertEqual(response.status_code, 200)
            self.assertEqual(client.get("/api/history").json()["entries"][0]["title"], "Test")
            self.assertEqual(client.get("/api/tasks").json()["tasks"], [])


if __name__ == "__main__":
    unittest.main()
