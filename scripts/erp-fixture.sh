#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$mdm_root"
docker compose up -d --wait --wait-timeout 60 db
if [ "$(docker compose exec -T db psql -U mdm -d postgres -Atc "select count(*) from pg_roles where rolname='mdm_erp_reader'")" = 0 ]; then
 docker compose exec -T db psql -U mdm -d postgres -v ON_ERROR_STOP=1 -c "create role mdm_erp_reader login password 'erp_read_dev_only';"
fi
if [ "$(docker compose exec -T db psql -U mdm -d postgres -Atc "select count(*) from pg_database where datname='mdm_erp_v3'")" = 0 ]; then
 docker compose exec -T db createdb -U mdm mdm_erp_v3
fi
docker compose exec -T db psql -U mdm -d mdm_erp_v3 -v ON_ERROR_STOP=1 < tests/fixtures/erp_v3.sql
