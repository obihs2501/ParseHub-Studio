from __future__ import annotations

import argparse
import socket
import threading
import time
import webbrowser

import uvicorn

from . import __version__


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="ParseHub Studio 浏览器开发模式；桌面版请启动 Tauri 应用。")
    parser.add_argument("--host", default="127.0.0.1", choices=["127.0.0.1", "localhost"])
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--no-browser", action="store_true")
    parser.add_argument("-v", "--version", action="version", version=f"parsehub-studio {__version__}")
    args = parser.parse_args(argv)
    from .app import app

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    try:
        listener.bind((args.host, args.port))
    except OSError as exc:
        parser.exit(1, f"无法监听端口 {args.port}: {exc}\n请使用 --port 0 自动选择空闲端口。\n")
    port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, log_level="warning", access_log=False))

    def announce() -> None:
        while not server.started and not server.should_exit:
            time.sleep(0.05)
        if server.started:
            url = f"http://localhost:{port}/"
            print(f"ParseHub Studio: {url}", flush=True)
            if not args.no_browser:
                webbrowser.open(url)

    threading.Thread(target=announce, daemon=True).start()
    try:
        server.run(sockets=[listener])
    finally:
        listener.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
