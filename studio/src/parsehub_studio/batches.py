from __future__ import annotations

import asyncio
import re
import time
import uuid

from parsehub.utils.helpers import _url_extractor


def extract_links(text: str) -> list[str]:
    links = _url_extractor.find_urls(re.sub(r"(https?://)", r" \1", text), only_unique=True)
    links = list(dict.fromkeys(url if url.startswith(("https://", "http://")) else "https://" + url for url in links))
    if not links:
        raise ValueError("未找到链接，请粘贴分享文案或每行一个链接")
    if len(links) > 50:
        raise ValueError("一次最多解析 50 条链接")
    return links


class BatchManager:
    def __init__(self, parse, download):
        self.parse = parse
        self.download = download
        self.batches = {}
        self.running = {}
        self.slots = asyncio.Semaphore(3)

    def create(self, links: list[str], options: dict):
        if sum(not task.done() for task in self.running.values()) >= 3:
            raise ValueError("已有 3 批正在解析，请稍候或取消后再试")
        for batch_id in list(self.batches):
            if len(self.batches) < 10:
                break
            if self.running[batch_id].done():
                self.batches.pop(batch_id)
                self.running.pop(batch_id)
        batch = {
            "id": uuid.uuid4().hex,
            "created_at": time.time(),
            "status": "running",
            "items": [
                {"id": uuid.uuid4().hex, "url": url, "status": "pending", "result": None, "error": None}
                for url in links
            ],
        }
        self.batches[batch["id"]] = batch
        self.running[batch["id"]] = asyncio.create_task(self._run(batch, options))
        return batch

    async def _run(self, batch, options):
        async def work(item):
            try:
                async with self.slots:
                    item["status"] = "running"
                    item["result"] = await asyncio.wait_for(self.parse(item["url"], options), timeout=180)
                    item["status"] = "success"
                    if options.get("download"):
                        item["download_id"] = self.download(item["url"], options).id
            except asyncio.CancelledError:
                item["status"] = "cancelled"
                raise
            except Exception as error:
                item["status"] = "error"
                item["error"] = str(error) or type(error).__name__

        try:
            await asyncio.gather(*(work(item) for item in batch["items"]))
            batch["status"] = "complete"
        except asyncio.CancelledError:
            batch["status"] = "cancelled"
            raise

    def cancel(self, batch_id):
        task = self.running.get(batch_id)
        if task and not task.done():
            task.cancel()
            batch = self.batches[batch_id]
            batch["status"] = "cancelled"
            for item in batch["items"]:
                if item["status"] in {"pending", "running"}:
                    item["status"] = "cancelled"

    async def shutdown(self):
        for batch_id in self.running:
            self.cancel(batch_id)
        await asyncio.gather(*self.running.values(), return_exceptions=True)
