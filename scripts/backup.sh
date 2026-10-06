#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$mdm_root"
umask 077
mdm_dir="$mdm_root/.runtime/backups/$(date -u +%Y%m%dT%H%M%S)-$$"
mkdir -p "$mdm_dir"
docker compose exec -T db pg_dump -U mdm -d "${BACKUP_DATABASE:-mdm}" -Fc > "$mdm_dir/mdm.dump"
docker compose exec -T db pg_dump -U mdm -d "${BACKUP_ERP_DATABASE:-mdm_erp_v3}" -Fc > "$mdm_dir/erp.dump"
(cd "$mdm_dir" && sha256sum mdm.dump erp.dump > SHA256SUMS)
printf '备份路径：%s\n' "$mdm_dir"
