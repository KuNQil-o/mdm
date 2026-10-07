"""Verify API restart and response-loss retry against a real durable Assignment."""

import os
import subprocess
import sys
import time
from pathlib import Path
from uuid import uuid4
import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from app.common import dumps

URL = "http://localhost:8080/api/v1"
with httpx.Client(base_url=URL, trust_env=False, timeout=30) as c:
    c.post("/dev/login", json={"userCode": "editor"}).raise_for_status()
    c.headers["X-Tenant-Code"] = "TENANT-A"
    caller = c.post(
        "/caller-systems",
        json={
            "code": "RESTART_" + uuid4().hex[:12],
            "status": "ACTIVE",
            "allowedCategoryCodes": ["RESISTOR"],
        },
    ).json()
    request = {
        "callerSystemCode": caller["code"],
        "categoryCode": "RESISTOR",
        "sourceRecordKey": "RESTART-1",
        "attributes": {
            "resistance": "RESTART" + uuid4().hex[:8],
            "tolerance": "1%",
            "power": "0.25W",
            "package": "0603",
            "manufacturer": "YAGEO",
        },
    }
    headers = {"X-Caller-Key": caller["apiKey"], "Idempotency-Key": str(uuid4())}
    first = c.post("/material-number-assignments", json=request, headers=headers)
    first.raise_for_status()
    old = first.json()
    subprocess.run(["bash", "scripts/stop.sh"], cwd=ROOT, check=True)
    subprocess.run(["bash", "scripts/start.sh"], cwd=ROOT, check=True)
    replay = c.post("/material-number-assignments", json=request, headers=headers)
    replay.raise_for_status()
    new = replay.json()
    passed = (
        old["assignmentId"] == new["assignmentId"]
        and old["materialNo"] == new["materialNo"]
        and new["reused"]
    )
    evidence = {
        "passed": passed,
        "assignmentId": new["assignmentId"],
        "materialNo": new["materialNo"],
        "reused": new["reused"],
    }
    (ROOT / ".runtime/restart-results.json").write_text(dumps(evidence, indent=2))
    print(dumps(evidence))
    if not passed:
        sys.exit(1)
