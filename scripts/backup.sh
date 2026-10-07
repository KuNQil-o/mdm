#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p work/backups
mdm_stamp=$(date -u +%Y%m%dT%H%M%SZ)
mdm_archive=${1:-work/backups/mdm-v4-$mdm_stamp.dump}
umask 077
docker compose exec -T db pg_dump -U mdm -d mdm_v4 -Fc > "$mdm_archive"
echo "备份已保存：$mdm_archive"
