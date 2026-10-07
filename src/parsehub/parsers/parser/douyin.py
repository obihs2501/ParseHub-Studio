import asyncio
import re
from dataclasses import dataclass, field
from enum import Enum
from pathlib import Path
from typing import Self, Union
from urllib.parse import parse_qs, urlparse

import httpx

from ... import ProgressCallback
from ...provider_api.douyin import DouyinMobileCrawler, DouyinMobileDevice, DouyinWebCrawler
from ...provider_api.douyin_browser import enhance_with_browser
from ...provider_api.douyin_origin import enhance_with_origin
from ...types import (
    DownloadResult,
    ImageParseResult,
    ImageRef,
    LivePhotoRef,
    MultimediaParseResult,
    ParseError,
    ParseResult,
    Platform,
    VideoParseResult,
    VideoRef,
)
from ...utils.helpers import UA, match_url
from ..base.base import BaseParser


class DouyinParser(BaseParser):
    __platform__ = Platform.DOUYIN
    __supported_type__ = ["视频", "图文", "日常"]
    __match__ = r"^(http(s)?://)?.+douyin.com/(?!share/user|qishui).+"
    __redirect_keywords__ = ["v.douyin", "iesdouyin"]
    __reserved_parameters__ = ["modal_id"]

    @staticmethod
    def _canonical_url(url: str) -> str | None:
        parsed = urlparse(url)
        if parsed.hostname not in {"www.douyin.com", "douyin.com", "www.iesdouyin.com", "iesdouyin.com"}:
            return None
        matched = re.search(r"/(?:share/)?(video|note|slides)/(\d+)(?:/|$)", parsed.path)
        if matched:
            kind = "note" if matched[1] in {"note", "slides"} else "video"
            return f"https://www.douyin.com/{kind}/{matched[2]}"
        query = parse_qs(parsed.query)
        for key in ("modal_id", "aweme_id", "vid"):
            value = query.get(key, [""])[0]
            if value.isdigit():
                return f"https://www.douyin.com/video/{value}"
        return None

    async def get_raw_url(self, url: str, *, clean_all: bool = False, headers: dict | None = None) -> str:
        matched = match_url(url) or url
        if canonical := self._canonical_url(matched):
            return canonical
        if urlparse(matched).hostname != "v.douyin.com":
            return await super().get_raw_url(matched, clean_all=clean_all, headers=headers)
        for attempt in range(3):
            current = matched
            try:
                async with httpx.AsyncClient(proxy=self.proxy, trust_env=False, timeout=15) as client:
                    for _hop in range(8):
                        if canonical := self._canonical_url(current):
                            return canonical
                        async with client.stream(
                            "GET", current, headers={"User-Agent": UA} if headers is None else headers
                        ) as response:
                            if not response.is_redirect:
                                response.raise_for_status()
                                break
                            current = str(response.url.join(response.headers["location"]))
                            parsed = urlparse(current)
                            if parsed.scheme not in {"https", "http"} or parsed.hostname not in {
                                "v.douyin.com",
                                "www.douyin.com",
                                "douyin.com",
                                "www.iesdouyin.com",
                                "iesdouyin.com",
                            }:
                                raise ParseError("抖音短链返回了非预期的跳转地址")
                raise ParseError("抖音短链未返回作品 ID，链接可能已失效")
            except httpx.HTTPError as error:
                if attempt == 2:
                    detail = str(error) or "连接中断或超时"
                    raise ParseError(f"获取抖音原始链接失败 ({type(error).__name__}): {detail}") from error
                await asyncio.sleep(attempt + 1)
        raise ParseError("获取抖音原始链接失败")

    async def _do_parse(self, raw_url: str) -> Union["VideoParseResult", "ImageParseResult", "MultimediaParseResult"]:
        result = await self._fetch_api_result(raw_url)

        match result.type:
            case DouyinMediaType.VIDEO:
                parsed = self._build_video_result(result)
            case DouyinMediaType.IMAGE:
                parsed = self._build_image_result(result)
        parsed.author = result.author
        parsed.author_id = result.author_id
        parsed.published_at = result.published_at
        parsed.post_id = result.post_id
        return parsed

    async def _fetch_api_result(self, raw_url: str) -> "DouyinApiResult":
        """获取并解析抖音 API 结果"""
        web_cookie = self.cookie.get_value() or {}

        web_error: ParseError | None = None
        if web_cookie:
            try:
                web_crawler = DouyinWebCrawler(proxy=self.proxy, cookie=web_cookie)
                response = await web_crawler.parse(raw_url)
                # 网页接口给的仍是转码后最高档；真原画（ratio=default）要单独解析（失败静默跳过）
                await enhance_with_origin(response, proxy=self.proxy)
                return DouyinApiResult.parse(response)
            except ParseError as e:
                web_error = e

        try:
            mobile_device = DouyinMobileDevice.resolve()
            app_crawler = DouyinMobileCrawler(proxy=self.proxy, device=mobile_device)
            response = await app_crawler.parse(raw_url)
            # 移动端接口常常只给 720P 及以下：用系统浏览器补高清档（内部保证绝不抛异常，失败静默跳过）
            await enhance_with_browser(response, raw_url, proxy=self.proxy)
            # 补完高清档再解析真原画：原画分辨率取「当前最高档」，浏览器补的 4K 档要算进去
            await enhance_with_origin(response, proxy=self.proxy)
            return DouyinApiResult.parse(response)
        except ParseError as e:
            mobile_error = e

        if web_error:
            raise ParseError(f"{web_error}；移动端解析也失败: {mobile_error}") from mobile_error
        raise mobile_error

    @staticmethod
    def _build_video_result(result: "DouyinApiResult") -> VideoParseResult:
        """构建视频解析结果"""
        if result.video is None:
            raise ParseError("抖音解析失败: 未获取到视频")
        return DouyinVideoParseResult(
            title=result.desc,
            video=result.video,
        )

    @staticmethod
    def _build_image_result(result: "DouyinApiResult") -> ImageParseResult:
        """构建图片解析结果"""
        return DouyinImageParseResult(
            title=result.desc,
            photo=result.image_list,
        )


