from __future__ import annotations

import os
import shutil
from pathlib import Path


def publish_archive(download_result, staging: Path, destination: Path):
    staging = staging.resolve()
    source_root = Path(download_result.output_dir).resolve()
    if not source_root.is_relative_to(staging) or source_root == staging:
        raise ValueError("下载结果不在本次临时目录内")
    if destination.resolve().parent != staging.parent:
        raise ValueError("归档目录必须位于下载目录内，不能指向外部目录")
    destination.mkdir(parents=True, exist_ok=True)
    destination = destination.resolve()
    mapping = {}
    sources = sorted(source_root.rglob("*"))
    for source in sources:
        if source.is_symlink() or not source.resolve().is_relative_to(staging):
            raise ValueError("拒绝归档临时目录之外的文件")
        if not source.is_file():
            continue
        relative = source.relative_to(source_root)
        parent = destination / relative.parent
        parent.mkdir(parents=True, exist_ok=True)
        if not parent.resolve().is_relative_to(destination):
            raise ValueError("归档目录不能链接到外部目录")
        number = 1
        while True:
            name = source.name if number == 1 else f"{source.stem}_{number}{source.suffix}"
            target = parent / name
            try:
                os.link(source, target)
            except FileExistsError:
                number += 1
                continue
            except OSError:
                try:
                    output = target.open("xb")
                except FileExistsError:
                    number += 1
                    continue
                try:
                    with output, source.open("rb") as incoming:
                        shutil.copyfileobj(incoming, output)
                except BaseException:
                    target.unlink(missing_ok=True)
                    raise
            mapping[source.resolve()] = target
            break
    media = download_result.media
    items = media if isinstance(media, (list, tuple)) else [media]
    for item in items:
        for field in ("path", "video_path"):
            value = getattr(item, field, None)
            if value:
                resolved = Path(value).resolve()
                if resolved not in mapping:
                    raise ValueError("下载文件未能归档，原文件保留在临时目录")
                setattr(item, field, mapping[resolved])
    download_result.output_dir = destination
    for source in sources:
        if source.is_file():
            source.unlink()
    for directory in sorted((entry for entry in staging.rglob("*") if entry.is_dir()),
                            key=lambda entry: len(entry.parts), reverse=True):
        directory.rmdir()
    staging.rmdir()
    return download_result
