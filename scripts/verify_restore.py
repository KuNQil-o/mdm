"""Consistent snapshot backup; restore into a fresh DB, never overwrite a database."""

import os
import subprocess
import sys
from pathlib import Path
from uuid import uuid4
from urllib.parse import urlparse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
import psycopg
from psycopg.rows import dict_row
from app.common import dumps, digest

DATABASE = os.getenv("RESTORE_SOURCE_DB", "mdm_v4")
require_name = lambda s: bool(s) and all(c.isalnum() or c == "_" for c in s)
if not require_name(DATABASE):
    raise ValueError("Invalid source database name")
RESTORE = "v4_restore_" + uuid4().hex[:12]
archive = ROOT / "work" / (RESTORE + ".dump")
archive.parent.mkdir(exist_ok=True)
URL = os.getenv(
    "RESTORE_SERVER_URL", "postgresql://mdm:mdm_v4_dev_only@localhost:5433/postgres"
)
container = ["docker", "compose", "exec", "-T", "db"]


def fingerprint(conn):
    result = {}
    tables = [
        "material_assignment",
        "sequence_counter",
        "source_binding",
        "assignment_idempotency",
        "config_version",
        "release_package",
        "caller_system",
        "operation_log",
        "tenant",
        "membership",
        "role",
    ]
    for table in tables:
        records = conn.execute(
            "SELECT row_to_json(t) AS value FROM " + table + " t"
        ).fetchall()
        values = sorted(dumps(r["value"], sort_keys=True) for r in records)
        # Hash only. Credentials, source snapshots and member data never enter evidence output.
        result[table] = {"count": len(values), "digest": digest("\n".join(values))}
    return result


with psycopg.connect(
    URL.replace("/postgres", "/" + DATABASE), row_factory=dict_row
) as source:
    source.execute("BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY")
    snapshot = source.execute("SELECT pg_export_snapshot() AS snapshot").fetchone()[
        "snapshot"
    ]
    expected = fingerprint(source)
    with archive.open("wb") as output:
        subprocess.run(
            container
            + ["pg_dump", "-U", "mdm", "-d", DATABASE, "-Fc", "--snapshot=" + snapshot],
            cwd=ROOT,
            stdout=output,
            check=True,
        )
# Source remains untouched; a new restore target is intentionally retained for inspection.
subprocess.run(container + ["createdb", "-U", "mdm", RESTORE], cwd=ROOT, check=True)
with archive.open("rb") as input_stream:
    subprocess.run(
        container + ["pg_restore", "-U", "mdm", "-d", RESTORE, "--exit-on-error"],
        cwd=ROOT,
        stdin=input_stream,
        check=True,
    )
with psycopg.connect(
    URL.replace("/postgres", "/" + RESTORE), row_factory=dict_row
) as restored:
    actual = fingerprint(restored)
    invariants = {
        "orphanBindings": restored.execute(
            "SELECT count(*) AS n FROM source_binding b LEFT JOIN material_assignment a ON a.id=b.assignment_id AND a.tenant_id=b.tenant_id WHERE a.id IS NULL"
        ).fetchone()["n"],
        "duplicateNumbers": restored.execute(
            "SELECT count(*) AS n FROM (SELECT tenant_id,material_no FROM material_assignment GROUP BY tenant_id,material_no HAVING count(*)>1) x"
        ).fetchone()["n"],
        "duplicateIdentities": restored.execute(
            "SELECT count(*) AS n FROM (SELECT tenant_id,category_id,identity_canonical FROM material_assignment GROUP BY tenant_id,category_id,identity_canonical HAVING count(*)>1) x"
        ).fetchone()["n"],
    }
    restored.execute("SELECT * FROM source_binding LIMIT 1").fetchone()
passed = expected == actual and not any(invariants.values())
evidence = {
    "passed": passed,
    "source": DATABASE,
    "restoreDatabase": RESTORE,
    "tables": actual,
    "invariants": invariants,
}
(ROOT / ".runtime").mkdir(exist_ok=True)
(ROOT / ".runtime/restore-results.json").write_text(dumps(evidence, indent=2))
print(dumps(evidence, indent=2))
if not passed:
    sys.exit(1)
