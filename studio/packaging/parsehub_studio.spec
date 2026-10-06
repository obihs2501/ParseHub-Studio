import sys
from pathlib import Path

from PyInstaller.building.datastruct import TOC
from PyInstaller.utils.hooks import collect_data_files, collect_submodules, copy_metadata

packaging_dir = Path(SPECPATH).resolve()
studio_dir = packaging_dir.parent
repo_dir = studio_dir.parent

# ---- 运行时 DLL 显式固定 -----------------------------------------------------
# 让 bindepend 的 PATH 搜索决定用哪个 DLL 是不可接受的：实测在 conda 机器上抓到了
# C:\Program Files\Git\mingw64\bin 的 OpenSSL 与 MuMu 模拟器的 liblzma/libexpat，
# 而 _ctypes 需要的 ffi.dll 因为找不到被静默丢弃，打出来的 exe 一启动就
# "ImportError: DLL load failed while importing _ctypes"，且只有换台机器才暴露。
# 这里按「<prefix>\Library\bin（conda）→ 解释器根 → DLLs」的顺序，把两种布局下
# 真正存在的运行时 DLL 显式固定（每组候选名只会命中一个）；静态链接的布局
# （如 python.org 的 _lzma/_bz2/pyexpat）找不到独立 DLL 属正常，交给 check_bundle.py 兜底。
RUNTIME_DLL_GROUPS = [
    ("ffi.dll", "libffi-8.dll", "libffi.dll"),      # _ctypes（click → uvicorn 全链都依赖它）
    ("libssl-3-x64.dll", "libssl-3.dll"),           # _ssl
    ("libcrypto-3-x64.dll", "libcrypto-3.dll"),     # _ssl / _hashlib
    ("sqlite3.dll",),                               # _sqlite3（yt_dlp 读浏览器 cookie 库）
    ("liblzma.dll",),                               # _lzma
    ("libbz2.dll",),                                # _bz2
    ("libexpat.dll",),                              # pyexpat
]
binaries = []
prefixes = {Path(sys.prefix), Path(sys.base_prefix)}
search_dirs = [
    directory
    for prefix in prefixes
    for directory in (prefix / "Library" / "bin", prefix, prefix / "DLLs")
]
for group in RUNTIME_DLL_GROUPS:
    found = next((directory / name for name in group for directory in search_dirs if (directory / name).is_file()), None)
    if found is None:
        print(f"警告：解释器目录里找不到 {group[0]} 等运行时 DLL —— 该布局可能静态链接，交由 check_bundle.py 校验")
    else:
        binaries.append((str(found), "."))

hiddenimports = collect_submodules("parsehub") + collect_submodules("uvicorn")
datas = [(str(studio_dir / "src/parsehub_studio/web"), "parsehub_studio/web")]
for package in ("yt_dlp", "gmssl", "signerpy"):
    hiddenimports += collect_submodules(package)
for package in ("urlextract", "yt_dlp", "certifi", "bleach"):
    datas += collect_data_files(package)
for package in ("parsehub", "parsehub-studio", "yt-dlp"):
    datas += copy_metadata(package)

analysis = Analysis(
    [str(packaging_dir / "entry.py")],
    pathex=[str(studio_dir / "src"), str(repo_dir / "src")],
    binaries=binaries,
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib", "pytest", "webview", "pythonnet", "cv2", "numpy", "pydub"],
    noarchive=False,
)

# ucrtbase.dll 是系统级 CRT：conda 根目录下的副本会被当成依赖收进包，
# 与目标机的系统版本混用可能出问题（Win10+ 系统自带），不要分发。
analysis.binaries = TOC(
    [entry for entry in analysis.binaries if Path(entry[0]).name.lower() != "ucrtbase.dll"]
)

archive = PYZ(analysis.pure)
exe = EXE(
    archive,
    analysis.scripts,
    analysis.binaries,
    analysis.datas,
    [],
    name="parsehub-sidecar",
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=True,
)
