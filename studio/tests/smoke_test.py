"""ParseHub Studio 冒烟测试：只验证本地接口，不访问外网。"""

from __future__ import annotations

import sys

from fastapi.testclient import TestClient

from parsehub_studio.app import app


def check(condition: object, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    with TestClient(app) as client:
        health = client.get("/api/health")
        check(health.status_code == 200 and health.json()["ok"] is True, "/api/health 异常")

        page = client.get("/")
        check(page.status_code == 200 and "ParseHub" in page.text, "首页未正确返回")
        for asset in ("/app.js", "/styles.css", "/workspace.css", "/favicon.svg"):
            check(client.get(asset).status_code == 200, f"静态资源缺失: {asset}")

        platforms = client.get("/api/platforms").json()["platforms"]
        check(len(platforms) >= 15, f"平台数量异常: {len(platforms)}")
        ids = {item["id"] for item in platforms}
        for expected in ("xhs", "douyin", "weibo", "bilibili", "twitter", "zhihu"):
            check(expected in ids, f"缺少平台: {expected}")
        check(all(isinstance(item["supported_types"], list) for item in platforms), "平台信息结构异常")

        settings = client.get("/api/settings").json()
        check("default_save_dir" in settings and "connections" in settings, "设置接口异常")

        config = client.get("/api/config").json()
        check("config_path" in config and "cookie_path" in config, "配置接口异常")

        detect = client.post(
            "/api/detect",
            json={"text": "分享文案 https://www.xiaohongshu.com/explore/abc123 复制打开"},
        ).json()
        check(detect["platform"] and detect["platform"]["id"] == "xhs", f"平台识别异常: {detect}")
        check(detect["url"].endswith("abc123"), f"链接提取异常: {detect}")

        unknown = client.post("/api/parse", json={"text": "https://example.com/post/1"})
        check(unknown.status_code == 400, f"未知平台应返回 400，实际 {unknown.status_code}")

        no_link = client.post("/api/clean", json={"text": "这里没有链接"})
        check(no_link.status_code == 400, "无链接的文案清洗应返回 400")

        check(client.get("/api/tasks").json()["tasks"] == [], "任务列表初始应为空")
        check(isinstance(client.get("/api/history").json()["entries"], list), "历史接口异常")
        check(client.get("/api/fs/list").status_code == 200, "目录浏览接口异常")

    print("smoke test passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
