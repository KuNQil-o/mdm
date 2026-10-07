from concurrent.futures import ThreadPoolExecutor
from copy import deepcopy
from decimal import Decimal
from uuid import uuid4
import json
import os
import subprocess
import sys
import time
from pathlib import Path
import httpx
import psycopg
import pytest
from conftest import ok, issue, request_body, ROOT
from app.db import pool, one, rows, Jsonb
from app.common import loads, dumps, identifier
from app import engine, service
from app.security import caller_context


def test_ac01_normalization_and_non_identity_change(factory, clients):
    f = factory()
    c = clients["editor"]
    r = issue(
        c, f, request_body(f, name=" abc ", width={"value": "1.25", "unit": "CM"})
    ).json()
    assert r["status"] == "ISSUED"
    again = issue(c, f, request_body(f, name="ABC", width=12.5, note="changed")).json()
    assert again["assignmentId"] == r["assignmentId"] and again["reused"]
    changed = issue(c, f, request_body(f, name="DIFFERENT"))
    assert (
        changed.status_code == 409
        and changed.json()["code"] == "SOURCE_IDENTITY_CHANGED_AFTER_ISSUE"
    )
    explanation = ok(c, "/material-number-assignments/" + r["assignmentId"])
    assert (
        explanation["normalizedSnapshot"]["name"] == "ABC"
        and explanation["normalizedSnapshot"]["width"] == 12.5
    )
    assert (
        explanation["inputSnapshot"]["name"] == " abc "
        and "note" not in explanation["inputSnapshot"]
    )


def test_ac02_response_lost_idempotent_retry(factory, clients):
    f = factory()
    c = clients["editor"]
    key = str(uuid4())
    body = request_body(f)
    first = issue(c, f, body, key).json()
    retry = issue(c, f, body, key).json()
    assert (
        retry["assignmentId"] == first["assignmentId"]
        and retry["materialNo"] == first["materialNo"]
        and retry["reused"]
    )
    with pool.connection() as conn:
        assert (
            one(
                conn,
                "SELECT count(*) AS n FROM material_assignment WHERE category_id=%s",
                (identifier(f["category"]["categoryId"]),),
            )["n"]
            == 1
        )
        assert (
            one(
                conn,
                "SELECT last_value FROM sequence_counter WHERE code_rule_version_id=%s",
                (identifier(f["code"]["id"]),),
            )["last_value"]
            == 1
        )


def test_ac03_idempotency_conflict_and_caller_scope(factory, clients):
    f = factory()
    key = str(uuid4())
    c = clients["editor"]
    assert issue(c, f, key=key).status_code == 200
    conflict = issue(c, f, request_body(f, note="new semantics"), key)
    assert (
        conflict.status_code == 409
        and conflict.json()["code"] == "IDEMPOTENCY_CONFLICT"
    )
    # The same key is independently scoped to another caller, even with identical identity.
    other = issue(c, f, request_body(f, source="other", caller=1), key, caller=1)
    assert other.status_code == 200 and other.json()["reused"]


def test_ac04_source_binding_new_keys(factory, clients):
    f = factory()
    c = clients["editor"]
    body = request_body(f)
    with ThreadPoolExecutor(max_workers=100) as executor:
        result = list(executor.map(lambda _: issue(c, f, body), range(100)))
    assert all(r.status_code == 200 for r in result), [
        (r.status_code, r.text) for r in result if r.status_code != 200
    ]
    assert len({r.json()["assignmentId"] for r in result}) == 1
    assert sum(not r.json()["reused"] for r in result) == 1


def test_ac05_cross_system_identity_reuse_and_review_policy(factory, clients):
    c = clients["editor"]
    f = factory()
    a = issue(c, f, request_body(f, source="A")).json()
    b = issue(c, f, request_body(f, source="B", caller=1), caller=1).json()
    assert a["assignmentId"] == b["assignmentId"] and b["reused"]
    assert (
        len(
            ok(c, "/material-number-assignments/" + a["assignmentId"])["sourceBindings"]
        )
        == 2
    )
    reviewed = factory(policy="REVIEW_ON_DUPLICATE")
    assert issue(c, reviewed).status_code == 200
    duplicate = issue(c, reviewed, request_body(reviewed, source="other"))
    assert (
        duplicate.status_code == 409 and duplicate.json()["code"] == "IDENTITY_CONFLICT"
    )


