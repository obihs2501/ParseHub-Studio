@echo off
setlocal
cd /d "%~dp0"
where uv >nul 2>nul || (echo 未找到 uv，请先安装: https://docs.astral.sh/uv/ & exit /b 1)
uv sync --quiet
uv run parsehub-studio %*
endlocal
