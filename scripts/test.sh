#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$mdm_root"
mdm_suite=${1:-all}
case "$mdm_suite" in all|backend|http|browser|operations|erp) ;; *) echo 'Usage: scripts/test.sh [all|backend|http|browser|operations|erp]' >&2;exit 2;; esac
mkdir -p .runtime
scripts/start.sh
scripts/seed.sh > .runtime/v3-seed.log 2>&1
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = backend ]; then
 if [ "$(docker compose exec -T db psql -U mdm -d postgres -Atc "select count(*) from pg_database where datname='mdm_test'")" = 0 ]; then docker compose exec -T db createdb -U mdm mdm_test;fi
 scripts/mvn.sh -q package > .runtime/v3-build.log 2>&1
 cat backend/target/surefire-reports/com.acme.mdm.*Test.txt
 scripts/stop.sh
 scripts/start.sh
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = http ]; then
 python3 tests/v3_acceptance.py > .runtime/v3-acceptance.log 2>&1
 tail -5 .runtime/v3-acceptance.log
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = erp ]; then
 python3 tests/erp_workflow.py > .runtime/erp-workflow.log 2>&1
 tail -1 .runtime/erp-workflow.log
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = browser ]; then
 if [ ! -f .runtime/v3-acceptance-fixtures.json ]; then python3 tests/v3_acceptance.py > .runtime/v3-acceptance.log 2>&1;fi
 npm --prefix frontend run build --cache /workspace/.tools/npm-cache
 (cd frontend && npm exec --cache /workspace/.tools/npm-cache -- playwright test)
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = operations ]; then
 python3 tests/v3_operations.py > .runtime/v3-operations.log 2>&1
 tail -1 .runtime/v3-operations.log
 python3 tests/v3_restart.py > .runtime/v3-restart.log 2>&1
 tail -1 .runtime/v3-restart.log
 scripts/stop.sh
 # A new restore target is created for each run; existing databases are never overwritten.
 if python3 tests/v3_backup_verify.py > .runtime/v3-backup.log 2>&1;then mdm_restore_exit=0;else mdm_restore_exit=$?;fi
 scripts/start.sh
 cat .runtime/v3-backup.log
 [ "$mdm_restore_exit" = 0 ]
fi
