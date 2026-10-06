"""抖音高清档位补充：用系统浏览器抓 ``/discover`` 页，无需 cookie。

移动端接口常常只给 720P 及以下，网页接口才带 1080P/2160P 档 —— 但网页接口需要 cookie
（而且要过 ``a_bogus`` + ``x-secsdk-web-signature`` 签名）。实测抖音 CDN 直链本身**不校验
cookie**，卡点只在接口签名，所以让真实浏览器执行页面 JS 自己签更省事：

    msedge --headless=new --dump-dom "https://www.douyin.com/discover?modal_id=<aweme_id>"

页面 SSR HTML 里内嵌了 URL 编码的 JSON（camelCase：``uri/dataSize/width/height/playAddr``
``/gearName/isH265/bitRate/fps``），直接抠出来即可拿到 4K 档（如 ``adapt_lowest_4_1``
2160x3840 / 14.4 MB / H265 / 60fps），其 ``playAddr`` 的 URL 无需 cookie 就能下载
（下载必须带 ``Referer: https://www.douyin.com/``，见 ``DouyinParseResult._do_download``）。

注意：必须用 ``/discover?modal_id=``，``/video/{id}`` 的 dump 里一个档位都没有；
viewport 2560x1440 才会触发高画质档。

本模块只做「补充」：解析永远不因为它失败而失败，任何异常都返回 0。抓取结果按 aweme_id
缓存 ``CACHE_TTL`` 秒（下载任务会重新解析一遍，命中缓存才能保证画质芯片下标不错位）；
同一进程内最多同时跑 ``MAX_CONCURRENT_DUMPS`` 个无头浏览器。
"""

import asyncio
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.parse import unquote
from weakref import WeakKeyDictionary

from .douyin import DouyinWebCrawler

__all__ = [
    "enhance_with_browser",
    "extract_discover_gears",
    "fetch_discover_gears",
    "find_browser",
    "gears_to_bit_rate",
    "merge_gears",
]

# 浏览器环境变量：``0|false|off|no`` 表示禁用；其它值视为浏览器 exe 路径覆盖。
BROWSER_ENV = "PARSEHUB_DOUYIN_BROWSER"
DISABLED_VALUES = {"0", "false", "off", "no"}

# 与实测命令一致：Chrome 126 UA + 大 viewport（触发高画质档）。
BROWSER_UA = (
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)
WINDOW_SIZE = "2560,1440"
DISCOVER_URL = "https://www.douyin.com/discover?modal_id={}"

# 档位 JSON 在 HTML 里的 URL 编码标记（``{"uri"``），实测 24 个标记 / 36 个 gearName。
GEAR_MARKER = "%7B%22uri%22"

# 实测 dump 耗时 8~14.2 秒且有波动，超时给 3 倍余量；批次解析整体 180 秒上限
# （studio/src/parsehub_studio/batches.py 的 wait_for），两段 dump 不能吃掉太多预算。
DUMP_TIMEOUT = 45
VIRTUAL_TIME_TIMEOUT = 45
VIRTUAL_TIME_BUDGET = 15000

# 无头浏览器内存和临时 profile 都不小，而批量下载是逐条 create_task、没有并发上限，
# 必须在这里压住并发（按事件循环计数）。
MAX_CONCURRENT_DUMPS = 2

# 抓取结果缓存：下载任务会重新解析一遍，命中缓存既保证画质芯片下标不错位，
# 也避免同一视频短时间内重复起浏览器。
CACHE_TTL = 600.0
CACHE_LIMIT = 32

# 当前最高档短边低于此值才值得去抓（1440 及以上说明接口已经给了高清档）。
MIN_SHORT_SIDE = 1440

PROFILE_PREFIX = "parsehub-douyin-"
PROFILE_STALE_SECONDS = 3600.0

CANDIDATE_PATHS = (
    r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Microsoft\Edge\Application\msedge.exe",
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
)

CANDIDATE_NAMES = ("msedge", "microsoft-edge", "google-chrome", "google-chrome-stable", "chrome", "chromium")


