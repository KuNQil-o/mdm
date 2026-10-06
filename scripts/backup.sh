#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
cd "$mdm_root"
mdm_dir="$mdm_root/.runtime/backups/$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -p "$mdm_dir"
docker compose exec -T db pg_dump -U mdm -d "${BACKUP_DATABASE:-mdm}" -Fc > "$mdm_dir/mdm.dump"
python - "$mdm_dir" <<'PY'
import sqlite3,sys,pathlib
root=pathlib.Path('.runtime/erp.sqlite')
if root.exists():
 with sqlite3.connect(root) as source,sqlite3.connect(pathlib.Path(sys.argv[1])/'erp.sqlite') as target: source.backup(target)
PY
sha256sum "$mdm_dir"/* > "$mdm_dir/SHA256SUMS"
printf '备份路径：%s\n' "$mdm_dir"
