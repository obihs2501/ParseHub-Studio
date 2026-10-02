import ast
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]
analysis = ast.literal_eval((root / "build/parsehub_studio/Analysis-00.toc").read_text(encoding="utf-8"))
forbidden = {"cv2", "numpy", "pydub", "ffmpeg.exe", "ffprobe.exe"}


def inspect(value):
    if isinstance(value, (list, tuple)):
        if len(value) == 3 and isinstance(value[2], str) and value[2] in {"BINARY", "EXTENSION", "DATA", "PYMODULE", "PYMODULE-1", "PYMODULE-2"}:
            for path in value[:2]:
                parts = str(path).replace("\\", "/").lower().split("/")
                if any(part in forbidden or part.startswith(("cv2.", "numpy.", "pydub.")) for part in parts):
                    raise AssertionError(f"Unwanted dependency in bundle: {path}")
        else:
            for item in value:
                inspect(item)


inspect(analysis)
binary = root / "dist/parsehub-sidecar.exe"
print(json.dumps({"sidecar_mb": round(binary.stat().st_size / 1024**2, 2), "excluded": sorted(forbidden)}))
