#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -m venv backend/.venv
backend/.venv/bin/pip install -r backend/requirements-dev.txt
npm --prefix frontend ci

echo "浏览器测试需要 Chromium，可使用 CHROMIUM_PATH 指定路径。"
