"""抖音「真·原画」：用 ``ratio=default`` 拿未转码的原始上传档。

``/aweme/v1/play/`` 的 ``ratio`` 参数决定返回哪一档：

    ratio=1080p / 720p / origin -> 转码后的 ``ve`` 桶（小）
    ratio=default               -> 原始上传档 ``v`` 桶（真原画，可能几 GB）

接口给的 ``bit_rate[]`` 最高档只是「转码后最清晰的一档」，原画比它还大（同一分辨率下
体积可能差几十倍），所以原画要单独解析，并插到候选列表最前面（``parse_video_info`` 里
原画档排在第一位，前端画质芯片默认选它）。

``line`` 轮询多条 CDN 线路：同一份内容有多个落点，主线路坏了还能换。这里对每条线路的
落点做一次 ``Range: bytes=0-0`` 探测 —— 既能拿到文件大小（芯片上显示体积），也顺带把
「探不通的线路」排到后面（冷存储节点证书过期/不响应时不会让它当主线路）。

本模块只做「补充」：解析永远不因为它失败而失败，任何异常都返回 0。
``PARSEHUB_DOUYIN_ORIGIN=0`` 可整体关闭（与 ``PARSEHUB_DOUYIN_BROWSER`` 同风格）。

参考实现（用户自用工具，已实测）：``C:/softwares/claudecode/douyin/dy_dl.py`` 的
``resolve_origin`` / ``origin_kind`` / ``_probe_size``。
"""

import asyncio
import os
import re
import time

import httpx

from .douyin import PLAY_USER_AGENT

__all__ = [
    "ORIGIN_ENV",
    "ORIGIN_LINES",
    "PLAY_API",
    "build_origin_bit_rate",
    "enhance_with_origin",
    "origin_dimensions",
    "origin_kind",
    "pick_origin_uri",
    "probe_size",
    "resolve_origin",
]

# 原画解析入口：注意不是移动端那个 aweme.snssdk.com 域名（那条只给转码档）。
PLAY_API = "https://www.douyin.com/aweme/v1/play/"

# 线路轮询顺序（与用户工具的实测顺序一致）：多拿几条落点做下载时的故障切换。
ORIGIN_LINES = (0, 2, 5, 3, 1, 4)

# 原画文件桶：``v`` = 原始档，``ve`` = 转码档；``0015c800`` 是 8K 档。
EIGHT_K_BUCKET = "tos-cn-v-0015c800"
ORIGIN_BUCKET_PREFIX = "tos-cn-v-0015c"

# 8K 档的长边（原画分辨率未知时按这个抬，保持横竖方向）。
EIGHT_K_LONG_SIDE = 7680

# 估算码率的上限。原画档接口不报码率，只能按「体积 / 时长」估 —— 几 GB 的短片（抖音常见）
# 能估出 10^9~10^10 bps，而客户端的 ``MediaVariant.bitrate`` 是 **Int**（32 位），超过 2^31-1
# 会让前端解码**整份解析结果**失败（kotlinx 对超范围数字直接抛异常，coerceInputValues 兜不住）。
# 这个值只用于排序兜底，没有展示意义，压到 1 Gbps 足够且安全。
MAX_ESTIMATED_BITRATE = 1_000_000_000

REFERER = "https://www.douyin.com/"
LINE_TIMEOUT = 15.0
PROBE_TIMEOUT = 20.0

# 与浏览器开关同风格：``0|false|off|no`` 表示禁用。
ORIGIN_ENV = "PARSEHUB_DOUYIN_ORIGIN"
DISABLED_VALUES = {"0", "false", "off", "no"}

# uri 是抖音的视频标识（实测形如 ``v0300fg10000c...``），只允许这种字符集，
# 避免把奇怪的值拼进 query。
URI_PATTERN = re.compile(r"[A-Za-z0-9_\-]{10,64}")

CONTENT_RANGE_PATTERN = re.compile(r"bytes\s+\d+-\d+/(\d+)", re.IGNORECASE)

# 解析结果按 aweme_id 缓存 ``CACHE_TTL`` 秒（含「没解析到原画」这个结果）。
# **必须缓存**：前端选画质传的是下标（``quality="vN"``），而下载任务会把作品重新解析一遍 ——
# 如果第一次解析到原画、第二次没解析到（网络抖动），候选列表整体前移一位，用户选的「原画」
# 就会变成另一条流。缓存住结果（成功或失败都算）才能保证两次的档位列表一致，下标不错位。
# 这与 ``douyin_browser`` 的档位缓存同一个理由。
CACHE_TTL = 600.0
CACHE_LIMIT = 32

_cache: dict[str, tuple[float, dict | None]] = {}


def _is_disabled() -> bool:
    return os.environ.get(ORIGIN_ENV, "").strip().lower() in DISABLED_VALUES