def test_ac06_100_concurrent_same_identity(factory, clients):
    f = factory()
    c = clients["editor"]
    with ThreadPoolExecutor(max_workers=100) as executor:
        result = list(
            executor.map(
                lambda i: issue(c, f, request_body(f, source="source-" + str(i))),
                range(100),
            )
        )
    assert all(r.status_code == 200 for r in result), [
        (r.status_code, r.text) for r in result if r.status_code != 200
    ]
    assert len({r.json()["assignmentId"] for r in result}) == 1
    assert len({r.json()["materialNo"] for r in result}) == 1
    assert sum(not r.json()["reused"] for r in result) == 1
    row = ok(c, "/material-number-assignments/" + result[0].json()["assignmentId"])
    assert len(row["sourceBindings"]) == 100


def test_ac07_100_distinct_identity_unique_numbers(factory, clients):
    f = factory()
    c = clients["editor"]
    with ThreadPoolExecutor(max_workers=100) as executor:
        result = list(
            executor.map(
                lambda i: issue(
                    c,
                    f,
                    request_body(f, source="source-" + str(i), name="NAME" + str(i)),
                ),
                range(100),
            )
        )
    assert all(r.status_code == 200 for r in result)
    assert len({r.json()["materialNo"] for r in result}) == 100
    assert len({r.json()["assignmentId"] for r in result}) == 100
    values = sorted(int(r.json()["materialNo"].split("-")[-1]) for r in result)
    assert values == list(range(1, 101))


def test_ac08_100_previews_no_sequence_or_assignment(factory, clients):
    f = factory()
    c = clients["editor"]
    body = request_body(f)
    body.pop("sourceRecordKey")
    with ThreadPoolExecutor(max_workers=30) as executor:
        result = list(
            executor.map(
                lambda _: c.post("/material-number-previews", json=body), range(100)
            )
        )
    assert all(
        r.status_code == 200 and r.json()["sequenceReserved"] is False for r in result
    )
    assert all("{SEQUENCE:MAIN:6}" in r.json()["materialNo"] for r in result)
    with pool.connection() as conn:
        assert (
            one(
                conn,
                "SELECT count(*) AS n FROM sequence_counter WHERE code_rule_version_id=%s",
                (identifier(f["code"]["id"]),),
            )["n"]
            == 0
        )
        assert (
            one(
                conn,
                "SELECT count(*) AS n FROM material_assignment WHERE category_id=%s",
                (identifier(f["category"]["categoryId"]),),
            )["n"]
            == 0
        )


def test_ac09_query_explanation_history(factory, clients):
    f = factory()
    c = clients["editor"]
    issued = issue(c, f).json()
    by_no = ok(c, "/material-number-assignments/by-no/" + issued["materialNo"])
    by_source = ok(
        c,
        "/material-number-assignments/by-source/"
        + f["callers"][0]["code"]
        + "/record-1?categoryCode="
        + f["prefix"],
    )
    assert by_no["assignmentId"] == by_source["assignmentId"] == issued["assignmentId"]
    assert by_no["issueRequestId"] == issued["requestId"]
    assert by_no["issueTraceId"] == issued["traceId"]
    with pool.connection() as conn:
        log = one(
            conn,
            "SELECT trace_id FROM operation_log WHERE request_id=%s AND action='ISSUE'",
            (issued["requestId"],),
        )
        assert log["trace_id"] == issued["traceId"]
        metric = one(
            conn,
            "SELECT trace_id FROM request_metric WHERE request_id=%s",
            (issued["requestId"],),
        )
        assert metric["trace_id"] == issued["traceId"]
    for key in [
        "inputSnapshot",
        "mappedSnapshot",
        "normalizedSnapshot",
        "derivedSnapshot",
        "codeSegmentSnapshot",
        "validationSnapshot",
        "configurationSnapshot",
        "identityCanonical",
        "identityHash",
        "releasePackageVersionId",
        "sourceBindings",
    ]:
        assert key in by_no
    assert by_no["configurationSnapshot"]["codeRule"]["id"] == f["code"]["id"]
    search = ok(
        c,
        "/material-number-assignments?categoryCode="
        + f["prefix"]
        + "&identityHash="
        + by_no["identityHash"],
    )
    assert search["total"] == 1


