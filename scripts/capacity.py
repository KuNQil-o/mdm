"""NFR-01 isolated 1M-ledger/500-category index and API-capacity fixture.

Creates a NEW database. Bulk inserts synthetic, fully explained assignments with all
constraints/triggers enabled, then verifies the real service's search/issue/reuse.
This is a data-size benchmark; the independent performance test covers HTTP throughput.
"""

import os, sys, time
from pathlib import Path
from uuid import uuid4
import psycopg
from psycopg.rows import dict_row

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
server_url = os.getenv(
    "CAPACITY_SERVER_URL", "postgresql://mdm:mdm_v4_dev_only@localhost:5433/postgres"
)
name = "v4_capacity_" + uuid4().hex[:10]
with psycopg.connect(server_url, autocommit=True) as admin:
    admin.execute(
        psycopg.sql.SQL("CREATE DATABASE {}").format(psycopg.sql.Identifier(name))
    )
os.environ["DATABASE_URL"] = server_url.rsplit("/", 1)[0] + "/" + name
os.environ["DEV_CREDENTIAL_FILE"] = str(ROOT / "work/capacity-callers.json")
from app.db import pool, migrate, one, rows, Jsonb
from app.seed import seed
from app.common import dumps, identifier
from app.security import Context, PERMISSIONS, caller_context
from app import service as s

pool.open()
pool.wait()
migrate()
seed()
start = time.perf_counter()
templates = []
with pool.connection() as conn:
    tid = one(conn, "SELECT id FROM tenant WHERE code='TENANT-A'")["id"]
    editor = one(conn, "SELECT id FROM app_user WHERE code='editor'")["id"]
    reviewer = one(conn, "SELECT id FROM app_user WHERE code='reviewer'")["id"]
    edit = Context(
        tid, str(editor), PERMISSIONS - {"RELEASE_PUBLISH"}, {"*"}, str(uuid4())
    )
    review = Context(
        tid, str(reviewer), {"PREVIEW", "RELEASE_PUBLISH"}, {"*"}, str(uuid4())
    )
    for index in range(500):
        code = "CAP_" + str(index).zfill(3)
        cat = s.save_config(
            conn, edit, "categories", {"code": code, "definition": {"name": code}}
        )
        schema = s.save_config(
            conn,
            edit,
            "schemas",
            {
                "code": code + "_SCHEMA",
                "categoryCode": code,
                "definition": {
                    "attributes": [
                        {
                            "attributeCode": "name",
                            "type": "STRING",
                            "required": True,
                            "case": "UPPER",
                        }
                    ]
                },
            },
        )
        identity = s.save_config(
            conn,
            edit,
            "identity-definitions",
            {
                "code": code + "_ID",
                "categoryCode": code,
                "definition": {"attributes": ["name"]},
            },
        )
        rule = s.save_config(
            conn,
            edit,
            "code-rules",
            {
                "code": code + "_RULE",
                "categoryCode": code,
                "definition": {
                    "separator": "-",
                    "segments": [
                        {"type": "CONSTANT", "value": "CP"},
                        {"type": "ATTRIBUTE", "field": "name"},
                    ],
                },
            },
        )
        rel = s.save_release(
            conn,
            edit,
            {
                "code": code + "_RELEASE",
                "categoryCode": code,
                "refs": {
                    "categoryVersionId": str(cat["id"]),
                    "schemaVersionId": str(schema["id"]),
                    "identityDefinitionVersionId": str(identity["id"]),
                    "codeRuleVersionId": str(rule["id"]),
                },
                "samples": [
                    {
                        "attributes": {"name": "SAMPLE"},
                        "expectedMaterialNo": "CP-SAMPLE",
                    }
                ],
            },
        )
        rel = s.release_action(conn, edit, rel["id"], "submit", rel["rowVersion"])
        rel = s.release_action(conn, review, rel["id"], "publish", rel["rowVersion"])
        templates.append(
            (
                index,
                cat["categoryId"],
                cat["id"],
                schema["id"],
                identity["id"],
                rule["id"],
                rel["id"],
            )
        )
    caller = s.save_caller(
        conn,
        edit,
        {
            "code": "CAPACITY_CALLER",
            "status": "ACTIVE",
            "allowedCategoryCodes": ["CAP_" + str(i).zfill(3) for i in range(500)],
        },
    )
    conn.execute(
        "CREATE TABLE capacity_template(slot integer PRIMARY KEY,category_id uuid,category_version_id uuid,schema_id uuid,identity_id uuid,rule_id uuid,release_id uuid)"
    )
    with conn.cursor().copy("COPY capacity_template FROM STDIN") as copy:
        for template in templates:
            copy.write_row(template)
