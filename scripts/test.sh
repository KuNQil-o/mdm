#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$mdm_root"
mdm_suite=${1:-all}
mkdir -p .runtime
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = backend ]; then
 docker compose up -d --wait --wait-timeout 60 db
 if [ "$(docker compose exec -T db psql -U mdm -d postgres -Atc "select count(*) from pg_database where datname='mdm_test'")" = 0 ]; then docker compose exec -T db createdb -U mdm mdm_test;fi
 scripts/mvn.sh -q package > .runtime/java-tests.log 2>&1
 cat backend/target/surefire-reports/com.acme.mdm.BusinessTest.txt
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = http ] || [ "$mdm_suite" = operations ]; then
 scripts/test-start.sh
 if [ "$mdm_suite" != operations ]; then python tests/integration.py;fi
 if [ "$mdm_suite" != http ]; then python tests/operations.py;fi
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = browser ]; then
 scripts/start.sh
 npm --prefix frontend run build --cache /workspace/.tools/npm-cache
 (cd frontend && npm exec --cache /workspace/.tools/npm-cache -- playwright test)
fi
