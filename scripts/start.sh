#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$mdm_root"
mkdir -p .runtime
if [ -z "${DATABASE_URL:-}" ]; then docker compose up -d --wait --wait-timeout 60 db; fi
start_process(){
 local mdm_name=$1; shift
 local mdm_pidfile="$mdm_root/.runtime/v4-$mdm_name.pid"
 if [ -f "$mdm_pidfile" ]; then
  local mdm_pid; mdm_pid=$(cat "$mdm_pidfile")
  if kill -0 "$mdm_pid" 2>/dev/null && [ -r "/proc/$mdm_pid/cmdline" ] && tr '\0' ' ' < "/proc/$mdm_pid/cmdline" | rg -q 'uvicorn|vite'; then return; fi
 fi
 nohup setsid "$@" >> "$mdm_root/.runtime/v4-$mdm_name.log" 2>&1 </dev/null &
 echo $! > "$mdm_pidfile"
}
start_process backend "$mdm_root/backend/.venv/bin/uvicorn" app.api:app --app-dir "$mdm_root/backend" --host 0.0.0.0 --port "${PORT:-8080}" --no-access-log
start_process frontend node "$mdm_root/frontend/node_modules/vite/bin/vite.js" "$mdm_root/frontend" --host 0.0.0.0 --config "$mdm_root/frontend/vite.config.ts"
for mdm_url in "http://localhost:${PORT:-8080}/api/v1/health" http://localhost:5173; do
 mdm_ready=false
 for mdm_try in $(seq 1 30); do
  if curl --silent --fail --max-time 2 "$mdm_url" >/dev/null; then mdm_ready=true; break; fi
  sleep 1
 done
 if [ "$mdm_ready" != true ]; then echo "启动失败，请检查 .runtime/v4 日志：$mdm_url" >&2; exit 1; fi
 echo "服务已启动：$mdm_url"
done