print(
    "500 category releases published; populating 1,000,000 immutable assignments.",
    flush=True,
)
for offset in range(0, 1000000, 2000):
    with pool.connection() as conn:
        conn.execute(
            """INSERT INTO material_assignment(id,tenant_id,category_id,material_no,identity_canonical,identity_hash,collision_index,release_id,schema_version_id,
          validation_rule_version_ids,derivation_rule_version_ids,identity_definition_version_id,code_rule_version_id,category_version_id,input_snapshot,mapped_snapshot,
          normalized_snapshot,derived_snapshot,code_segment_snapshot,validation_snapshot,issue_request_id)
          SELECT gen_random_uuid(),%s,t.category_id,'CP-SKU_'||i,canonical,encode(digest(canonical,'sha256'),'hex'),0,t.release_id,t.schema_id,
          '[]','[]',t.identity_id,t.rule_id,t.category_version_id,jsonb_build_object('name','SKU_'||i),jsonb_build_object('name','SKU_'||i),jsonb_build_object('name','SKU_'||i),'{}',
          jsonb_build_array(jsonb_build_object('index',0,'definition',jsonb_build_object('type','CONSTANT','value','CP'),'input',null,'output','CP'),
          jsonb_build_object('index',1,'definition',jsonb_build_object('type','ATTRIBUTE','field','name'),'input','SKU_'||i,'output','SKU_'||i)),
          '[]','CAPACITY-'||i FROM generate_series(%s::integer,%s::integer) i JOIN capacity_template t ON t.slot=(i-1)%%500
          CROSS JOIN LATERAL (SELECT '[['||'"name","SKU_'||i||'"]]' AS canonical) q""",
            (tid, offset + 1, offset + 2000),
        )
        conn.execute(
            """INSERT INTO source_binding(tenant_id,caller_system_id,category_id,source_record_key,assignment_id)
           SELECT tenant_id,%s,category_id,issue_request_id,id FROM material_assignment WHERE tenant_id=%s AND issue_request_id IN
           (SELECT 'CAPACITY-'||i FROM generate_series(%s::integer,%s::integer) i)""",
            (identifier(caller["id"]), tid, offset + 1, offset + 2000),
        )
    if (offset + 2000) % 100000 == 0:
        print(f"{offset + 2000:,} assignments loaded", flush=True)
with pool.connection() as conn:
    conn.execute("ANALYZE material_assignment")
    conn.execute("ANALYZE source_binding")
    context = caller_context(
        conn,
        {"x-tenant-code": "TENANT-A", "x-caller-key": caller["apiKey"]},
        str(uuid4()),
        True,
    )
    count = one(conn, "SELECT count(*) AS n FROM material_assignment")["n"]
    count_categories = one(
        conn, "SELECT count(*) AS n FROM category WHERE tenant_id=%s", (tid,)
    )["n"]
    t = time.perf_counter()
    ledger = s.search(conn, context, {"categoryCode": "CAP_000", "limit": "50"})
    search_ms = (time.perf_counter() - t) * 1000
    assert ledger["total"] == 2000 and len(ledger["items"]) == 50
    t = time.perf_counter()
    reused = s.issue(
        conn,
        context,
        {
            "callerSystemCode": "CAPACITY_CALLER",
            "categoryCode": "CAP_000",
            "sourceRecordKey": "NEW-BINDING",
            "attributes": {"name": "SKU_1"},
        },
        str(uuid4()),
    )
    reuse_ms = (time.perf_counter() - t) * 1000
    assert reused["reused"] and reused["materialNo"] == "CP-SKU_1"
    t = time.perf_counter()
    issued = s.issue(
        conn,
        context,
        {
            "callerSystemCode": "CAPACITY_CALLER",
            "categoryCode": "CAP_000",
            "sourceRecordKey": "NEW-ISSUE",
            "attributes": {"name": "NEW_ITEM"},
        },
        str(uuid4()),
    )
    issue_ms = (time.perf_counter() - t) * 1000
    assert issued["status"] == "ISSUED"
    plan = conn.execute(
        "EXPLAIN SELECT * FROM material_assignment WHERE tenant_id=%s AND category_id=%s AND identity_hash=%s",
        (tid, templates[0][1], "not-an-existing-hash"),
    ).fetchall()
    plan_text = "\n".join(str(row) for row in plan)
    assert "Index" in plan_text
result = {
    "passed": count == 1000000
    and count_categories >= 500
    and max(search_ms, reuse_ms, issue_ms) <= 500,
    "database": name,
    "assignments": count,
    "categories": count_categories,
    "searchMs": search_ms,
    "reuseMs": reuse_ms,
    "issueMs": issue_ms,
    "loadSeconds": time.perf_counter() - start,
    "constraintsEnabled": True,
    "indexUsed": True,
}
(ROOT / ".runtime/capacity-results.json").write_text(dumps(result, indent=2))
print(dumps(result, indent=2))
pool.close()
if not result["passed"]:
    sys.exit(1)