class DouyinParseResult(ParseResult):
    async def _do_download(
        self,
        *,
        output_dir: Path,
        callback: ProgressCallback | None = None,
        callback_args: tuple = (),
        callback_kwargs: dict | None = None,
        proxy: str | None = None,
        headers: dict | None = None,
        connections: int = 4,
    ) -> "DownloadResult":
        headers = {
            "Referer": "https://www.douyin.com/",
        }
        return await super()._do_download(
            output_dir=output_dir,
            callback=callback,
            callback_args=callback_args,
            callback_kwargs=callback_kwargs,
            proxy=proxy,
            headers=headers,
            connections=connections,
        )


class DouyinVideoParseResult(DouyinParseResult, VideoParseResult): ...


class DouyinImageParseResult(DouyinParseResult, ImageParseResult): ...


def remove_video_watermark(url: str) -> str:
    """移除抖音视频水印标识 (playwm -> play)"""
    return url.replace("playwm", "play")


def _http_urls(address: dict) -> list[str]:
    """地址里的全部 http(s) 落点（原画档带多条 CDN 线路，下载时可故障切换）。"""
    return [
        url for url in address.get("url_list") or [] if isinstance(url, str) and url.startswith(("https://", "http://"))
    ]


def _stream_candidates(video_data: dict) -> list[dict]:
    """收集全部可用码流（顶层 play_addr + bit_rate[] 档位），按清晰度从高到低排序。

    不同接口给的档位差别很大：网页接口（需要 cookie）会带 2160P 之类的高清档；
    移动端接口常常只有 720P/540P，而且它的顶层 play_addr 往往只是低清自适应预览
    （实测某条视频是 576x1024 5.4MB，而 bit_rate[] 里明明有 720x1280 的档位）。
    所以不再按接口类型挑来源，统一收集后按「分辨率面积 → 体积 → 码率」排序；
    前端拿到的 ``variants`` 就是这份列表，可以自己选画质。
    """
    candidates: list[dict] = []

    def add(
        address: dict | None,
        gear: str = "",
        bytevc1: int | None = None,
        bitrate: int = 0,
        origin: bool = False,
    ) -> None:
        if not isinstance(address, dict):
            return
        urls = _http_urls(address)
        if not urls:
            return
        candidates.append({
            "url": remove_video_watermark(urls[0]),
            "urls": [remove_video_watermark(url) for url in urls],
            "width": int(address.get("width") or 0),
            "height": int(address.get("height") or 0),
            "size": int(address.get("data_size") or 0),
            "bitrate": int(bitrate or 0),
            "gear": gear,
            "codec": "h265" if bytevc1 else "h264",
            "origin": origin,
        })

    add(video_data.get("play_addr"), gear="play_addr")
    for item in video_data.get("bit_rate") or []:
        if isinstance(item, dict):
            add(
                item.get("play_addr"),
                gear=str(item.get("gear_name") or ""),
                bytevc1=item.get("is_bytevc1"),
                bitrate=item.get("bit_rate") or 0,
                origin=str(item.get("quality_type") or "") == "origin",
            )

    unique: dict[str, dict] = {}
    for item in candidates:
        unique.setdefault(item["url"], item)
    # 原画（ratio=default 的原始上传档）永远排第一：它就是最高档 —— 即使分辨率跟转码最高档
    # 相同，体积也大得多；分辨率未知时也不该被挤到后面（前端画质芯片默认选第一档）。
    return sorted(
        unique.values(),
        key=lambda i: (1 if i["origin"] else 0, i["width"] * i["height"], i["size"], i["bitrate"]),
        reverse=True,
    )