def origin_kind(url: str) -> str | None:
    """判定落点属于哪个原画桶：``8K`` / ``原画`` / ``None``（不是原画桶）。"""
    value = url or ""
    if EIGHT_K_BUCKET in value:
        return "8K"
    if ORIGIN_BUCKET_PREFIX in value:
        return "原画"
    return None


def pick_origin_uri(video: dict) -> str:
    """取用于解析原画的 uri：优先顶层 ``play_addr.uri``，否则码率最高档的 uri。"""
    top = video.get("play_addr")
    if isinstance(top, dict) and (uri := str(top.get("uri") or "")):
        return uri
    best_uri, best_bitrate = "", -1
    for item in video.get("bit_rate") or []:
        if not isinstance(item, dict):
            continue
        address = item.get("play_addr")
        candidate = str((address or {}).get("uri") or "") if isinstance(address, dict) else ""
        bitrate = _as_int(item.get("bit_rate"))
        if candidate and bitrate >= best_bitrate:
            best_uri, best_bitrate = candidate, bitrate
    return best_uri


def origin_dimensions(kind: str, width: int, height: int) -> tuple[int, int]:
    """原画的分辨率：8K 档抬到 8K（保持横竖方向），其它沿用当前最高档。

    原画是未转码的原始上传档，分辨率不会低于转码档；实测 8K 桶的档位在网页最高档
    （2160x3840）之上，所以按长边 7680 抬一档，避免它在芯片上跟 4K 档同名。
    """
    if kind == "8K":
        if height > width:
            return 4320, EIGHT_K_LONG_SIDE
        return EIGHT_K_LONG_SIDE, 4320
    return width, height


def _as_int(value: object) -> int:
    if isinstance(value, bool):
        return int(value)
    try:
        return int(float(str(value)))
    except (TypeError, ValueError, OverflowError):
        return 0


def _client(proxy: str | None, timeout: float) -> httpx.AsyncClient:
    return httpx.AsyncClient(proxy=proxy, trust_env=False, timeout=timeout)


async def _resolve_line(client: httpx.AsyncClient, uri: str, line: int) -> str:
    """单条线路的落点；拿不到返回空串。"""
    try:
        response = await client.get(
            PLAY_API,
            params={"video_id": uri, "ratio": "default", "line": str(line), "watermark": "0"},
            headers={"User-Agent": PLAY_USER_AGENT, "Referer": REFERER},
            follow_redirects=False,
        )
    except httpx.HTTPError:
        return ""
    return response.headers.get("location") or ""


async def probe_size(client: httpx.AsyncClient, url: str) -> int:
    """``Range: bytes=0-0`` 探测最终文件大小；探不到返回 0（不影响原画可用）。

    用 ``stream`` 只读响应头、不读 body：节点若忽略 ``Range`` 而回整个文件（原画可能几 GB），
    普通 ``get`` 会把它整个读进内存。另外**只认 2xx**：坏线路返回 500 时带的错误页也有
    ``Content-Length``，把它当体积会让这条线路排在探得通的线路前面，芯片上还会显示假体积。
    """
    headers = {"User-Agent": PLAY_USER_AGENT, "Referer": REFERER, "Range": "bytes=0-0"}
    try:
        async with client.stream("GET", url, headers=headers) as response:
            if response.status_code not in (200, 206):
                return 0
            if match := CONTENT_RANGE_PATTERN.search(response.headers.get("content-range") or ""):
                return _as_int(match.group(1))
            return _as_int(response.headers.get("content-length"))
    except httpx.HTTPError:
        return 0


async def resolve_origin(uri: str, *, proxy: str | None = None) -> tuple[list[str], int]:
    """用 ``ratio=default`` 拿原画直链，返回 ``(各线路落点, 文件大小)``。

    落点顺序即下载时的尝试顺序：先探通（探到大小）的线路在前，探不通的排后面兜底 ——
    冷存储节点证书过期时不会当主线路。全部探不通也会返回原始落点（大小为 0），
    让下载器自己去试。
    """
    if not uri or not URI_PATTERN.fullmatch(uri):
        return [], 0

    async with _client(proxy, LINE_TIMEOUT) as client:
        locations = await asyncio.gather(*(_resolve_line(client, uri, line) for line in ORIGIN_LINES))
    urls: list[str] = []
    for location in locations:
        if location and location not in urls:
            urls.append(location)
    if not urls:
        return [], 0

    async with _client(proxy, PROBE_TIMEOUT) as client:
        sizes = await asyncio.gather(*(probe_size(client, url) for url in urls))
    probed = [(url, size) for url, size in zip(urls, sizes, strict=True) if size > 0]
    if not probed:
        return urls, 0
    working = {url for url, _ in probed}
    return [url for url, _ in probed] + [url for url in urls if url not in working], probed[0][1]


