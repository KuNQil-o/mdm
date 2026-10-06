#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
source "$mdm_root/scripts/env.sh"
curl --fail-with-body --silent --show-error -X POST "http://localhost:${PORT:-8080}/api/dev/seed"
echo
python3 "$mdm_root/scripts/erp_demo.py"
