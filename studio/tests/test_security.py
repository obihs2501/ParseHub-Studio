import unittest
from unittest.mock import patch

from fastapi.testclient import TestClient

from parsehub_studio.app import create_app
from parsehub_studio.serialize import sanitize_html


class SecurityTests(unittest.TestCase):
    def setUp(self):
        self.client = TestClient(create_app("test-desktop-token"))
        self.client.__enter__()

    def tearDown(self):
        self.client.__exit__(None, None, None)

    def login(self):
        return self.client.get("/desktop?token=test-desktop-token")

    def test_every_resource_needs_authentication(self):
        for path in ("/", "/app.js", "/api/config", "/api/health", "/api/fs/list", "/api/media?src=https://example.com"):
            self.assertEqual(self.client.get(path).status_code, 401, path)
        self.assertEqual(self.client.get("/desktop?token=wrong").status_code, 401)

    def test_bootstrap_cookie_and_security_headers(self):
        response = self.login()
        self.assertEqual(response.status_code, 200)
        cookie = response.history[0].headers["set-cookie"]
        self.assertIn("HttpOnly", cookie)
        self.assertIn("SameSite=strict", cookie)
        self.assertEqual(response.url.path, "/")
        self.assertIn("script-src 'self'", response.headers["content-security-policy"])
        health = self.client.get("/api/health")
        self.assertTrue(health.json()["desktop"])
        self.assertEqual(health.headers["cache-control"], "no-store")

    def test_bearer_and_cross_site_rejection(self):
        headers = {"Authorization": "Bearer test-desktop-token"}
        self.assertEqual(self.client.get("/api/platforms", headers=headers).status_code, 200)
        self.login()
        for headers in ({"Origin": "https://evil.example"}, {"Origin": "null"}, {"Host": "evil.example"}, {"Sec-Fetch-Site": "cross-site"}):
            self.assertEqual(self.client.get("/api/config", headers=headers).status_code, 403)
        self.assertEqual(self.client.post("/api/settings", json={}, headers={"Origin": "https://evil.example"}).status_code, 403)

    def test_external_urls_only_allow_web_protocols(self):
        self.login()
        with patch("parsehub_studio.app.webbrowser.open") as opener:
            for url in ("file:///C:/Windows/notepad.exe", "javascript:alert(1)", "ms-settings:privacy", "https://user:password@example.com"):
                self.assertEqual(self.client.post("/api/open-url", json={"path": url}).status_code, 400)
            opener.assert_not_called()
            self.assertEqual(self.client.post("/api/open-url", json={"path": "https://github.com/z-mio/ParseHub"}).status_code, 200)
            opener.assert_called_once()

    def test_rich_text_removes_active_content(self):
        html = sanitize_html('<h2>Title</h2><script>alert(1)</script><img src="https://example.com/a.jpg" onerror="alert(1)"><a href="javascript:alert(1)">bad</a><iframe src="https://example.com"></iframe>')
        self.assertIn("<h2>Title</h2>", html)
        for unsafe in ("<script", "onerror", "javascript:", "<iframe"):
            self.assertNotIn(unsafe, html)


if __name__ == "__main__":
    unittest.main()