def test_ac10_published_immutable_new_version_diff(factory, clients):
    f = factory()
    c = clients["editor"]
    review = clients["reviewer"]
    issued = issue(c, f).json()
    old = ok(c, "/schemas/" + f["schema"]["id"])
    r = c.patch(
        "/schemas/" + old["id"],
        json={"definition": old["definition"]},
        headers={"If-Match": str(old["rowVersion"])},
    )
    assert r.status_code == 409 and r.json()["code"] == "IMMUTABLE_VERSION"
    with pool.connection() as conn:
        with pytest.raises(psycopg.errors.CheckViolation):
            with conn.transaction():
                conn.execute(
                    "UPDATE config_version SET definition='{}' WHERE id=%s",
                    (identifier(old["id"]),),
                )
    newdef = deepcopy(f["code"]["definition"])
    newdef["segments"][0]["value"] = "NEW" + f["prefix"]
    new = ok(
        c,
        "/code-rules",
        "POST",
        {"code": f["code"]["code"], "categoryCode": f["prefix"], "definition": newdef},
    )
    rel = ok(
        c,
        "/release-packages",
        "POST",
        {
            "code": f["release"]["code"],
            "categoryCode": f["prefix"],
            "refs": {**f["release"]["refs"], "codeRuleVersionId": new["id"]},
            "samples": f["release"]["samples"],
        },
    )
    tested = ok(
        c, "/release-packages/" + rel["id"] + "/test", "POST", {}, rel["rowVersion"]
    )
    assert tested["tests"]["passed"] and tested["tests"]["diff"]
    rel = ok(
        c,
        "/release-packages/" + rel["id"] + "/submit",
        "POST",
        {},
        tested["rowVersion"],
    )
    rel = ok(
        review,
        "/release-packages/" + rel["id"] + "/publish",
        "POST",
        {},
        rel["rowVersion"],
    )
    assert ok(c, "/release-packages/" + f["release"]["id"])["status"] == "RETIRED"
    # Existing source is pinned to old version even when code attributes change.
    replay = issue(c, f, request_body(f, note="different")).json()
    assert replay["materialNo"] == issued["materialNo"]
    newer = issue(c, f, request_body(f, source="new", name="NEW")).json()
    assert newer["materialNo"].startswith("NEW")
    assert (
        ok(c, "/material-number-assignments/" + issued["assignmentId"])[
            "configurationSnapshot"
        ]["codeRule"]["id"]
        == f["code"]["id"]
    )
    draft = ok(
        c,
        "/code-rules",
        "POST",
        {"code": f["code"]["code"], "categoryCode": f["prefix"], "definition": newdef},
    )
    assert (
        c.patch("/code-rules/" + draft["id"], json={"definition": newdef}).status_code
        == 428
    )
    assert (
        c.patch(
            "/code-rules/" + draft["id"],
            json={"definition": newdef},
            headers={"If-Match": "999"},
        ).status_code
        == 409
    )


def test_ac11_three_industries_configuration_only(clients):
    c = clients["editor"]
    prefix = uuid4().hex[:8]
    for code, expected in [
        ("GLASS_CLOTH", "GC-7628-A-210-1270-HH"),
        ("BEARING", "BR-DEEP_GROOVE-25-52-15-2RS-SKF"),
        ("RESISTOR", "EC-0603-"),
    ]:
        rel = next(
            r
            for r in ok(c, "/release-packages")
            if r["categoryCode"] == code and r["status"] == "PUBLISHED"
        )
        caller = ok(
            c,
            "/caller-systems",
            "POST",
            {
                "code": "U" + prefix + "_" + code,
                "status": "ACTIVE",
                "allowedCategoryCodes": [code],
            },
        )
        sample = rel["samples"][0]
        body = {
            "callerSystemCode": caller["code"],
            "categoryCode": code,
            "sourceRecordKey": "UAT-" + prefix,
            "attributes": sample["attributes"],
        }
        r = c.post(
            "/material-number-assignments",
            json=body,
            headers={"X-Caller-Key": caller["apiKey"], "Idempotency-Key": str(uuid4())},
        )
        assert r.status_code == 200, r.text
        assert r.json()["materialNo"].startswith(expected)
    source = "\n".join(
        p.read_text()
        for p in (ROOT / "backend/app").glob("*.py")
        if p.name != "seed.py"
    )
    assert (
        "GLASS_CLOTH" not in source
        and "BEARING" not in source
        and "RESISTOR" not in source
    )