def find_browser() -> str | None:
    """找一个可用的 Chrome/Edge；``PARSEHUB_DOUYIN_BROWSER`` 可禁用或覆盖路径。"""
    override = os.environ.get(BROWSER_ENV, "").strip()
    if override:
        if override.lower() in DISABLED_VALUES:
            return None
        if Path(override).is_file():
            return override
        return shutil.which(override)
    for candidate in CANDIDATE_PATHS:
        if Path(candidate).is_file():
            return candidate
    for name in CANDIDATE_NAMES:
        if found := shutil.which(name):
            return found
    return None


def _as_int(value: object) -> int:
    """页面数据里的数字可能是字符串/浮点串（``"4743884.0"``），坏值一律当 0。"""
    if isinstance(value, bool):
        return int(value)
    try:
        return int(float(str(value)))
    except (TypeError, ValueError, OverflowError):
        return 0


def _balanced_object(text: str, start: int, window: int = 20000) -> dict | None:
    """从 ``text[start:]``（``%7B%22uri%22`` 标记处）抠出平衡花括号 JSON 对象。"""
    decoded = unquote(text[start : start + window])
    begin = decoded.find("{")
    if begin < 0:
        return None
    depth = 0
    in_string = False
    escaped = False
    for index in range(begin, len(decoded)):
        char = decoded[index]
        if escaped:
            escaped = False
            continue
        if char == "\\":
            escaped = True
            continue
        if char == '"':
            in_string = not in_string
            continue
        if in_string:
            continue
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                try:
                    parsed = json.loads(decoded[begin : index + 1])
                except ValueError:
                    return None
                return parsed if isinstance(parsed, dict) else None
    return None


def extract_discover_gears(html: str) -> list[dict]:
    """抠出 ``/discover`` 页里的全部画质档位（按 uri+分辨率+体积去重）。"""
    unique: dict[tuple, dict] = {}
    for start in _find_markers(html):
        gear = _balanced_object(html, start)
        if not gear or not _as_int(gear.get("width")):
            continue
        key = (gear.get("uri"), gear.get("width"), gear.get("height"), gear.get("dataSize"))
        unique.setdefault(key, gear)
    return list(unique.values())


def _find_markers(html: str) -> list[int]:
    positions: list[int] = []
    start = html.find(GEAR_MARKER)
    while start >= 0:
        positions.append(start)
        start = html.find(GEAR_MARKER, start + len(GEAR_MARKER))
    return positions


def _gear_to_bit_rate(gear: object) -> dict | None:
    """单条 camelCase 档位 → snake_case ``bit_rate[]`` 条目；字段不合法返回 None。"""
    if not isinstance(gear, dict):
        return None
    urls = [
        src
        for item in gear.get("playAddr") or []
        if isinstance(item, dict) and isinstance(src := item.get("src"), str) and src.startswith(("http://", "https://"))
    ]
    if not urls:
        return None
    return {
        "gear_name": str(gear.get("gearName") or ""),
        "bit_rate": _as_int(gear.get("bitRate")),
        "is_bytevc1": 1 if _as_int(gear.get("isH265")) else 0,
        "FPS": _as_int(gear.get("fps")),
        "quality_type": _as_int(gear.get("qualityType")),
        "play_addr": {
            "uri": str(gear.get("uri") or ""),
            "url_list": urls,
            "width": _as_int(gear.get("width")),
            "height": _as_int(gear.get("height")),
            "data_size": _as_int(gear.get("dataSize")),
        },
    }


def gears_to_bit_rate(gears: list[dict]) -> list[dict]:
    """camelCase 的 discover 档位 → 接口那套 snake_case ``bit_rate[]`` 结构。

    必备字段对齐 ``_stream_candidates`` 读的键：``gear_name`` / ``bit_rate`` /
    ``is_bytevc1`` / ``play_addr{uri,url_list,width,height,data_size}``；
    ``FPS`` / ``quality_type`` 是接口原生 ``bit_rate[]`` 里也有的字段，一并带上。
    单个档位字段异常（数字是字符串、结构不对）只跳过该档位，不影响其它档位。
    """
    entries: list[dict] = []
    for gear in gears:
        try:
            entry = _gear_to_bit_rate(gear)
        except Exception:  # noqa: BLE001 —— 单个坏档位不拖垮整批档位
            entry = None
        if entry:
            entries.append(entry)
    return entries


