from __future__ import annotations

import argparse
import json
import os
import queue
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

import httpx


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--executable")
    args = parser.parse_args()
    command = [str(Path(args.executable).resolve())] if args.executable else [sys.executable, "-m", "parsehub_studio.sidecar"]
    token = secrets.token_urlsafe(32)
    environment = {**os.environ, "PARSEHUB_SESSION_TOKEN": token, "PYTHONUTF8": "1"}
    with tempfile.TemporaryFile(mode="w+b") as errors:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, env=environment)
        messages = queue.Queue()

        def read_ready():
            for line in process.stdout:
                try:
                    message = json.loads(line)
                    if message.get("event") == "ready":
                        messages.put(message)
                except (ValueError, UnicodeDecodeError):
                    pass

        threading.Thread(target=read_ready, daemon=True).start()
        try:
            ready = messages.get(timeout=150)
            port = ready["port"]
            with httpx.Client(base_url=f"http://127.0.0.1:{port}", trust_env=False, follow_redirects=True) as client:
                assert client.get("/api/health").status_code == 401
                assert client.get(f"/desktop?token={token}").status_code == 200
                assert client.get("/api/health").json()["desktop"]
                assert len(client.get("/api/platforms").json()["platforms"]) >= 15
                for asset in ("/app.js", "/styles.css", "/favicon.svg"):
                    assert client.get(asset).status_code == 200
                assert client.post("/api/detect", json={"text": "https://www.xiaohongshu.com/explore/abc123"}).json()["platform"]["id"] == "xhs"
                assert client.get("/api/config", headers={"Origin": "https://evil.example"}).status_code == 403
            process.stdin.close()
            assert process.wait(timeout=15) == 0
            with socket.socket() as probe:
                assert probe.connect_ex(("127.0.0.1", port)) != 0
            print("sidecar startup, authenticated API, parser discovery, assets and parent EOF shutdown passed")
        except Exception:
            errors.seek(0)
            print(errors.read().decode("utf-8", errors="replace"), file=sys.stderr)
            raise
        finally:
            if process.poll() is None:
                process.stdin.close()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()


if __name__ == "__main__":
    main()
