"""校验 PyInstaller 产物：禁止的包 / 必须齐全的运行时 DLL / 不允许的外来工具链来源。

DLL 检查是 2026-10-05 那次事故（sidecar 启动即 ImportError: DLL load failed while
importing _ctypes）之后加的：PyInstaller 找不到某个扩展模块的依赖 DLL 时会**静默丢弃**，
只在 warn-*.txt 里留一行，产物要换台机器才暴露。这里把它变成构建期硬失败。
"""

import ast
import json
import sys
from pathlib import Path

FORBIDDEN_PACKAGES = {"cv2", "numpy", "pydub"}
FORBIDDEN_EXECUTABLES = {"ffmpeg.exe", "ffprobe.exe", "ffmpeg", "ffprobe"}
ENTRY_TYPES = {"BINARY", "EXTENSION", "DATA", "PYMODULE", "PYMODULE-1", "PYMODULE-2"}

# 扩展模块 → 依赖 DLL 的候选名（conda 与 python.org 命名不同）。
# 判定规则见 check_runtime_dlls：只有「环境里有这个 DLL、包里却没有」才报错，
# 所以静态链接的布局不会被误报。
REQUIRED_DLL_GROUPS = {
    # conda 与 python.org 都是独立 DLL：缺了必然启动崩溃 → 硬失败
    "_ctypes.pyd": ("ffi.dll", "libffi-8.dll", "libffi.dll"),
    "_ssl.pyd": ("libssl-3-x64.dll", "libssl-3.dll", "libssl.dll"),
    "_hashlib.pyd": ("libcrypto-3-x64.dll", "libcrypto-3.dll", "libcrypto.dll"),
    "_sqlite3.pyd": ("sqlite3.dll",),
}

# python.org 把这三个静态链接进扩展模块（环境里没有独立 DLL），conda 才有独立 DLL：
# 只在环境里确实找到该 DLL 时给提示，不阻塞构建（避免在 python.org 布局上误判）
ADVISORY_DLL_GROUPS = {
    "_lzma.pyd": ("liblzma.dll",),
    "_bz2.pyd": ("libbz2.dll",),
    "pyexpat.pyd": ("libexpat.dll",),
}

# 打包时按 PATH 抓到的外来工具链 DLL（mingw / MSYS / 模拟器）：与包内 MSVC 编译的
# python312.dll、_ssl.pyd 混用 CRT/OpenSSL，什么时候炸取决于目标机，必须拦住。
FOREIGN_SOURCE_MARKERS = ("git\\mingw", "msys", "mumu", "netease")

# 系统级 CRT 不应随包分发（目标机自带；混用旧版本会出问题）。
EXCLUDED_DLLS = {"ucrtbase.dll"}


def path_parts(value):
    return str(value).replace("\\", "/").lower().split("/")


def flatten(value):
    """把 TOC 里的 (name, source, kind) 三元组递归展开。"""
    if isinstance(value, (list, tuple)):
        if len(value) == 3 and isinstance(value[2], str) and value[2] in ENTRY_TYPES:
            yield value
        else:
            for item in value:
                yield from flatten(item)


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
    """发现禁止项直接抛 AssertionError（tests/test_bundle_check.py 依赖这个旧接口）。"""
    for name, source, kind in flatten(value):
        if forbidden_entry(name, source, kind):
            raise AssertionError(f"Unwanted dependency in bundle: {name} ({source})")


def load_toc(build_dir, name):
    path = build_dir / name
    if not path.is_file():
        return None
    return list(flatten(ast.literal_eval(path.read_text(encoding="utf-8"))))


def check_forbidden(entries):
    return [
        f"Unwanted dependency in bundle: {name} ({source})"
        for name, source, kind in entries
        if forbidden_entry(name, source, kind)
    ]


def extension_sources(entries):
    return {
        Path(name).name.lower(): Path(source)
        for name, source, kind in entries
        if kind == "EXTENSION"
    }


