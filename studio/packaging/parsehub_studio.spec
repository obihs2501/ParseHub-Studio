from pathlib import Path

from PyInstaller.utils.hooks import collect_data_files, collect_submodules, copy_metadata

packaging_dir = Path(SPECPATH).resolve()
studio_dir = packaging_dir.parent
repo_dir = studio_dir.parent
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
    binaries=[],
    datas=datas,
    hiddenimports=hiddenimports,
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=["tkinter", "matplotlib", "pytest", "webview", "pythonnet"],
    noarchive=False,
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
