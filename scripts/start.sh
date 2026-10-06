#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
source "$mdm_root/scripts/env.sh"
cd "$mdm_root"
mkdir -p .runtime
if [ -z "${DATABASE_URL:-}" ]; then docker compose up -d --wait --wait-timeout 60 db; fi
start_mdm_process(){
 local mdm_name=$1; shift
 local mdm_pidfile="$mdm_root/.runtime/$mdm_name.pid"
 if [ -f "$mdm_pidfile" ]; then
  local mdm_pid; mdm_pid=$(cat "$mdm_pidfile")
  if kill -0 "$mdm_pid" 2>/dev/null && [ -r "/proc/$mdm_pid/cmdline" ] && tr '\0' ' ' < "/proc/$mdm_pid/cmdline" | rg -q "$mdm_root"; then return; fi
 fi
 nohup "$@" >> "$mdm_root/.runtime/$mdm_name.log" 2>&1 &
 echo $! > "$mdm_pidfile"
}
if [ -z "${DATABASE_URL:-}" ]; then scripts/erp-fixture.sh >.runtime/erp-fixture.log 2>&1; fi
start_mdm_process erp-v3 python3 "$mdm_root/erp-v3/server.py" --port 9092
mdm_artifact=$(sha256sum "$mdm_root/backend/target/mdm-0.1.0.jar" | cut -d ' ' -f1)
mdm_jar="$mdm_root/.runtime/app-$mdm_artifact.jar"
if [ ! -f "$mdm_jar" ]; then cp "$mdm_root/backend/target/mdm-0.1.0.jar" "$mdm_jar"; fi
start_mdm_process backend java -jar "$mdm_jar"
start_mdm_process frontend node "$mdm_root/frontend/node_modules/vite/bin/vite.js" "$mdm_root/frontend" --host 0.0.0.0 --config "$mdm_root/frontend/vite.config.ts"
for mdm_url in "http://localhost:${PORT:-8080}/actuator/health" http://localhost:9092/health http://localhost:5173; do
 mdm_ready=false
 for mdm_try in $(seq 1 30); do
  if curl --silent --fail --max-time 2 "$mdm_url" > /dev/null; then mdm_ready=true; break; fi
  sleep 1
 done
 if [ "$mdm_ready" != true ]; then echo "启动失败，请检查 .runtime 下对应日志：$mdm_url" >&2; exit 1; fi
 echo "服务响应正常：$mdm_url"
done
