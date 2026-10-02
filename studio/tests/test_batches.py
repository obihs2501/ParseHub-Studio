import asyncio
import unittest
from unittest.mock import AsyncMock, Mock

from parsehub_studio.batches import BatchManager, extract_links


class BatchTests(unittest.IsolatedAsyncioTestCase):
    def test_extract_mixed_share_text_and_deduplicate(self):
        text = "复制打开抖音 https://v.douyin.com/sS04AWo12Jg/ 09/29 usR:/\nhttps://weibo.com/123/456\nhttps://v.douyin.com/sS04AWo12Jg/"
        self.assertEqual(extract_links(text), ["https://v.douyin.com/sS04AWo12Jg/", "https://weibo.com/123/456"])
        for text in ("没有链接", "\n".join(f"https://weibo.com/123/{number}" for number in range(51))):
            with self.assertRaises(ValueError):
                extract_links(text)

    async def test_mixed_results_and_global_concurrency_limit(self):
        active = 0
        peak = 0

        async def parse(url, options):
            nonlocal active, peak
            active += 1
            peak = max(active, peak)
            try:
                await asyncio.sleep(0.01)
                if url == "bad":
                    raise ValueError("test error")
                return {"raw_url": url}
            finally:
                active -= 1

        download = Mock(return_value=Mock(id="download-id"))
        manager = BatchManager(parse, download)
        first = manager.create(["weibo", "douyin", "bad", "xhs"], {"download": True})
        second = manager.create(["other", "last"], {})
        await asyncio.gather(*manager.running.values())
        self.assertEqual(peak, 3)
        self.assertEqual([item["status"] for item in first["items"]], ["success", "success", "error", "success"])
        self.assertEqual(first["items"][2]["error"], "test error")
        self.assertEqual(download.call_count, 3)
        self.assertEqual(second["status"], "complete")
        await manager.shutdown()

    async def test_cancel_before_start_and_during_parse(self):
        manager = BatchManager(AsyncMock(), Mock())
        batch = manager.create(["a", "b"], {})
        manager.cancel(batch["id"])
        await manager.shutdown()
        self.assertEqual(batch["status"], "cancelled")
        self.assertTrue(all(item["status"] == "cancelled" for item in batch["items"]))
        manager.parse.assert_not_awaited()
        started = asyncio.Event()

        async def parse(url, options):
            if url == "done":
                return {}
            started.set()
            await asyncio.Event().wait()

        manager = BatchManager(parse, Mock())
        batch = manager.create(["done", "slow", "pending", "pending2"], {})
        await started.wait()
        await asyncio.sleep(0.01)
        await manager.shutdown()
        self.assertEqual(batch["items"][0]["status"], "success")
        self.assertTrue(all(item["status"] == "cancelled" for item in batch["items"][1:]))

    async def test_retention_and_active_limit(self):
        manager = BatchManager(AsyncMock(return_value={}), Mock())
        for number in range(12):
            batch = manager.create([str(number)], {})
            await manager.running[batch["id"]]
        self.assertEqual(len(manager.batches), 10)
        for number in range(3):
            manager.create([str(number)], {})
        with self.assertRaises(ValueError):
            manager.create(["fourth"], {})
        await manager.shutdown()


if __name__ == "__main__":
    unittest.main()
