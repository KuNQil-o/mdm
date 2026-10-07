import os, sqlite3, subprocess, sys
from uuid import uuid4
from conftest import ROOT, ok
from app.common import loads


def test_caller_owns_record_save_and_retry(clients, server):
    caller = ok(
        clients["editor"],
        "/caller-systems",
        "POST",
        {
            "code": "EXT_" + uuid4().hex[:10],
            "status": "ACTIVE",
            "allowedCategoryCodes": ["GLASS_CLOTH"],
        },
    )
    record = "EXT-" + uuid4().hex
    env = {
        **os.environ,
        "MDM_URL": server,
        "MDM_CALLER": caller["code"],
        "MDM_CALLER_KEY": caller["apiKey"],
    }
    first = subprocess.run(
        [sys.executable, "scripts/caller_example.py", record],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=True,
    )
    retry = subprocess.run(
        [sys.executable, "scripts/caller_example.py", record],
        cwd=ROOT,
        env=env,
        text=True,
        capture_output=True,
        check=True,
    )
    a, b = loads(first.stdout), loads(retry.stdout)
    assert (
        a["savedByCaller"]
        and b["savedByCaller"]
        and a["assignmentId"] == b["assignmentId"]
        and a["materialNo"] == b["materialNo"]
    )
    with sqlite3.connect(ROOT / "work/caller-business.db") as conn:
        saved = conn.execute(
            "SELECT material_no,assignment_id FROM caller_material WHERE id=?",
            (record,),
        ).fetchone()
        assert saved == (a["materialNo"], a["assignmentId"])
