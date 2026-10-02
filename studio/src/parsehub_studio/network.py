"""网络与代理：读取系统代理并判断可用性。

Python 的 httpx 只会读取 ``HTTP_PROXY`` / ``HTTPS_PROXY`` 环境变量，并不会使用
Windows「Internet 选项」里的系统代理。国内直连不少平台（抖音、小红书等）会失败，
因此这里显式读取系统代理并作为默认值使用。
"""

from __future__ import annotations

import os
import socket
from urllib.parse import urlparse

PROXY_MODES = {"system", "custom", "none"}


def normalize_proxy(value: str | None) -> str | None:
    """把各种写法的代理地址规范成 URL。"""
    if not value:
        return None
    text = value.strip()
    if not text:
        return None
    if "=" in text:
        parts = dict(item.split("=", 1) for item in text.split(";") if "=" in item)
        text = (parts.get("https") or parts.get("http") or "").strip()
    if not text:
        return None
    if "://" not in text:
        text = "http://" + text
    parsed = urlparse(text)
    if not parsed.hostname:
        return None
    return text


def detect_system_proxy() -> str | None:
    """读取系统代理，读不到时回退到环境变量。"""
    if os.name == "nt":
        value = _read_windows_proxy()
        if value:
            return value
    for name in ("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy", "ALL_PROXY", "all_proxy"):
        candidate = normalize_proxy(os.environ.get(name))
        if candidate:
            return candidate
    return None


def _read_windows_proxy() -> str | None:
    try:
        import winreg

        with winreg.OpenKey(
            winreg.HKEY_CURRENT_USER,
            r"Software\Microsoft\Windows\CurrentVersion\Internet Settings",
        ) as key:
            enabled, _ = winreg.QueryValueEx(key, "ProxyEnable")
            if not enabled:
                return None
            server, _ = winreg.QueryValueEx(key, "ProxyServer")
    except OSError:
        return None
    except ImportError:
        return None
    return normalize_proxy(server if isinstance(server, str) else None)


def proxy_reachable(proxy: str | None, timeout: float = 0.6) -> bool:
    """代理端口是否可连接（Clash 没开时不至于让所有请求失败）。"""
    if not proxy:
        return False
    parsed = urlparse(proxy)
    host = parsed.hostname
    if not host:
        return False
    if parsed.port:
        port = parsed.port
    elif parsed.scheme in {"https"}:
        port = 443
    elif parsed.scheme.startswith("socks"):
        port = 1080
    else:
        port = 80
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def resolve_default_proxy(mode: str, custom: str | None) -> str | None:
    """根据设置推导默认代理，不在事件循环内探测 TCP 连接。"""
    if mode == "none":
        return None
    if mode == "custom":
        candidate = normalize_proxy(custom)
    else:
        candidate = detect_system_proxy()
    if not candidate:
        return None
    return candidate