def _iter_addresses(video_data: dict) -> list[dict]:
    """顶层 ``play_addr`` + ``bit_rate[].play_addr``（与 ``_stream_candidates`` 同源）。"""
    addresses: list[dict] = []
    if isinstance(top := video_data.get("play_addr"), dict):
        addresses.append(top)
    for item in video_data.get("bit_rate") or []:
        if isinstance(item, dict) and isinstance(address := item.get("play_addr"), dict):
            addresses.append(address)
    return addresses


def current_top_short_side(video_data: dict) -> int:
    """当前最高档（分辨率面积最大）的短边像素；没有可用档位时返回 0。"""
    best_area = 0
    best_short_side = 0
    for address in _iter_addresses(video_data):
        width = _as_int(address.get("width"))
        height = _as_int(address.get("height"))
        if width <= 0 or height <= 0:
            continue
        area = width * height
        if area > best_area:
            best_area = area
            best_short_side = min(width, height)
    return best_short_side


def merge_gears(video_data: dict, gears: list[dict]) -> int:
    """把 discover 档位追加进 ``video_data["bit_rate"]``，返回新增条数。

    只追加「(width,height) 与现有档位不重复且 URL 也不重复」的档位 —— 移动端已有 720P
    档就不重复添加，补上它缺的 1080P/2160P。
    """
    entries = gears_to_bit_rate(gears)
    if not entries:
        return 0
    bucket = video_data.get("bit_rate")
    if not isinstance(bucket, list):
        bucket = []
        video_data["bit_rate"] = bucket

    known_dims: set[tuple[int, int]] = set()
    known_urls: set[str] = set()
    for address in _iter_addresses(video_data):
        width = _as_int(address.get("width"))
        height = _as_int(address.get("height"))
        if width > 0 and height > 0:
            known_dims.add((width, height))
        for url in address.get("url_list") or []:
            if isinstance(url, str):
                known_urls.add(url)

    added = 0
    for entry in entries:
        address = entry["play_addr"]
        dims = (address["width"], address["height"])
        if dims[0] <= 0 or dims[1] <= 0 or dims in known_dims:
            continue
        if any(url in known_urls for url in address["url_list"]):
            continue
        bucket.append(entry)
        known_dims.add(dims)
        known_urls.update(address["url_list"])
        added += 1
    return added


def _build_command(
    browser: str,
    url: str,
    profile_dir: str,
    *,
    proxy: str | None = None,
    virtual_time: bool = False,
) -> list[str]:
    command = [
        browser,
        "--headless=new",
        "--disable-blink-features=AutomationControlled",
        "--no-first-run",
        "--no-default-browser-check",
        "--disable-extensions",
        "--disable-gpu",
        "--mute-audio",
        f"--user-data-dir={profile_dir}",
        f"--user-agent={BROWSER_UA}",
        f"--window-size={WINDOW_SIZE}",
    ]
    if proxy:
        command.append(f"--proxy-server={proxy}")
    if virtual_time:
        command.append(f"--virtual-time-budget={VIRTUAL_TIME_BUDGET}")
    command.extend(["--dump-dom", url])
    return command


_dump_semaphores: WeakKeyDictionary[asyncio.AbstractEventLoop, asyncio.Semaphore] = WeakKeyDictionary()


def _dump_semaphore() -> asyncio.Semaphore:
    """按事件循环取并发闸门（asyncio.Semaphore 绑定首次使用的事件循环）。"""
    loop = asyncio.get_running_loop()
    semaphore = _dump_semaphores.get(loop)
    if semaphore is None:
        semaphore = asyncio.Semaphore(MAX_CONCURRENT_DUMPS)
        _dump_semaphores[loop] = semaphore
    return semaphore


def _cleanup_stale_profiles() -> None:
    """清掉取消/超时后残留的临时 profile；还活着的目录 mtime 新，不会被误删。"""
    now = time.time()
    for path in Path(tempfile.gettempdir()).glob(f"{PROFILE_PREFIX}*"):
        try:
            if now - path.stat().st_mtime < PROFILE_STALE_SECONDS:
                continue
            shutil.rmtree(path, ignore_errors=True)
        except OSError:
            continue