def parse_video_info(video_data: dict) -> dict:
    """选一条最清晰的流，并把全部档位一起返回（``variants``，供前端选画质）。

    不再按接口类型区分来源、也不过滤 bytevc1/H265：抖音的 4K 原档本身就是 H265
    （网页接口 2160x3840 档），过滤掉它等于永远拿不到原画。播放由 VLC 负责（HEVC 支持完整）。
    """
    candidates = _stream_candidates(video_data)
    if not candidates:
        raise ParseError("抖音未返回可用视频地址")
    # 默认取「最高档」但**跳过原画**：原画是未转码的原始档，几 GB 很常见，而批次下载 / 历史 /
    # 灯箱这几条路径没有画质芯片、用户也看不到体积，静默拉一份原始档会一下吃掉几十 GB。
    # 原画仍然排在 variants 最前面（芯片 0），想要就显式选它。
    best = next((item for item in candidates if not item["origin"]), candidates[0])

    cover = video_data.get("cover", {})
    thumb_url_list = cover.get("url_list", [])
    thumb_url = thumb_url_list[-1] if thumb_url_list else None

    return {
        "video_url": best["url"],
        "thumb_url": thumb_url,
        "duration": round((video_data.get("duration") or 0) / 1000),
        "width": best["width"],
        "height": best["height"],
        "variants": candidates,
    }


class DouyinMediaType(Enum):
    """抖音媒体类型"""

    VIDEO = "video"
    IMAGE = "image"  # 实况图片 + 图片


@dataclass
class DouyinApiResult:
    """抖音 API 解析结果"""

    type: DouyinMediaType
    video: VideoRef | None = None
    desc: str = ""
    image_list: list[ImageRef | LivePhotoRef] = field(default_factory=list)
    author: str = ""
    author_id: str = ""
    published_at: int | str = ""
    post_id: str = ""

    @classmethod
    def parse(cls, json_dict: dict) -> Self:
        data = json_dict.get("aweme_detail")
        if not data:
            raise ParseError("抖音解析失败: 未获取到作品详情")

        desc = data.get("desc", "")

        if images := data.get("images"):
            result = cls._parse_images(images, desc)
        elif image_post_info := data.get("image_post_info"):
            result = cls._parse_image_post_info(image_post_info, desc)
        else:
            result = cls._parse_video(data, desc)
        result.author = (data.get("author") or {}).get("nickname") or ""
        result.author_id = str((data.get("author") or {}).get("uid") or "")
        result.published_at = data.get("create_time") or ""
        result.post_id = str(data.get("aweme_id") or "")
        return result

    @classmethod
    def _parse_images(cls, images: list[dict], desc: str) -> Self:
        """解析旧版图片格式 (images 字段)

        支持普通图片和实况照片 (LivePhoto)
        """
        has_live_photos = any(img.get("video") for img in images)

        if has_live_photos:
            image_list: list[ImageRef | LivePhotoRef] = []
            for image in images:
                if video := image.get("video"):
                    video_info = parse_video_info(video)
                    image_list.append(
                        LivePhotoRef(
                            url=video_info["thumb_url"],
                            video_url=video_info["video_url"],
                            width=int(video_info["width"]),
                            height=int(video_info["height"]),
                            duration=int(video_info["duration"]) or 3,
                        )
                    )
                else:
                    url_list = image.get("url_list", [])
                    if url_list:
                        image_list.append(
                            ImageRef(
                                url=url_list[-1],
                                height=image.get("height", 0),
                                width=image.get("width", 0),
                            )
                        )
        else:
            image_list = [
                ImageRef(
                    url=img["url_list"][-1],
                    height=img.get("height", 0),
                    width=img.get("width", 0),
                )
                for img in images
                if img.get("url_list")
            ]

        return cls(
            type=DouyinMediaType.IMAGE,
            desc=desc,
            image_list=image_list,
        )

    @classmethod
    def _parse_image_post_info(cls, image_post_info: dict, desc: str) -> Self:
        """解析新版图片格式 (image_post_info 字段)"""
        images = image_post_info.get("images", [])
        image_list: list[ImageRef | LivePhotoRef] = []

        for image in images:
            display_image = image.get("display_image", {})
            url_list = display_image.get("url_list", [])
            if url_list:
                image_list.append(
                    ImageRef(
                        url=url_list[-1],
                        height=display_image.get("height", 0),
                        width=display_image.get("width", 0),
                    )
                )

        return cls(
            type=DouyinMediaType.IMAGE,
            image_list=image_list,
            desc=desc,
        )

    @classmethod
    def _parse_video(cls, data: dict, desc: str) -> Self:
        """解析视频"""
        video_data = data.get("video")
        if not video_data:
            raise ParseError("抖音解析失败: 未获取到视频数据")

        video_info = parse_video_info(video_data)

        return cls(
            type=DouyinMediaType.VIDEO,
            video=VideoRef(
                url=video_info["video_url"],
                thumb_url=video_info["thumb_url"],
                width=video_info["width"],
                height=video_info["height"],
                duration=video_info["duration"],
                variants=video_info.get("variants"),
            ),
            desc=desc,
        )


__all__ = ["DouyinParser"]
