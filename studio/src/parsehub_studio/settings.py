"""应用设置持久化。"""

from __future__ import annotations

import json
from pathlib import Path
from urllib.parse import urlparse

from platformdirs import user_config_dir, user_data_dir
from pydantic import BaseModel, Field, ValidationError, field_validator

from .naming import DEFAULT_FILE, DEFAULT_FOLDER, validate_template

APP_NAME = "ParseHubStudio"


class Bookmark(BaseModel):
    name: str = Field(min_length=1, max_length=100)
    url: str = Field(max_length=2000)
    group: str = Field(default="通用", max_length=100)

    @field_validator("url")
    @classmethod
    def valid_url(cls, value):
        parsed = urlparse(value)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
            raise ValueError("收藏地址必须是 HTTP(S) 网页，且不能包含账户密码")
        return value


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


class AuthorAlias(BaseModel):
    platform: str = Field(min_length=1, max_length=100)
    account: str = Field(min_length=1, max_length=200)
    creator: str = Field(min_length=1, max_length=100)
    match_by: str = "id"

    @field_validator("platform", "account", "creator")
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError("作者映射字段不能为空")
        return value.strip()

    @field_validator("match_by")
    @classmethod
    def valid_match(cls, value):
        if value not in {"id", "name"}:
            raise ValueError("请选择作者 ID 或作者昵称")
        return value


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
    """proxy_mode 为 custom 时使用的代理地址"""
    ffmpeg_dir: str = ""
    folder_template: str = DEFAULT_FOLDER
    file_template: str = DEFAULT_FILE
    merge_author_folders: bool = True
    author_aliases: list[AuthorAlias] = Field(default_factory=list, max_length=500)
    bookmarks: list[Bookmark] = Field(default_factory=lambda: [
        Bookmark(name="KuKuTool", url="https://dy.kukutool.com/", group="抖音")
    ], max_length=200)

    @field_validator("folder_template", "file_template")
    @classmethod
    def valid_template(cls, value):
        return validate_template(value)


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