async def _dump_dom(
    browser: str,
    aweme_id: str,
    *,
    proxy: str | None = None,
    virtual_time: bool = False,
) -> str:
    """``--dump-dom`` 跑一次浏览器，返回页面 HTML；失败返回空串。"""
    url = DISCOVER_URL.format(aweme_id)
    timeout = VIRTUAL_TIME_TIMEOUT if virtual_time else DUMP_TIMEOUT
    async with _dump_semaphore():
        _cleanup_stale_profiles()
        profile_dir = tempfile.mkdtemp(prefix=PROFILE_PREFIX)
        command = _build_command(browser, url, profile_dir, proxy=proxy, virtual_time=virtual_time)

        def run() -> subprocess.CompletedProcess:
            return subprocess.run(  # noqa: S603
                command,
                capture_output=True,
                timeout=timeout,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0) if os.name == "nt" else 0,
            )

        try:
            completed = await asyncio.to_thread(run)
        except (OSError, subprocess.SubprocessError, ValueError):
            return ""
        finally:
            shutil.rmtree(profile_dir, ignore_errors=True)
    if completed.returncode != 0:
        return ""
    return (completed.stdout or b"").decode("utf-8", errors="replace")


async def fetch_discover_gears(browser: str, aweme_id: str, *, proxy: str | None = None) -> list[dict]:
    """抓 discover 页并返回档位；首次没抠到档位时再用 ``--virtual-time-budget`` 重试一次。

    重试只针对「页面拿到了但没有档位」（渲染太慢/风控）—— 第一次就是空结果（超时、崩溃）
    时不重试，避免把两段超时叠加进批次的 180 秒预算。
    """
    html = await _dump_dom(browser, aweme_id, proxy=proxy)
    gears = extract_discover_gears(html) if html else []
    if gears or not html:
        return gears
    retried = await _dump_dom(browser, aweme_id, proxy=proxy, virtual_time=True)
    return extract_discover_gears(retried) if retried else []


_gear_cache: dict[str, tuple[float, list[dict]]] = {}


def _cache_get(aweme_id: str) -> list[dict] | None:
    item = _gear_cache.get(aweme_id)
    if item is None:
        return None
    stamp, gears = item
    if time.monotonic() - stamp > CACHE_TTL:
        _gear_cache.pop(aweme_id, None)
        return None
    return gears


def _cache_put(aweme_id: str, gears: list[dict]) -> None:
    if len(_gear_cache) >= CACHE_LIMIT:
        oldest = min(_gear_cache, key=lambda key: _gear_cache[key][0])
        _gear_cache.pop(oldest, None)
    _gear_cache[aweme_id] = (time.monotonic(), gears)


async def enhance_with_browser(json_dict: dict, raw_url: str, *, proxy: str | None = None) -> int:
    """接口只给低清档时，用系统浏览器补高清档，返回新增档位数量。

    只在「是视频」且「当前最高档短边 < 1440」时才动手；没有浏览器、抓取失败、抠不出档位
    等任何情况都返回 0，绝不抛异常 —— 浏览器只是锦上添花，不能拖垮解析。
    抓取结果按 aweme_id 缓存（``CACHE_TTL`` 秒）：下载任务会重新解析一遍，命中缓存既保证
    画质芯片下标不错位，也避免同一视频重复起浏览器。
    """
    try:
        payload = json_dict.get("aweme_detail") if isinstance(json_dict, dict) else None
        if not payload:
            return 0
        if payload.get("images") or payload.get("image_post_info"):
            return 0
        video_data = payload.get("video")
        if not isinstance(video_data, dict):
            return 0
        if current_top_short_side(video_data) >= MIN_SHORT_SIDE:
            return 0
        aweme_id = await DouyinWebCrawler.get_aweme_id(raw_url)
        if not aweme_id.isdigit():
            return 0
        gears = _cache_get(aweme_id)
        if gears is None:
            browser = find_browser()
            if not browser:
                return 0
            gears = await fetch_discover_gears(browser, aweme_id, proxy=proxy)
            if not gears:
                return 0
            _cache_put(aweme_id, gears)
        return merge_gears(video_data, gears)
    except Exception:  # noqa: BLE001 —— 浏览器路径绝不允许拖垮解析
        return 0