def test_ac12_caller_contract_auth_lifecycle_and_isolation(factory, clients, server):
    f = factory()
    c = clients["editor"]
    headers = {
        "X-Tenant-Code": "TENANT-A",
        "X-Caller-Key": f["callers"][0]["apiKey"],
        "Idempotency-Key": str(uuid4()),
    }
    with httpx.Client(base_url=server, trust_env=False) as raw:
        assert (
            raw.post(
                "/material-number-assignments", json=request_body(f), headers=headers
            ).status_code
            == 200
        )
        assert raw.get("/caller-systems", headers=headers).status_code == 403
        fake = request_body(f, caller=1)
        assert (
            raw.post(
                "/material-number-assignments", json=fake, headers=headers
            ).status_code
            == 403
        )
        assert (
            raw.post(
                "/material-number-assignments",
                json=request_body(f),
                headers={**headers, "X-Tenant-Code": "TENANT-B"},
            ).status_code
            == 401
        )
    row = ok(c, "/caller-systems/" + f["callers"][0]["id"])
    assert "apiKey" not in row and "credentialHash" not in row
    paused = ok(
        c,
        "/caller-systems/" + row["id"],
        "PATCH",
        {"status": "SUSPENDED"},
        row["rowVersion"],
    )
    assert issue(c, f).json()["code"] == "CALLER_SYSTEM_SUSPENDED"
    active = ok(
        c,
        "/caller-systems/" + row["id"],
        "PATCH",
        {"status": "ACTIVE"},
        paused["rowVersion"],
    )
    rotated = ok(
        c,
        "/caller-systems/" + row["id"] + "/rotate-key",
        "POST",
        {},
        active["rowVersion"],
    )
    assert issue(c, f).status_code == 401
    f["callers"][0]["apiKey"] = rotated["apiKey"]
    assert issue(c, f).status_code == 200
    assert (
        c.post(
            "/material-number-assignments",
            json=request_body(f),
            headers={"Idempotency-Key": "manual"},
        ).status_code
        == 403
    )
    c.headers["X-Tenant-Code"] = "TENANT-B"
    assert c.get("/schemas/" + f["schema"]["id"]).status_code == 404
    assert (
        c.get("/material-number-assignments?categoryCode=" + f["prefix"]).json()[
            "total"
        ]
        == 0
    )


def test_ac13_process_death_after_sequence_atomic_recovery(factory, clients):
    f = factory()
    c = clients["editor"]
    body = request_body(f)
    payload = ROOT / "work/fault-input.json"
    payload.write_text(dumps({"body": body, "key": f["callers"][0]["apiKey"]}))
    os.chmod(payload, 0o600)
    program = """
import os
from pathlib import Path
from app.db import pool
from app.common import loads
from app.security import caller_context
from app.service import issue
pool.open();pool.wait()
p=loads(Path('work/fault-input.json').read_text())
with pool.connection() as conn:
 c=caller_context(conn,{'x-tenant-code':'TENANT-A','x-caller-key':p['key']},'crash-test',True)
 def die(stage,conn):
  if stage=='after_sequence':os._exit(73)
 issue(conn,c,p['body'],'crash-key',die)
"""
    proc = subprocess.run(
        [sys.executable, "-c", program],
        cwd=ROOT,
        env={**os.environ, "PYTHONPATH": str(ROOT / "backend")},
    )
    assert proc.returncode == 73
    with pool.connection() as conn:
        assert (
            one(
                conn,
                "SELECT count(*) AS n FROM material_assignment WHERE category_id=%s",
                (identifier(f["category"]["categoryId"]),),
            )["n"]
            == 0
        )
        assert (
            one(
                conn,
                "SELECT count(*) AS n FROM sequence_counter WHERE code_rule_version_id=%s",
                (identifier(f["code"]["id"]),),
            )["n"]
            == 0
        )
        assert (
            one(
                conn,
                "SELECT count(*) AS n FROM source_binding WHERE category_id=%s",
                (identifier(f["category"]["categoryId"]),),
            )["n"]
            == 0
        )
    retry = issue(c, f, body, "crash-key")
    assert retry.status_code == 200 and retry.json()["materialNo"].endswith("000001")
    payload.unlink()


def test_ac14_approval_separation_draft_forbidden(factory, clients):
    f = factory()
    edit = clients["editor"]
    admin = clients["admin"]
    rel = ok(
        admin,
        "/release-packages",
        "POST",
        {
            "code": f["release"]["code"],
            "categoryCode": f["prefix"],
            "refs": f["release"]["refs"],
            "samples": f["release"]["samples"],
        },
    )
    rel = ok(
        admin,
        "/release-packages/" + rel["id"] + "/submit",
        "POST",
        {},
        rel["rowVersion"],
    )
    same = admin.post(
        "/release-packages/" + rel["id"] + "/publish",
        json={},
        headers={"If-Match": str(rel["rowVersion"])},
    )
    assert same.status_code == 409 and same.json()["code"] == "SEPARATION_OF_DUTIES"
    request = request_body(f)
    request["releasePackageVersionId"] = rel["id"]
    assert issue(edit, f, request).status_code == 400
    assert clients["reader"].post("/schemas", json={"code": "bad"}).status_code == 403
    assert (
        clients["editor"]
        .post(
            "/release-packages/" + rel["id"] + "/publish",
            json={},
            headers={"If-Match": str(rel["rowVersion"])},
        )
        .status_code
        == 403
    )
