"""应用设置持久化。"""

from __future__ import annotations

import json
from pathlib import Path

from platformdirs import user_config_dir, user_data_dir
from pydantic import BaseModel, Field, ValidationError

APP_NAME = "ParseHubStudio"


def config_dir() -> Path:
    """应用配置目录 (与 CLI 的 parsehub 配置分开存放)。"""
    return Path(user_config_dir(APP_NAME))


def data_dir() -> Path:
    """应用数据目录 (历史记录等)。"""
    return Path(user_data_dir(APP_NAME))


def _default_save_dir() -> Path:
    downloads = Path.home() / "Downloads"
    base = downloads if downloads.is_dir() else Path.home()
    return base / "ParseHub"


class Settings(BaseModel):
    """应用设置。"""

    default_save_dir: Path = Field(default_factory=_default_save_dir)
    connections: int = Field(default=4, ge=1, le=32)
    save_metadata: bool = False
    theme: str = "dark"
    open_after_download: bool = False
    preview_mode: bool = True
    """预览模式：只加载缩略图，不拉取原文件"""
    download_quality: str = "origin"
    """下载规格: origin 原图/原视频, thumb 预览图"""
    proxy_mode: str = "system"
    """网络代理: system 跟随系统, custom 自定义, none 直连"""
    proxy_url: str = ""
    ffmpeg_dir: str = ""
    """proxy_mode 为 custom 时使用的代理地址"""


class SettingsStore:
    """读写 settings.json。"""

    def __init__(self, path: Path | None = None) -> None:
        self.path = Path(path) if path is not None else config_dir() / "settings.json"

    def load(self) -> Settings:
        if not self.path.exists():
            return Settings()
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return Settings()
        if not isinstance(data, dict):
            return Settings()
        try:
            return Settings.model_validate(data)
        except ValidationError:
            return Settings()

    def save(self, settings: Settings) -> Settings:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(
            json.dumps(settings.model_dump(mode="json"), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return settings
