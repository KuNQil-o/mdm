#!/usr/bin/env bash
set -euo pipefail
curl --fail-with-body --silent --show-error -X POST "http://localhost:${PORT:-8080}/api/dev/seed"
echo
