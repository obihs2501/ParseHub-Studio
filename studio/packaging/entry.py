"""PyInstaller 打包入口。"""

from __future__ import annotations

import multiprocessing
import sys

from parsehub_studio.sidecar import main

if __name__ == "__main__":
    multiprocessing.freeze_support()
    sys.exit(main())
