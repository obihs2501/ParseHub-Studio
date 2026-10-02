from __future__ import annotations

import json
import os
import socket
import sys
import threading
import time

import uvicorn


def main() -> int:
    if not os.environ.get("PARSEHUB_SESSION_TOKEN"):
        print("The sidecar must be started by ParseHub Studio.", file=sys.stderr)
        return 2
    from .app import app

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    port = listener.getsockname()[1]
    server = uvicorn.Server(uvicorn.Config(app, log_level="warning", access_log=False, timeout_graceful_shutdown=5))

    def announce() -> None:
        while not server.started and not server.should_exit:
            time.sleep(0.05)
        if server.started:
            print(json.dumps({"event": "ready", "port": port}), flush=True)

    def watch_parent() -> None:
        try:
            while sys.stdin.buffer.read(1):
                pass
        finally:
            server.should_exit = True

    threading.Thread(target=watch_parent, daemon=True).start()
    threading.Thread(target=announce, daemon=True).start()
    try:
        server.run(sockets=[listener])
    finally:
        listener.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
