#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$mdm_root"
mdm_dump=${1:?提供备份文件的绝对路径}
mdm_target="mdm_restore_$(date -u +%Y%m%d_%H%M%S)"
docker compose exec -T db createdb -U mdm "$mdm_target"
docker compose exec -T db pg_restore -U mdm -d "$mdm_target" --exit-on-error < "$mdm_dump"
docker compose exec -T db psql -U mdm -d "$mdm_target" -v ON_ERROR_STOP=1 -c 'select count(*) as materials from material; select count(*) as revisions from material_revision; select count(*) as events from outbox_event; select count(*) as identities from external_identity; select count(*) as imports from import_job; select max(version) as migration from flyway_schema_history;'
printf '恢复到独立库 %s；未覆盖原库。\n' "$mdm_target"
