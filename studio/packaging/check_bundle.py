import ast
import json
from pathlib import Path

FORBIDDEN_PACKAGES = {"cv2", "numpy", "pydub"}
FORBIDDEN_EXECUTABLES = {"ffmpeg.exe", "ffprobe.exe", "ffmpeg", "ffprobe"}
ENTRY_TYPES = {"BINARY", "EXTENSION", "DATA", "PYMODULE", "PYMODULE-1", "PYMODULE-2"}


def path_parts(value):
    return str(value).replace("\\", "/").lower().split("/")


def forbidden_entry(name, source, kind):
    destination = path_parts(name)
    if kind.startswith("PYMODULE") or kind == "EXTENSION":
        module_root = destination[0].split(".")[0]
        if module_root in FORBIDDEN_PACKAGES:
            return True
    package_dirs = FORBIDDEN_PACKAGES | {f"{package}.libs" for package in FORBIDDEN_PACKAGES}
    if any(part in package_dirs for part in destination[:-1]):
        return True
    if destination[-1] in FORBIDDEN_EXECUTABLES:
        return True
    source_parts = path_parts(source)
    for index, part in enumerate(source_parts[:-1]):
        if part in {"site-packages", "dist-packages"}:
            package_root = source_parts[index + 1].split(".")[0]
            if package_root in FORBIDDEN_PACKAGES:
                return True
    return False


def inspect(value):
    if isinstance(value, (list, tuple)):
        if len(value) == 3 and isinstance(value[2], str) and value[2] in ENTRY_TYPES:
            name, source, kind = value
            if forbidden_entry(name, source, kind):
                raise AssertionError(f"Unwanted dependency in bundle: {name} ({source})")
        else:
            for item in value:
                inspect(item)


def main():
    root = Path(__file__).resolve().parents[1]
    analysis = ast.literal_eval((root / "build/parsehub_studio/Analysis-00.toc").read_text(encoding="utf-8"))
    inspect(analysis)
    binary = root / "dist/parsehub-sidecar.exe"
    print(json.dumps({
        "sidecar_mb": round(binary.stat().st_size / 1024**2, 2),
        "excluded": sorted(FORBIDDEN_PACKAGES | FORBIDDEN_EXECUTABLES),
    }))


if __name__ == "__main__":
    main()
