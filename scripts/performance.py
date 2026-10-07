"""Sustained 20/s assignment baseline against the running development API."""

import sys, time, statistics
from pathlib import Path
from uuid import uuid4
from concurrent.futures import ThreadPoolExecutor
import httpx

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
from app.common import dumps

base = "http://localhost:8080/api/v1"
run = "PERF_" + uuid4().hex[:10]
with httpx.Client(base_url=base, trust_env=False, timeout=30) as client:
    client.post("/dev/login", json={"userCode": "editor"}).raise_for_status()
    client.headers["X-Tenant-Code"] = "TENANT-A"
    r = client.post(
        "/caller-systems",
        json={"code": run, "status": "ACTIVE", "allowedCategoryCodes": ["RESISTOR"]},
    )
    r.raise_for_status()
    caller = r.json()

    def execute(index, preview=False):
        body = {
            "callerSystemCode": run,
            "categoryCode": "RESISTOR",
            "attributes": {
                "resistance": run + "_" + str(index),
                "tolerance": "1%",
                "power": "0.25W",
                "package": "0603",
                "manufacturer": "YAGEO",
            },
        }
        if not preview:
            body["sourceRecordKey"] = "PERF-" + str(index)
        started = time.perf_counter()
        r = client.post(
            "/material-number-previews" if preview else "/material-number-assignments",
            json=body,
            headers={
                "X-Caller-Key": caller["apiKey"],
                "Idempotency-Key": run + "-" + str(index),
            },
        )
        return {
            "ms": (time.perf_counter() - started) * 1000,
            "status": r.status_code,
            "materialNo": r.json().get("materialNo"),
        }

    results = []
    start = time.perf_counter()
    with ThreadPoolExecutor(max_workers=24) as executor:
        futures = []
        for index in range(200):
            wait = start + index / 20 - time.perf_counter()
            if wait > 0:
                time.sleep(wait)
            futures.append(executor.submit(execute, index))
        results = [f.result() for f in futures]
    elapsed = time.perf_counter() - start
    previews = [execute(i, True) for i in range(100)]

    def percentile(values, p):
        return sorted(values)[int((len(values) - 1) * p)]

    evidence = {
        "requests": len(results),
        "schedulePerSecond": 20,
        "elapsedSeconds": elapsed,
        "successes": sum(r["status"] == 200 for r in results),
        "assignmentP50Ms": percentile([r["ms"] for r in results], 0.5),
        "assignmentP95Ms": percentile([r["ms"] for r in results], 0.95),
        "assignmentP99Ms": percentile([r["ms"] for r in results], 0.99),
        "previewP95Ms": percentile([r["ms"] for r in previews], 0.95),
        "uniqueNumbers": len({r["materialNo"] for r in results}),
    }
    evidence["passed"] = (
        evidence["successes"] == 200
        and evidence["uniqueNumbers"] == 200
        and evidence["assignmentP95Ms"] <= 500
        and evidence["previewP95Ms"] <= 500
    )
    (ROOT / ".runtime/performance-results.json").write_text(dumps(evidence, indent=2))
    print(dumps(evidence, indent=2))
    if not evidence["passed"]:
        sys.exit(1)
