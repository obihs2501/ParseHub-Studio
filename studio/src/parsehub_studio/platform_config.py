"""桥接 ParseHub CLI 的代理 / Cookie 配置，让界面与命令行共用同一份配置。"""

from __future__ import annotations

import json
import uuid
from pathlib import Path
from typing import Any, Literal

from parsehub.cli_config import ConfigStore, FileCookieStore

ProxyTarget = Literal["parse", "download", "all"]


def mask_secret(value: str | None) -> str | None:
    if not value:
        return None
    if len(value) <= 10:
        return "•" * len(value)
    return f"{value[:4]}{'•' * 6}{value[-4:]}"


class PlatformConfigBridge:
    """包装 ConfigStore / FileCookieStore。"""

    def __init__(self, config_store: ConfigStore | None = None, cookie_store: FileCookieStore | None = None) -> None:
        self.config_store = config_store or ConfigStore()
        self.cookie_store = cookie_store or FileCookieStore()
        self.pool_path = self.cookie_store.path.with_name("studio_cookie_pool.json")
        self._positions: dict[str, int] = {}

    def _pool(self) -> dict:
        try:
            value = json.loads(self.pool_path.read_text(encoding="utf-8"))
            return value if isinstance(value, dict) else {}
        except (OSError, ValueError):
            return {}

    def _save_pool(self, pool: dict) -> None:
        self.pool_path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.pool_path.with_suffix(".tmp")
        temporary.write_text(json.dumps(pool, ensure_ascii=False), encoding="utf-8")
        temporary.replace(self.pool_path)

    def pool_entries(self, platform: str) -> list[dict]:
        return [{"id": item["id"], "name": item["name"], "enabled": item["enabled"],
                 "hint": mask_secret(item["cookie"])} for item in self._pool().get(platform, [])]

    def add_cookie(self, platform: str, cookie: str, name: str) -> None:
        pool = self._pool()
        entries = pool.setdefault(platform, [])
        if not any(item["cookie"] == cookie for item in entries):
            entries.append({"id": uuid.uuid4().hex, "name": name or f"账户 {len(entries) + 1}",
                            "cookie": cookie, "enabled": True})
        self._save_pool(pool)

    def change_cookie(self, platform: str, entry_id: str, enabled: bool | None) -> None:
        pool = self._pool()
        entries = pool.get(platform, [])
        if enabled is None:
            pool[platform] = [item for item in entries if item["id"] != entry_id]
        else:
            for item in entries:
                if item["id"] == entry_id:
                    item["enabled"] = enabled
        self._save_pool(pool)

    def next_cookie(self, platform: str) -> str | None:
        entries = [item for item in self._pool().get(platform, []) if item["enabled"]]
        if not entries:
            return self.cookie_store.get(platform)
        position = self._positions.get(platform, 0)
        self._positions[platform] = position + 1
        return entries[position % len(entries)]["cookie"]

    @property
    def config_path(self) -> Path:
        return self.config_store.path

    @property
    def cookie_path(self) -> Path:
        return self.cookie_store.path

    def describe(self, platform: str) -> dict[str, Any]:
        item = self.config_store.get_platform(platform)
        cookie = self.cookie_store.get(platform)
        return {
            "parse_proxy": item.parse_proxy,
            "download_proxy": item.download_proxy,
            "cookie_set": bool(cookie),
            "cookie_hint": mask_secret(cookie),
            "cookie_length": len(cookie) if cookie else 0,
            "cookie_pool": self.pool_entries(platform),
        }

    def set_proxy(self, platform: str, proxy: str, target: ProxyTarget = "all") -> None:
        self.config_store.set_proxy(platform, proxy, target)

    def clear_proxy(self, platform: str, target: ProxyTarget = "all") -> bool:
        return self.config_store.clear_proxy(platform, target)

    def set_cookie(self, platform: str, cookie: str) -> None:
        self.cookie_store.set(platform, cookie)

    def clear_cookie(self, platform: str) -> bool:
        return self.cookie_store.delete(platform)

    def resolve(
        self,
        platform: str | None,
        *,
        proxy: str | None = None,
        parse_proxy: str | None = None,
        cookie: str | None = None,
        default_proxy: str | None = None,
    ) -> tuple[str | None, str | None, str | None]:
        """把界面上传的参数与已保存的平台配置合并。

        :param default_proxy: 平台未配置代理时使用的全局默认代理

        :return: (解析代理, 下载代理, 解析 Cookie)
        """
        saved = self.config_store.get_platform(platform) if platform else None
        resolved_parse_proxy = parse_proxy or (saved.parse_proxy if saved else None) or default_proxy
        resolved_download_proxy = proxy or (saved.download_proxy if saved else None) or default_proxy
        resolved_cookie = cookie or (self.next_cookie(platform) if platform else None)
        return resolved_parse_proxy, resolved_download_proxy, resolved_cookie
