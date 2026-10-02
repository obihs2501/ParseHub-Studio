from __future__ import annotations

import hashlib
import re
import string
from datetime import UTC, datetime
from email.utils import parsedate_to_datetime
from urllib.parse import parse_qs, urlparse

TOKENS = {
    "platform": "平台",
    "author": "作者",
    "title": "标题",
    "id": "作品 ID",
    "date": "下载日期",
    "publish_date": "发布日期",
    "time": "下载时间",
    "index": "媒体序号",
    "type": "媒体类型",
}
DEFAULT_FOLDER = "{platform}_{title}_{id}"
DEFAULT_FILE = "{index:03d}_{title}"


def validate_template(template: str) -> str:
    if not template.strip() or len(template) > 200:
        raise ValueError("命名模板不能为空，且不能超过 200 字符")
    try:
        for literal, field, spec, conversion in string.Formatter().parse(template):
            if any(char in literal for char in '/\\:<>"|?*'):
                raise ValueError("模板不能包含目录分隔符或文件名非法字符")
            if field is None:
                continue
            if field not in TOKENS or conversion:
                raise ValueError(f"不支持的命名元素: {field}")
            if spec and not (field == "index" and re.fullmatch(r"0?[1-6]d", spec)):
                raise ValueError("仅序号支持格式，例如 {index:03d}")
    except ValueError as error:
        raise ValueError(f"命名模板无效: {error}") from error
    return template


def safe_name(value: str, limit: int = 100) -> str:
    value = re.sub(r'[\x00-\x1f\x7f<>:"/\\|?*]', "_", value)
    value = re.sub(r"\s+", " ", value).strip(" .") or "未命名"
    value = value.encode("utf-16-le")[: limit * 2].decode("utf-16-le", errors="ignore").rstrip(" .")
    if value.split(".")[0].upper() in {
        "CON",
        "PRN",
        "AUX",
        "NUL",
        *(f"COM{number}" for number in range(1, 10)),
        *(f"LPT{number}" for number in range(1, 10)),
    }:
        value = "_" + value
    return value


def publication_date(value, timezone) -> str:
    try:
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            if value <= 0:
                return "未知发布日期"
            published = datetime.fromtimestamp(value / 1000 if value > 100_000_000_000 else value, tz=timezone)
        elif isinstance(value, str) and value.strip():
            value = value.strip()
            if value.isdigit():
                return publication_date(int(value), timezone)
            try:
                published = datetime.fromisoformat(value.replace("Z", "+00:00"))
            except ValueError:
                published = parsedate_to_datetime(value)
            if published.tzinfo:
                published = published.astimezone(timezone)
        else:
            return "未知发布日期"
        return published.strftime("%Y-%m-%d")
    except (ValueError, TypeError, OverflowError, OSError):
        return "未知发布日期"


def naming_values(payload: dict, now: datetime | None = None) -> dict:
    now = now or datetime.now(UTC).astimezone()
    raw = payload.get("raw_url") or ""
    parsed = urlparse(raw)
    query = parse_qs(parsed.query)
    post_id = payload.get("post_id") or next(
        (query[key][0] for key in ("modal_id", "aweme_id", "id", "v") if query.get(key)), None
    )
    post_id = post_id or parsed.path.rstrip("/").split("/")[-1] or hashlib.sha256(raw.encode()).hexdigest()[:10]
    title = payload.get("title") or (payload.get("content") or "").strip().split("\n")[0] or "无标题"
    return {
        "platform": (payload.get("platform") or {}).get("name") or "未知平台",
        "author": payload.get("author") or "未知作者",
        "title": safe_name(title, 40),
        "id": safe_name(str(post_id), 32),
        "date": now.strftime("%Y-%m-%d"),
        "publish_date": publication_date(payload.get("published_at"), now.tzinfo),
        "time": now.strftime("%H%M%S"),
        "index": 1,
        "type": payload.get("type_label") or "图片",
    }


def render_name(template: str, values: dict) -> str:
    validate_template(template)
    return safe_name(template.format_map(values))


def apply_naming(result, payload: dict, folder: str, filename: str, index_offset: int = 0):
    values = naming_values(payload)
    result.name = render_name(folder, values)
    used = set()

    def file_name(index, media, video):
        from .serialize import KIND_LABELS, media_kind

        tokens = {**values, "index": index + index_offset, "type": "视频" if video else KIND_LABELS[media_kind(media)]}
        base = render_name(filename, tokens) + ("_live" if video else "")
        candidate = base
        number = 2
        while candidate.casefold() in used:
            candidate = f"{base}_{number}"
            number += 1
        used.add(candidate.casefold())
        extension = media.video_ext if video else media.ext
        extension = re.sub(r"[^a-zA-Z0-9]", "", extension or "bin") or "bin"
        return f"{candidate}.{extension}"

    result.file_name_builder = file_name
