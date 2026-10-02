"""解析 / 下载历史记录。"""

from __future__ import annotations

import json
import time
import uuid
from pathlib import Path
from typing import Any

from .settings import data_dir

MAX_ENTRIES = 200
MAX_CONTENT_CHARS = 2000


class HistoryStore:
    """以 JSON 文件保存的历史记录。"""

    def __init__(self, path: Path | None = None, limit: int = MAX_ENTRIES) -> None:
        self.path = Path(path) if path is not None else data_dir() / "history.json"
        self.limit = limit

    def load(self) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []
        try:
            data = json.loads(self.path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return []
        if not isinstance(data, list):
            return []
        return [item for item in data if isinstance(item, dict)]

    def add(self, payload: dict[str, Any]) -> dict[str, Any]:
        entry = self._slim(payload)
        entries = self.load()
        entries.insert(0, entry)
        self._write(entries[: self.limit])
        return entry

    def remove(self, entry_id: str) -> bool:
        entries = self.load()
        remaining = [item for item in entries if item.get("id") != entry_id]
        if len(remaining) == len(entries):
            return False
        self._write(remaining)
        return True

    def clear(self) -> None:
        self._write([])

    def _write(self, entries: list[dict[str, Any]]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(entries, ensure_ascii=False, indent=2), encoding="utf-8")

    @staticmethod
    def _slim(payload: dict[str, Any]) -> dict[str, Any]:
        content = payload.get("content") or ""
        if len(content) > MAX_CONTENT_CHARS:
            content = content[:MAX_CONTENT_CHARS] + "…"
        entry = dict(payload)
        entry["content"] = content
        entry.pop("html", None)
        entry["id"] = uuid.uuid4().hex
        entry["time"] = time.time()
        return entry