def search_dirs(source: Path):
    """在哪些目录里找「这个扩展模块该用的那个 DLL」。"""
    parent = source.parent
    prefix = parent.parent if parent.name.lower() == "dlls" else parent
    return [parent, prefix, prefix / "DLLs", prefix / "Library" / "bin"]


def check_runtime_dlls(entries, exists=Path.is_file, groups=REQUIRED_DLL_GROUPS):
    """扩展模块的依赖 DLL 必须跟着进包。

    三种布局都成立的判定：
    - 候选 DLL 已在包里 → 通过；
    - 不在包里，但构建环境里找得到（扩展模块所在目录 / 解释器根 / DLLs / Library\\bin）
      → 这个布局用的是独立 DLL，PyInstaller 把它丢了 → 报错；
    - 环境里也没有 → 该布局静态链接（python.org 的 _lzma/_bz2/pyexpat 就是），跳过。
    """
    present = {Path(name).name.lower() for name, _, kind in entries if kind in {"BINARY", "EXTENSION"}}
    problems = []
    for module, candidates in groups.items():
        source = extension_sources(entries).get(module.lower())
        if source is None:
            continue
        directories = search_dirs(source)
        for candidate in candidates:
            if candidate.lower() in present:
                break
            if any(exists(directory / candidate) for directory in directories):
                problems.append(
                    f"{module} 的依赖 DLL {candidate} 在构建环境里存在（{directories[0]} 等目录）"
                    "但没有进包 —— PyInstaller 找不到依赖时会静默丢弃，产物启动即 DLL load failed"
                )
                break
    return problems


def check_sources(entries):
    problems = []
    for name, source, kind in entries:
        if kind not in {"BINARY", "EXTENSION"}:
            continue
        normalized = str(source).replace("/", "\\").lower()
        if any(marker in normalized for marker in FOREIGN_SOURCE_MARKERS):
            problems.append(f"外来工具链 DLL（与包内 CRT/OpenSSL 混用）：{name} ← {source}")
        # 系统 CRT 只有在从解释器/环境目录（conda 根、venv…）被收进包时才拦；
        # 来自 Windows 系统目录的副本 PyInstaller 本来就不会打进产物
        if Path(name).name.lower() in EXCLUDED_DLLS and "\\windows\\" not in normalized:
            problems.append(f"不应随包分发的系统 DLL：{name} ← {source}")
    return problems


def main():
    root = Path(__file__).resolve().parents[1]
    build_dir = root / "build/parsehub_studio"
    # Analysis-00.toc 才有纯 Python 模块（PYMODULE）——禁止包检查必须用它；
    # EXE-00.toc 是最终产物清单（已应用 spec 里的 binaries 过滤）——DLL/来源检查用它。
    analysis = load_toc(build_dir, "Analysis-00.toc")
    final = load_toc(build_dir, "EXE-00.toc") or analysis
    if not analysis and not final:
        raise SystemExit("找不到 build/parsehub_studio/{Analysis,EXE}-00.toc —— 先跑 pyinstaller 再检查")
    binary = root / "dist/parsehub-sidecar.exe"
    problems = check_forbidden(analysis or final) + check_runtime_dlls(final) + check_sources(final)
    advisories = check_runtime_dlls(final, groups=ADVISORY_DLL_GROUPS)
    if not binary.is_file():
        problems.append(f"没有产出 {binary} —— PyInstaller 的 EXE 步骤没跑完")
    print(json.dumps({
        "sidecar_mb": round(binary.stat().st_size / 1024**2, 2) if binary.is_file() else None,
        "excluded": sorted(FORBIDDEN_PACKAGES | FORBIDDEN_EXECUTABLES),
        "required_dlls": sorted({name for names in REQUIRED_DLL_GROUPS.values() for name in names}),
        "problems": problems,
        "advisories": advisories,
    }, ensure_ascii=False))
    for advisory in advisories:
        print(f"::warning::{advisory}", file=sys.stderr)
    if problems:
        for problem in problems:
            print(f"::error::{problem}", file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
