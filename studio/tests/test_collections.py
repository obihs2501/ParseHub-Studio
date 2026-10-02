import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import httpx
from parsehub.cli_config import ConfigStore, FileCookieStore
from pydantic import ValidationError

from parsehub_studio.app import create_app
from parsehub_studio.platform_config import PlatformConfigBridge
from parsehub_studio.settings import Bookmark, Settings, SettingsStore


class CollectionTests(unittest.IsolatedAsyncioTestCase):
    def test_cookie_rotation_override_disable_delete_and_persistence(self):
        with tempfile.TemporaryDirectory() as directory:
            bridge = PlatformConfigBridge(ConfigStore(Path(directory) / "config.toml"),
                                          FileCookieStore(Path(directory) / "cookies.toml"))
            bridge.set_cookie("weibo", "default-cookie")
            bridge.add_cookie("weibo", "first-secret-cookie", "账户一")
            bridge.add_cookie("weibo", "second-secret-cookie", "账户二")
            bridge.add_cookie("weibo", "first-secret-cookie", "重复")
            self.assertEqual(len(bridge.pool_entries("weibo")), 2)
            self.assertNotIn("first-secret-cookie", str(bridge.describe("weibo")))
            self.assertEqual(bridge.resolve("weibo", cookie="explicit")[2], "explicit")
            self.assertEqual([bridge.resolve("weibo")[2] for _ in range(3)],
                             ["first-secret-cookie", "second-secret-cookie", "first-secret-cookie"])
            entries = bridge.pool_entries("weibo")
            bridge.change_cookie("weibo", entries[0]["id"], False)
            self.assertEqual(bridge.next_cookie("weibo"), "second-secret-cookie")
            bridge.change_cookie("weibo", entries[1]["id"], None)
            self.assertEqual(bridge.next_cookie("weibo"), "default-cookie")
            restored = PlatformConfigBridge(bridge.config_store, bridge.cookie_store)
            self.assertFalse(restored.pool_entries("weibo")[0]["enabled"])
            self.assertIsNone(restored.next_cookie("douyin"))

    def test_bookmarks_validation_and_persistence(self):
        with tempfile.TemporaryDirectory() as directory:
            store = SettingsStore(Path(directory) / "settings.json")
            store.save(Settings(bookmarks=[Bookmark(name="测试", url="https://example.com", group="微博")]))
            self.assertEqual(store.load().bookmarks[0].group, "微博")
        for url in ("javascript:alert(1)", "file:///tmp/a", "https://user:password@example.com", "https://"):
            with self.assertRaises(ValidationError):
                Bookmark(name="bad", url=url)

    async def test_collection_api_and_naming_preview(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            bridge = PlatformConfigBridge(ConfigStore(root / "config.toml"), FileCookieStore(root / "cookies.toml"))
            with (patch("parsehub_studio.app.PlatformConfigBridge", return_value=bridge),
                  patch("parsehub_studio.app.SettingsStore", return_value=SettingsStore(root / "settings.json"))):
                app = create_app()
            async with (app.router.lifespan_context(app),
                        httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://localhost") as client):
                response = await client.post("/api/config/cookie-pool", json={"platform": "weibo", "cookie": "test-secret-value"})
                self.assertEqual(response.status_code, 200)
                self.assertNotIn("test-secret-value", response.text)
                self.assertEqual(len(response.json()["cookie_pool"]), 1)
                self.assertEqual((await client.post("/api/config/cookie-pool", json={"platform": "invalid", "cookie": "test"})).status_code, 400)
                response = await client.post("/api/settings", json={"bookmarks": [{"name": "工具", "url": "https://example.com", "group": "通用"}]})
                self.assertEqual(response.status_code, 200)
                response = await client.post("/api/naming/preview", json={"folder_template": "{publish_date}_{author}", "file_template": "{publish_date}_{index:03d}"})
                self.assertEqual(response.status_code, 200)
                self.assertEqual(response.json()["file"], "2026-09-28_001.jpg")
