"""把 ParseHub 的解析结果转换成前端可用的 JSON。"""

from __future__ import annotations

from dataclasses import asdict
from typing import Any

import bleach
from markdown import markdown as md_to_html
from parsehub.types import AniRef, ImageRef, LivePhotoRef, VideoRef

from parsehub.types.result import RichTextParseResult
from parsehub.utils.media_network import preview_url

MEDIA_KINDS: tuple[tuple[type, str], ...] = (
    (VideoRef, "video"),
    (LivePhotoRef, "livephoto"),
    (AniRef, "ani"),
    (ImageRef, "image"),
)

KIND_LABELS = {
    "video": "视频",
    "image": "图片",
    "ani": "动图",
    "livephoto": "实况照片",
    "unknown": "媒体",
}

TYPE_LABELS = {
    "video": "视频",
    "image": "图文",
    "multimedia": "多媒体",
    "richtext": "长文",
    "unknown": "未知",
}


def sanitize_html(content: str) -> str:
    return bleach.clean(
        content,
        tags={"p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6", "a", "img", "strong", "b",
              "em", "i", "del", "s", "blockquote", "pre", "code", "ul", "ol", "li", "table",
              "thead", "tbody", "tr", "th", "td", "div", "span", "sup", "sub"},
        attributes={"a": ["href", "title"], "img": ["src", "alt", "title"], "td": ["colspan", "rowspan"]},
        protocols={"https", "http"},
        strip=True,
    )


def media_kind(media: Any) -> str:
    for cls, kind in MEDIA_KINDS:
        if isinstance(media, cls):
            return kind
    return "unknown"


def media_to_dict(media: Any) -> dict[str, Any]:
    data = {key: value for key, value in asdict(media).items()}
    kind = media_kind(media)
    data["kind"] = kind
    data["kind_label"] = KIND_LABELS.get(kind, "媒体")
    data["preview_url"] = preview_url(data["url"]) if kind in {"image", "livephoto", "ani"} else data.get("thumb_url")
    if not data.get("thumb_url"):
        data["thumb_url"] = None
    return data


def _media_list(result: Any) -> list[Any]:
    media = getattr(result, "media", None)
    if media is None:
        return []
    if isinstance(media, (list, tuple)):
        return list(media)
    return [media]


def result_to_payload(result: Any) -> dict[str, Any]:
    """把任意 ParseResult 转成前端使用的字典。"""
    media = [media_to_dict(item) for item in _media_list(result)]
    platform = getattr(result, "platform", None)
    post_type = result.type.value
    payload: dict[str, Any] = {
        "platform": (
            {"id": platform.id, "name": platform.display_name} if platform is not None else None
        ),
        "type": post_type,
        "type_label": TYPE_LABELS.get(post_type, post_type),
        "title": result.title,
        "content": result.content,
        "raw_url": getattr(result, "raw_url", "") or "",
        "name": getattr(result, "name", ""),
        "author": getattr(result, "author", ""),
        "post_id": getattr(result, "post_id", ""),
        "media": media,
        "media_count": len(media),
    }
    markdown_content = getattr(result, "markdown_content", None)
    if isinstance(result, RichTextParseResult) or markdown_content:
        payload["markdown"] = markdown_content or ""
        payload["html"] = sanitize_html(md_to_html(markdown_content or "", extensions=["extra", "sane_lists", "nl2br"]))
    return payload
