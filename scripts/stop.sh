#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for mdm_name in backend frontend; do
 mdm_pidfile=".runtime/v4-$mdm_name.pid"
 if [ -f "$mdm_pidfile" ]; then
  mdm_pid=$(cat "$mdm_pidfile")
  if kill -0 "$mdm_pid" 2>/dev/null && [ -r "/proc/$mdm_pid/cmdline" ] && tr '\0' ' ' < "/proc/$mdm_pid/cmdline" | rg -q 'uvicorn|vite'; then kill "$mdm_pid"; fi
  rm "$mdm_pidfile"
 fi
done
