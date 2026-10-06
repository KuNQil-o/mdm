#!/usr/bin/env bash
set -euo pipefail
mdm_root=$(cd "$(dirname "$0")/.." && pwd)
for mdm_name in frontend backend erp; do
 mdm_pidfile="$mdm_root/.runtime/$mdm_name.pid"
 if [ -f "$mdm_pidfile" ]; then
  mdm_pid=$(cat "$mdm_pidfile")
  if [ -r "/proc/$mdm_pid/cmdline" ] && tr '\0' ' ' < "/proc/$mdm_pid/cmdline" | rg -q "$mdm_root"; then kill "$mdm_pid"; fi
  rm -f "$mdm_pidfile"
 fi
done
# PostgreSQL and its volume are deliberately retained.
