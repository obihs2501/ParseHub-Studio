import re
from urllib.parse import urlparse

from .helpers import UA


def is_weibo_image(url: str) -> bool:
    parsed = urlparse(url)
    return bool(parsed.scheme in {"https", "http"} and re.fullmatch(r"wx[1-4]\.sinaimg\.cn", parsed.netloc))


def media_candidates(url: str) -> list[str]:
    if not is_weibo_image(url):
        return [url]
    parsed = urlparse(url)
    hosts = [f"wx{number}.sinaimg.cn" for number in range(1, 5)]
    return [url] + [parsed._replace(netloc=host).geturl() for host in hosts if host != parsed.netloc]


def media_headers(url: str) -> dict[str, str]:
    headers = {"User-Agent": UA, "Accept-Encoding": "identity"}
    if is_weibo_image(url):
        headers["Referer"] = "https://weibo.com/"
    return headers


def preview_url(url: str) -> str:
    if not is_weibo_image(url):
        return url
    parsed = urlparse(url)
    parts = parsed.path.split("/")
    if len(parts) == 3 and parts[1] == "large":
        parts[1] = "mw1024"
    return parsed._replace(path="/".join(parts)).geturl()
