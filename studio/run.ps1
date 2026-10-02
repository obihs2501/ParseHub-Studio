#!/usr/bin/env pwsh
$ErrorActionPreference = "Stop"
Set-Location -LiteralPath $PSScriptRoot

if (-not (Get-Command uv -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 uv，请先安装: https://docs.astral.sh/uv/"
}

uv sync --quiet
uv run parsehub-studio @args
