#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mdm_suite=${1:-all}
case "$mdm_suite" in all|backend|browser|operations|security) ;; *) echo 'Usage: scripts/test.sh [all|backend|browser|operations|security]' >&2;exit 2;; esac
mkdir -p .runtime work
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = backend ]; then
 if [ -z "${TEST_DATABASE_URL:-}" ]; then docker compose up -d --wait --wait-timeout 60 db; fi
 backend/.venv/bin/pytest tests -q --junitxml=.runtime/v4-tests.xml
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = browser ]; then
 npm --prefix frontend run build
 scripts/start.sh
 (cd frontend && npm exec -- playwright test)
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = operations ]; then
 backend/.venv/bin/python scripts/verify_restore.py
 backend/.venv/bin/python scripts/verify_restart.py
fi
if [ "$mdm_suite" = all ] || [ "$mdm_suite" = security ]; then
 npm --prefix frontend audit --audit-level=low --json > work/npm-audit.json
 backend/.venv/bin/pip-audit -r backend/requirements.txt --format json --output work/python-audit.json
fi
