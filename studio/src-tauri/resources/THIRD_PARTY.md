# Third-party components

ParseHub Studio includes the MIT-licensed ParseHub project by z-mio:
https://github.com/z-mio/ParseHub (see the repository root LICENSE).

The desktop shell uses Tauri (MIT / Apache-2.0) and bundles Python and its
dependencies via PyInstaller. Their original licenses continue to apply.

The lightweight package does not bundle FFmpeg, FFprobe, OpenCV, NumPy or
pydub. FFmpeg/FFprobe, when installed by the user, are discovered through
PATH or the directory selected in Studio settings. Image downloads and
previews do not require them; some video merge/conversion operations do.