def _top_dimensions(video: dict) -> tuple[int, int]:
    """当前最高档（面积最大）的分辨率；没有可用档位返回 ``(0, 0)``。"""
    addresses: list[dict] = []
    if isinstance(top := video.get("play_addr"), dict):
        addresses.append(top)
    for item in video.get("bit_rate") or []:
        if isinstance(item, dict) and isinstance(address := item.get("play_addr"), dict):
            addresses.append(address)
    best_area, best = 0, (0, 0)
    for address in addresses:
        width, height = _as_int(address.get("width")), _as_int(address.get("height"))
        if width > 0 and height > 0 and width * height > best_area:
            best_area, best = width * height, (width, height)
    return best


def build_origin_bit_rate(
    video: dict, uri: str, urls: list[str], size: int, kind: str, *, width: int, height: int
) -> dict:
    """合成一条原画 ``bit_rate[]`` 条目（结构对齐接口原生的 snake_case 档位）。"""
    top_bitrate, bytevc1 = 0, 0
    for item in video.get("bit_rate") or []:
        if not isinstance(item, dict):
            continue
        bitrate = _as_int(item.get("bit_rate"))
        if bitrate > top_bitrate:
            top_bitrate, bytevc1 = bitrate, 1 if item.get("is_bytevc1") else 0
    duration_ms = _as_int(video.get("duration"))
    # 码率按「体积 / 时长」估算（原画档接口不报码率），单位 bits/s；上限见 MAX_ESTIMATED_BITRATE。
    estimated = int(size * 8 / (duration_ms / 1000)) if size and duration_ms else 0
    estimated = min(estimated, MAX_ESTIMATED_BITRATE)
    return {
        "gear_name": kind,
        "bit_rate": estimated or top_bitrate,
        "is_bytevc1": bytevc1,
        "quality_type": "origin",
        "play_addr": {
            "uri": uri,
            "url_list": urls,
            "width": width,
            "height": height,
            "data_size": size,
        },
    }


def _cache_get(aweme_id: str) -> tuple[bool, dict | None]:
    """返回 ``(是否命中, 原画条目或 None)``；过期的条目顺手清掉。"""
    item = _cache.get(aweme_id)
    if item is None:
        return False, None
    stamp, entry = item
    if time.monotonic() - stamp > CACHE_TTL:
        _cache.pop(aweme_id, None)
        return False, None
    return True, entry


def _cache_put(aweme_id: str, entry: dict | None) -> None:
    if len(_cache) >= CACHE_LIMIT:
        oldest = min(_cache, key=lambda key: _cache[key][0])
        _cache.pop(oldest, None)
    _cache[aweme_id] = (time.monotonic(), entry)


async def enhance_with_origin(json_dict: dict, *, proxy: str | None = None) -> int:
    """把「真·原画」档插进 ``aweme_detail.video.bit_rate[]`` 最前面，返回插入条数（0/1）。

    只在「是视频」时动手；图文/实况没有顶层 video 原画，直接跳过。任何异常都返回 0 ——
    原画是锦上添花，不能拖垮解析。解析结果（含「没解析到」）按 ``aweme_id`` 缓存，
    保证「解析」与「下载前重新解析」两次拿到的档位列表一致（下标不错位，见 ``CACHE_TTL``）。
    """
    try:
        if _is_disabled():
            return 0
        payload = json_dict.get("aweme_detail") if isinstance(json_dict, dict) else None
        if not payload:
            return 0
        if payload.get("images") or payload.get("image_post_info"):
            return 0
        video = payload.get("video")
        if not isinstance(video, dict):
            return 0

        aweme_id = str(payload.get("aweme_id") or "")
        hit, entry = _cache_get(aweme_id) if aweme_id else (False, None)
        if not hit:
            uri = pick_origin_uri(video)
            urls, size = await resolve_origin(uri, proxy=proxy)
            entry = None
            if urls:
                kind = origin_kind(urls[0]) or "原画"
                width, height = origin_dimensions(kind, *_top_dimensions(video))
                entry = build_origin_bit_rate(video, uri, urls, size, kind, width=width, height=height)
            if aweme_id:
                _cache_put(aweme_id, entry)
        if not entry:
            return 0

        bucket = video.get("bit_rate")
        if not isinstance(bucket, list):
            bucket = []
            video["bit_rate"] = bucket
        # 缓存里的条目会被插进另一次解析的 video，浅拷一层再放，避免被就地改动
        bucket.insert(0, {**entry, "play_addr": dict(entry["play_addr"])})
        return 1
    except Exception:  # noqa: BLE001 —— 原画路径绝不允许拖垮解析
        return 0
