"""Caller-owned record creation -> Assignment API -> caller-owned save, independent of MDM Core."""

import hashlib
import hmac
import os
import sqlite3
import sys
import time
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
from app.common import dumps, loads
import httpx

base = os.getenv("MDM_URL", "http://localhost:8080/api/v1").rstrip("/")
tenant = os.getenv("MDM_TENANT", "TENANT-A")
caller = os.getenv("MDM_CALLER", "ERP_DEMO")
key = os.getenv("MDM_CALLER_KEY")
if not key:
    raise SystemExit("请设置 MDM_CALLER_KEY；不要把凭据加入代码或命令参数。")
record = sys.argv[1] if len(sys.argv) > 1 else "example-" + uuid4().hex[:8]
attributes = {
    "clothType": "7628",
    "manufacturer": "HH",
    "treatment": "A",
    "basisWeight": 210,
    "width": 1270,
}
store = Path("work/caller-business.db")
store.parent.mkdir(exist_ok=True)
with sqlite3.connect(store) as db:
    db.execute(
        "CREATE TABLE IF NOT EXISTS caller_material(id text PRIMARY KEY,attributes text NOT NULL,material_no text,assignment_id text,idempotency_key text NOT NULL)"
    )
    db.execute(
        "INSERT OR IGNORE INTO caller_material VALUES(?,?,NULL,NULL,?)",
        (record, dumps(attributes), str(uuid4())),
    )
    raw, idem = db.execute(
        "SELECT attributes,idempotency_key FROM caller_material WHERE id=?", (record,)
    ).fetchone()
    body = dumps(
        {
            "callerSystemCode": caller,
            "sourceRecordKey": record,
            "categoryCode": "GLASS_CLOTH",
            "attributes": loads(raw),
        }
    )
    # The business record and retry key are durable BEFORE the HTTP request.
    db.commit()
    stamp = str(int(time.time()))
    nonce = uuid4().hex
    message = "\n".join(
        [
            stamp,
            nonce,
            "POST",
            "/api/v1/material-number-assignments",
            hashlib.sha256(body.encode()).hexdigest(),
        ]
    )
    headers = {
        "Content-Type": "application/json",
        "X-Tenant-Code": tenant,
        "X-Caller-Key": key,
        "Idempotency-Key": idem,
        "X-Timestamp": stamp,
        "X-Nonce": nonce,
        "X-Signature": hmac.new(
            key.encode(), message.encode(), hashlib.sha256
        ).hexdigest(),
    }
    r = httpx.post(
        base + "/material-number-assignments",
        content=body.encode(),
        headers=headers,
        timeout=30,
        trust_env=False,
    )
    if r.status_code >= 300:
        raise SystemExit(r.text)
    result = r.json()
    db.execute(
        "UPDATE caller_material SET material_no=?,assignment_id=? WHERE id=?",
        (result["materialNo"], result["assignmentId"], record),
    )
    db.commit()
    print(
        dumps(
            {
                "sourceRecordKey": record,
                "materialNo": result["materialNo"],
                "assignmentId": result["assignmentId"],
                "status": result["status"],
                "savedByCaller": True,
            }
        )
    )
