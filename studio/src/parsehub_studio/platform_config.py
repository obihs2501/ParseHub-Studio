"""桥接 ParseHub CLI 的代理 / Cookie 配置，让界面与命令行共用同一份配置。"""

from __future__ import annotations

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
        resolved_cookie = cookie or (self.cookie_store.get(platform) if platform else None)
        return resolved_parse_proxy, resolved_download_proxy, resolved_cookie
