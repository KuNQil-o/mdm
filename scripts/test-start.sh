#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$mdm_root"
source scripts/env.sh
mkdir -p .runtime
docker compose up -d --wait --wait-timeout 60 db
if [ "$(docker compose exec -T db psql -U mdm -d postgres -Atc "select count(*) from pg_database where datname='mdm_test'")" = 0 ]; then docker compose exec -T db createdb -U mdm mdm_test; fi
mdm_hash=$(sha256sum backend/target/mdm-0.1.0.jar | cut -d ' ' -f1)
mdm_jar="$mdm_root/.runtime/test-app-$mdm_hash.jar"
[ -f "$mdm_jar" ] || cp backend/target/mdm-0.1.0.jar "$mdm_jar"
if [ -f .runtime/test-backend.pid ]; then
 mdm_pid=$(cat .runtime/test-backend.pid)
 if [ -r "/proc/$mdm_pid/cmdline" ]; then
  if tr '\0' ' ' < "/proc/$mdm_pid/cmdline" | rg -q "$mdm_jar"; then mdm_existing=true
  elif tr '\0' ' ' < "/proc/$mdm_pid/cmdline" | rg -q "$mdm_root/.runtime/test-app-"; then kill "$mdm_pid"; fi
 fi
fi
if [ "${mdm_existing:-false}" != true ]; then
 PORT=8081 DATABASE_URL=jdbc:postgresql://localhost:5432/mdm_test RETRY_DELAYS=1,1,1,1 WORKER_DELAY=100 nohup java -jar "$mdm_jar" >> .runtime/test-backend.log 2>&1 &
 echo $! > .runtime/test-backend.pid
fi
if ! curl -fsS --max-time 1 http://localhost:9091/health >/dev/null; then
 nohup python "$mdm_root/erp-mock/server.py" --port 9091 --database "$mdm_root/.runtime/erp-test.sqlite" >> .runtime/erp-test.log 2>&1 &
 echo $! > .runtime/test-erp.pid
fi
for mdm_url in http://localhost:8081/actuator/health http://localhost:9091/health; do
 mdm_ready=false
 for mdm_try in $(seq 1 30); do if curl -fsS --max-time 1 "$mdm_url" >/dev/null; then mdm_ready=true;break;fi;sleep 1;done
 [ "$mdm_ready" = true ] || exit 1
done
curl -fsS -X POST http://localhost:8081/api/dev/seed
