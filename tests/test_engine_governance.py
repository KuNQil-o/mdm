from copy import deepcopy
from decimal import Decimal
from uuid import uuid4
import httpx
import psycopg
import pytest
from conftest import ok, issue, request_body, ROOT
from app.common import dumps, loads, identifier, digest
from app.db import pool, one, rows, Jsonb
from app import engine


def bundle_for(f, c):
    return ok(c, "/release-packages/" + f["release"]["id"])["dependencySnapshot"]


def test_light_profile_mapping_and_protected_derivation(factory, clients):
    f = factory(
        profile={
            "fields": [
                {"source": "vendor", "target": "name", "trim": True, "case": "UPPER"}
            ]
        },
        attributes={
            "attributes": [
                {"attributeCode": "name", "type": "STRING", "required": True},
                {
                    "attributeCode": "width",
                    "type": "DECIMAL",
                    "required": True,
                    "scale": 3,
                },
                {"attributeCode": "class", "type": "STRING", "required": True},
            ]
        },
        derivation={
            "attributes": [
                {
                    "field": "class",
                    "expression": {
                        "op": "if",
                        "args": [
                            {"op": "gte", "args": [{"field": "width"}, 10]},
                            "WIDE",
                            "NORMAL",
                        ],
                    },
                }
            ]
        },
    )
    c = clients["editor"]
    body = request_body(f)
    body["attributes"] = {"vendor": " abc ", "width": "11.125"}
    r = issue(c, f, body)
    assert r.status_code == 200, r.text
    row = ok(c, "/material-number-assignments/" + r.json()["assignmentId"])
    assert (
        row["normalizedSnapshot"]["name"] == "ABC"
        and row["derivedSnapshot"]["class"] == "WIDE"
        and row["inputProfileVersionId"]
    )
    body["attributes"]["class"] = "OVERRIDE"
    bad = issue(c, f, body)
    assert (
        bad.status_code == 422
        and bad.json()["details"][0]["code"] == "DERIVED_READONLY"
    )
    body["attributes"].pop("class")
    body["attributes"]["name"] = "SHADOW"
    assert issue(c, f, body).json()["code"] == "INPUT_MAPPING_ERROR"


def test_dag_cycle_invalid_dependency_and_script_rejected(factory, clients):
    f = factory()
    c = clients["editor"]
    b = bundle_for(f, c)
    b["schema"]["definition"]["attributes"] += [
        {"attributeCode": "x", "type": "STRING"},
        {"attributeCode": "y", "type": "STRING"},
    ]
    b["derivations"] = [
        {
            "id": str(uuid4()),
            "definition": {
                "attributes": [
                    {"field": "x", "expression": {"field": "y"}},
                    {"field": "y", "expression": {"field": "x"}},
                ]
            },
        }
    ]
    with pytest.raises(Exception, match="循环依赖"):
        engine.compile_bundle(b)
    b["derivations"][0]["definition"]["attributes"] = [
        {"field": "x", "expression": {"field": "notPresent"}}
    ]
    with pytest.raises(Exception, match="未知属性"):
        engine.compile_bundle(b)
    r = c.post(
        "/input-profiles",
        json={
            "code": "S" + uuid4().hex,
            "categoryCode": f["prefix"],
            "definition": {"sql": "select * from erp"},
        },
    )
    assert r.status_code == 422 and r.json()["code"] == "CONFIGURATION_ERROR"


def test_validation_boolean_ast_warning_and_error_detail(factory, clients):
    f = factory(
        validation={
            "rules": [
                {
                    "field": "width",
                    "assert": {
                        "op": "and",
                        "args": [
                            {"op": "exists", "args": [{"field": "width"}]},
                            {
                                "op": "not_in",
                                "args": [{"field": "name"}, {"literal": ["BLOCKED"]}],
                            },
                            {"op": "gte", "args": [{"field": "width"}, 1]},
                        ],
                    },
                    "message": "必须有幅宽且名称不能为BLOCKED",
                    "severity": "ERROR",
                },
                {
                    "field": "width",
                    "assert": {"op": "lt", "args": [{"field": "width"}, 100]},
                    "message": "大幅宽请确认",
                    "severity": "WARNING",
                },
            ]
        }
    )
    c = clients["editor"]
    r = issue(c, f, request_body(f, width=200))
    assert r.status_code == 200
    row = ok(c, "/material-number-assignments/" + r.json()["assignmentId"])
    warnings = [x for x in row["validationSnapshot"] if not x["passed"]]
    assert len(warnings) == 1 and warnings[0]["severity"] == "WARNING"
    r = issue(c, f, request_body(f, source="bad", name="BLOCKED", width=12))
    assert r.status_code == 422 and r.json()["code"] == "VALIDATION_ERROR"
    detail = r.json()["details"][0]
    assert set(
        [
            "fieldPath",
            "errorCode",
            "sourceValue",
            "normalizedValue",
            "ruleVersion",
            "suggestion",
        ]
    ) <= set(detail)


def test_types_defaults_enum_reference_dictionary_and_exact_precision(factory, clients):
    f = factory()
    c = clients["editor"]
    bundle = bundle_for(f, c)
    attrs = bundle["schema"]["definition"]["attributes"]
    attrs.extend(
        [
            {"attributeCode": "flag", "type": "BOOLEAN", "default": True},
            {"attributeCode": "day", "type": "DATE"},
            {"attributeCode": "integer", "type": "INTEGER", "scale": 0},
            {"attributeCode": "amount", "type": "DECIMAL", "precision": 38, "scale": 9},
            {
                "attributeCode": "vendor",
                "type": "REFERENCE",
                "referenceType": "VENDOR",
                "options": [
                    {"code": "V01", "name": "Company A", "aliases": ["aliasA"]}
                ],
            },
            {
                "attributeCode": "enum",
                "type": "ENUM",
                "case": "UPPER",
                "options": [{"code": "E01", "aliases": ["ONE"]}],
            },
        ]
    )
    result = engine.prepare(
        bundle,
        "none",
        {
            "name": "ABC",
            "integer": "5",
            "amount": "12345678901234567890123456789.123456789",
            "day": "2026-10-07",
            "vendor": {"type": "VENDOR", "id": "aliasA"},
            "enum": " one ",
        },
    )
    assert result["normalizedAttributes"]["flag"] is True
    assert (
        result["normalizedAttributes"]["vendor"] == "V01"
        and result["normalizedAttributes"]["enum"] == "E01"
    )
    assert result["normalizedAttributes"]["amount"] == Decimal(
        "12345678901234567890123456789.123456789"
    )
    for value, field in [
        ("2026-02-30", "day"),
        ("5.5", "integer"),
        ("INACTIVE", "enum"),
    ]:
        with pytest.raises(Exception):
            engine.prepare(bundle, "none", {"name": "ABC", field: value})
    a = engine.prepare(
        bundle, "none", {"name": "ABC", "width": {"value": "1", "unit": "CM"}}
    )
    assert a["normalizedAttributes"]["width"] == 10
    with pytest.raises(Exception):
        engine.prepare(
            bundle, "none", {"name": "ABC", "width": {"value": "1", "unit": "KG"}}
        )


def test_period_scopes_segment_padding_format_and_preview():
    from datetime import datetime, timezone

    bundle = {
        "category": {"id": "c", "definition": {}},
        "schema": {
            "id": "s",
            "definition": {
                "attributes": [{"attributeCode": "x", "type": "DECIMAL", "unit": "MM"}],
                "units": {
                    "MM": {"dimension": "LENGTH", "factor": 1},
                    "CM": {"dimension": "LENGTH", "factor": 10},
                },
            },
        },
        "identity": {"id": "i", "definition": {"attributes": ["x"]}},
        "codeRule": {
            "id": "r",
            "definition": {
                "separator": "",
                "segments": [
                    {"type": "CONSTANT", "value": "M"},
                    {"type": "UNIT_FORMAT", "field": "x", "unit": "CM", "scale": 1},
                    {"type": "SEPARATOR", "value": "-"},
                    {"type": "PERIOD", "reset": "MONTHLY", "timezone": "Asia/Shanghai"},
                    {
                        "type": "SEQUENCE",
                        "reset": "MONTHLY",
                        "timezone": "Asia/Shanghai",
                        "width": 3,
                    },
                ],
            },
        },
    }
    fixed = datetime(2026, 9, 30, 18, tzinfo=timezone.utc)
    preview = engine.render(bundle, {"x": Decimal(12)}, now=fixed)
    assert preview["materialNo"] == "M1.2-202610{SEQUENCE:MAIN:3}"
    used = []
    actual = engine.render(
        bundle,
        {"x": Decimal(12)},
        lambda name, period: used.append((name, period)) or 8,
        now=fixed,
    )
    assert actual["materialNo"] == "M1.2-202610008" and used == [("MAIN", "202610")]


def test_long_identity_full_comparison_and_delimiter_safety(factory, clients):
    f = factory()
    c = clients["editor"]
    value = "长" + ("X" * 7000) + "|name=TRAP"
    a = issue(c, f, request_body(f, name=value))
    assert a.status_code == 200, a.text
    b = issue(c, f, request_body(f, source="second", name=value))
    assert b.status_code == 200 and b.json()["reused"]
    distinct = issue(c, f, request_body(f, source="third", name=value + "2"))
    assert (
        distinct.status_code == 200
        and distinct.json()["assignmentId"] != a.json()["assignmentId"]
    )
    row = ok(c, "/material-number-assignments/" + a.json()["assignmentId"])
    assert len(row["identityCanonical"]) > 7000 and row["identityHash"] == digest(
        row["identityCanonical"]
    )


def test_code_conflict_rolls_back_source_and_idempotency(factory, clients):
    f = factory(
        sequence=False, segments=[{"type": "CONSTANT", "value": "C" + uuid4().hex}]
    )
    c = clients["editor"]
    assert issue(c, f).status_code == 200
    b = request_body(f, source="conflict", name="DIFFERENT")
    r = issue(c, f, b, "code-conflict-key")
    assert r.status_code == 409 and r.json()["code"] == "CODE_CONFLICT"
    with pool.connection() as conn:
        assert (
            one(
                conn,
                "SELECT count(*) AS n FROM source_binding WHERE category_id=%s AND source_record_key='conflict'",
                (identifier(f["category"]["categoryId"]),),
            )["n"]
            == 0
        )
        assert (
            one(
                conn,
                "SELECT count(*) AS n FROM assignment_idempotency WHERE caller_system_id=%s AND idempotency_key='code-conflict-key'",
                (identifier(f["callers"][0]["id"]),),
            )["n"]
            == 0
        )


def test_database_immutable_facts_and_sequence_rewind(factory, clients):
    f = factory()
    r = issue(clients["editor"], f).json()
    with pool.connection() as conn:
        for sql, params in [
            (
                "DELETE FROM material_assignment WHERE id=%s",
                (identifier(r["assignmentId"]),),
            ),
            (
                "UPDATE material_assignment SET material_no='OTHER' WHERE id=%s",
                (identifier(r["assignmentId"]),),
            ),
            (
                "DELETE FROM source_binding WHERE assignment_id=%s",
                (identifier(r["assignmentId"]),),
            ),
            (
                "UPDATE sequence_counter SET last_value=0 WHERE code_rule_version_id=%s",
                (identifier(f["code"]["id"]),),
            ),
            (
                "DELETE FROM sequence_counter WHERE code_rule_version_id=%s",
                (identifier(f["code"]["id"]),),
            ),
        ]:
            with pytest.raises(psycopg.errors.CheckViolation):
                with conn.transaction():
                    conn.execute(sql, params)


def test_failed_release_samples_cannot_publish_and_review_freeze(factory, clients):
    f = factory()
    c = clients["editor"]
    rel = ok(
        c,
        "/release-packages",
        "POST",
        {
            "code": f["release"]["code"],
            "categoryCode": f["prefix"],
            "refs": f["release"]["refs"],
            "samples": [{"attributes": {"name": "ABC"}, "expectedMaterialNo": "WRONG"}],
        },
    )
    test = ok(
        c, "/release-packages/" + rel["id"] + "/test", "POST", {}, rel["rowVersion"]
    )
    assert not test["tests"]["passed"]
    r = c.post(
        "/release-packages/" + rel["id"] + "/submit",
        json={},
        headers={"If-Match": str(test["rowVersion"])},
    )
    assert r.status_code == 422
    new = ok(
        c,
        "/schemas",
        "POST",
        {
            "code": f["schema"]["code"],
            "categoryCode": f["prefix"],
            "definition": f["schema"]["definition"],
        },
    )
    rel = ok(
        c,
        "/release-packages",
        "POST",
        {
            "code": f["release"]["code"],
            "categoryCode": f["prefix"],
            "refs": {**f["release"]["refs"], "schemaVersionId": new["id"]},
            "samples": f["release"]["samples"],
        },
    )
    rel = ok(
        c, "/release-packages/" + rel["id"] + "/submit", "POST", {}, rel["rowVersion"]
    )
    frozen = ok(c, "/schemas/" + new["id"])
    assert (
        c.patch(
            "/schemas/" + new["id"],
            json={"definition": new["definition"]},
            headers={"If-Match": str(frozen["rowVersion"])},
        ).status_code
        == 409
    )
    rel = ok(
        clients["reviewer"],
        "/release-packages/" + rel["id"] + "/reject",
        "POST",
        {},
        rel["rowVersion"],
    )
    assert (
        rel["status"] == "DRAFT" and ok(c, "/schemas/" + new["id"])["status"] == "DRAFT"
    )


def test_duplicate_identity_and_code_samples_detected(factory, clients):
    f = factory()
    c = clients["editor"]
    sample = {"attributes": {"name": "ABC"}}
    rel = ok(
        c,
        "/release-packages",
        "POST",
        {
            "code": f["release"]["code"],
            "categoryCode": f["prefix"],
            "refs": f["release"]["refs"],
            "samples": [sample, sample],
        },
    )
    tests = ok(
        c, "/release-packages/" + rel["id"] + "/test", "POST", {}, rel["rowVersion"]
    )["tests"]
    assert not tests["passed"] and tests["samples"][1]["code"] == "IDENTITY_CONFLICT"


def test_rate_limit_persists_and_invalid_methods(factory, clients):
    f = factory()
    c = clients["editor"]
    caller = ok(c, "/caller-systems/" + f["callers"][0]["id"])
    ok(
        c,
        "/caller-systems/" + caller["id"],
        "PATCH",
        {"rateLimit": 1},
        caller["rowVersion"],
    )
    assert issue(c, f).status_code == 200
    assert issue(c, f).status_code == 429
    assert c.delete("/material-number-assignments/" + str(uuid4())).status_code == 405
    assert c.get("/source-datasets").status_code == 404
    assert c.post("/writeback-tasks", json={}).status_code == 404
    assert (
        c.post(
            "/material-number-previews",
            content="[]",
            headers={"Content-Type": "application/json"},
        ).status_code
        == 400
    )


def test_security_audit_redaction_scope_and_monitoring(factory, clients):
    f = factory()
    c = clients["editor"]
    r = issue(c, f).json()
    assert clients["reader"].get("/audit-logs").status_code == 403
    assert clients["auditor"].get("/audit-logs").status_code == 200
    audit = ok(clients["auditor"], "/audit-logs")
    serialized = json_text = dumps(audit)
    assert (
        f["callers"][0]["apiKey"] not in serialized
        and "credentialHash" not in serialized
    )
    uid = ok(clients["admin"], "/users")
    reader = next(u for u in uid if u["code"] == "reader")
    membership = next(
        m for m in ok(clients["admin"], "/members") if m["userId"] == reader["id"]
    )
    # A scoped role is applied then restored; server evaluates it on every request.
    limited = ok(
        clients["admin"],
        "/members/" + reader["id"],
        "PATCH",
        {"categoryScope": ["RESISTOR"]},
        membership["rowVersion"],
    )
    try:
        assert (
            clients["reader"]
            .get("/material-number-assignments/" + r["assignmentId"])
            .status_code
            == 403
        )
        assert (
            ok(
                clients["reader"],
                "/material-number-assignments?categoryCode=" + f["prefix"],
            )["total"]
            == 0
        )
        assert not any(
            x["categoryCode"] == f["prefix"]
            for x in ok(clients["reader"], "/categories")
        )
    finally:
        ok(
            clients["admin"],
            "/members/" + reader["id"],
            "PATCH",
            {"categoryScope": ["*"]},
            limited["rowVersion"],
        )
    monitoring = ok(c, "/monitoring")
    assert monitoring["database"]["healthy"] and monitoring["summary"]["requests"] > 0
    assert monitoring["summary"]["p95Ms"] >= 0


def test_tenant_pause_preview_allowed_formal_blocked(factory, clients):
    f = factory()
    c = clients["editor"]
    admin = clients["admin"]
    t = next(x for x in ok(admin, "/tenants") if x["code"] == "TENANT-A")
    paused = ok(
        admin, "/tenants/" + t["id"], "PATCH", {"status": "SUSPENDED"}, t["rowVersion"]
    )
    try:
        r = issue(c, f)
        assert r.status_code == 409 and r.json()["code"] == "TENANT_SUSPENDED"
        body = request_body(f)
        body.pop("sourceRecordKey")
        assert c.post("/material-number-previews", json=body).status_code == 200
    finally:
        ok(
            admin,
            "/tenants/" + t["id"],
            "PATCH",
            {"status": "ACTIVE"},
            paused["rowVersion"],
        )


def test_draft_preview_permissions_and_no_published_release(factory, clients):
    f = factory()
    c = clients["editor"]
    rel = ok(
        c,
        "/release-packages",
        "POST",
        {
            "code": f["release"]["code"],
            "categoryCode": f["prefix"],
            "refs": f["release"]["refs"],
            "samples": f["release"]["samples"],
        },
    )
    body = request_body(f)
    body.pop("sourceRecordKey")
    body["releasePackageVersionId"] = rel["id"]
    assert c.post("/material-number-previews", json=body).status_code == 200
    assert (
        clients["reader"].post("/material-number-previews", json=body).status_code
        == 403
    )
    retired = ok(
        clients["reviewer"],
        "/release-packages/" + f["release"]["id"] + "/retire",
        "POST",
        {},
        f["release"]["rowVersion"],
    )
    assert issue(c, f).json()["code"] == "NO_PUBLISHED_RELEASE"


def test_signed_requests_tamper_and_nonce_replay(factory, clients):
    import hmac, hashlib, time

    f = factory()
    c = clients["editor"]
    body = request_body(f)
    raw = dumps(body)
    timestamp = str(int(time.time()))
    nonce = uuid4().hex
    message = "\n".join(
        [timestamp, nonce, "POST", "/api/v1/material-number-assignments", digest(raw)]
    )
    signature = hmac.new(
        f["callers"][0]["apiKey"].encode(), message.encode(), hashlib.sha256
    ).hexdigest()
    headers = {
        "Content-Type": "application/json",
        "X-Caller-Key": f["callers"][0]["apiKey"],
        "Idempotency-Key": "signed-request",
        "X-Timestamp": timestamp,
        "X-Nonce": nonce,
        "X-Signature": signature,
    }
    invalid = c.post("/material-number-assignments", content=raw + " ", headers=headers)
    assert invalid.status_code == 401 and invalid.json()["code"] == "SIGNATURE_INVALID"
    issued = c.post("/material-number-assignments", content=raw, headers=headers)
    assert issued.status_code == 200
    replay = c.post("/material-number-assignments", content=raw, headers=headers)
    assert replay.status_code == 409 and replay.json()["code"] == "REPLAY_REJECTED"
    newnonce = uuid4().hex
    newmessage = "\n".join(
        [
            timestamp,
            newnonce,
            "POST",
            "/api/v1/material-number-assignments",
            digest(raw),
        ]
    )
    headers["X-Nonce"] = newnonce
    headers["X-Signature"] = hmac.new(
        f["callers"][0]["apiKey"].encode(), newmessage.encode(), hashlib.sha256
    ).hexdigest()
    retried = c.post("/material-number-assignments", content=raw, headers=headers)
    assert (
        retried.status_code == 200
        and retried.json()["assignmentId"] == issued.json()["assignmentId"]
        and retried.json()["reused"]
    )


def test_cross_site_write_denied_and_contract(clients):
    c = clients["editor"]
    r = c.post(
        "/categories",
        json={"code": "EVIL"},
        headers={"Origin": "https://evil.example", "Sec-Fetch-Site": "cross-site"},
    )
    assert r.status_code == 403 and r.json()["code"] == "ORIGIN_FORBIDDEN"
    root = c.base_url.copy_with(path="/api/openapi.json")
    spec = c.get(str(root)).json()
    assert (
        spec["openapi"] == "3.0.3" and "/material-number-assignments" in spec["paths"]
    )
    assert (
        spec["components"]["schemas"]["AssignmentRequest"]["additionalProperties"]
        is False
    )


def test_tenants_allow_identical_category_identity_and_material_no(factory, clients):
    f = factory(sequence=False)
    c = clients["editor"]
    review = clients["reviewer"]
    a = issue(c, f).json()
    for client in clients.values():
        client.headers["X-Tenant-Code"] = "TENANT-B"
    try:
        cat = ok(
            c,
            "/categories",
            "POST",
            {"code": f["prefix"], "definition": f["category"]["definition"]},
        )
        refs = {"categoryVersionId": cat["id"]}
        for res, key, item in [
            ("schemas", "schemaVersionId", f["schema"]),
            ("identity-definitions", "identityDefinitionVersionId", f["identity"]),
            ("code-rules", "codeRuleVersionId", f["code"]),
        ]:
            cfg = ok(
                c,
                "/" + res,
                "POST",
                {
                    "code": item["code"],
                    "categoryCode": f["prefix"],
                    "definition": item["definition"],
                },
            )
            refs[key] = cfg["id"]
        caller = ok(
            c,
            "/caller-systems",
            "POST",
            {
                "code": f["callers"][0]["code"],
                "status": "ACTIVE",
                "allowedCategoryCodes": [f["prefix"]],
            },
        )
        rel = ok(
            c,
            "/release-packages",
            "POST",
            {
                "code": f["release"]["code"],
                "categoryCode": f["prefix"],
                "refs": refs,
                "samples": f["release"]["samples"],
            },
        )
        rel = ok(
            c,
            "/release-packages/" + rel["id"] + "/submit",
            "POST",
            {},
            rel["rowVersion"],
        )
        ok(
            review,
            "/release-packages/" + rel["id"] + "/publish",
            "POST",
            {},
            rel["rowVersion"],
        )
        copied = {**f, "callers": [caller]}
        b = issue(c, copied).json()
        assert (
            a["materialNo"] == b["materialNo"]
            and a["assignmentId"] != b["assignmentId"]
        )
        assert (
            c.get("/material-number-assignments/" + a["assignmentId"]).status_code
            == 404
        )
    finally:
        for client in clients.values():
            client.headers["X-Tenant-Code"] = "TENANT-A"


def test_versioned_dictionary_normalization_code_snapshot(factory, clients):
    f = factory()
    c = clients["editor"]
    review = clients["reviewer"]
    dictionary = ok(
        c,
        "/reference-data",
        "POST",
        {
            "code": f["prefix"] + "_DICT",
            "categoryCode": f["prefix"],
            "definition": {
                "entries": [
                    {"code": "SAMPLE", "aliases": ["sample_alias"], "numberCode": "SA"},
                    {"code": "OTHER", "numberCode": "OT"},
                ]
            },
        },
    )
    schema_def = deepcopy(f["schema"]["definition"])
    field = schema_def["attributes"][0]
    field["type"] = "ENUM"
    field["enumDictionaryVersionId"] = dictionary["id"]
    schema = ok(
        c,
        "/schemas",
        "POST",
        {
            "code": f["schema"]["code"],
            "categoryCode": f["prefix"],
            "definition": schema_def,
        },
    )
    rule_def = {
        "separator": "-",
        "segments": [
            {"type": "CONSTANT", "value": f["prefix"]},
            {
                "type": "DICTIONARY",
                "field": "name",
                "dictionaryVersionId": dictionary["id"],
            },
        ],
    }
    rule = ok(
        c,
        "/code-rules",
        "POST",
        {
            "code": f["code"]["code"],
            "categoryCode": f["prefix"],
            "definition": rule_def,
        },
    )
    refs = {
        **f["release"]["refs"],
        "schemaVersionId": schema["id"],
        "codeRuleVersionId": rule["id"],
        "dictionaryVersionIds": [dictionary["id"]],
    }
    rel = ok(
        c,
        "/release-packages",
        "POST",
        {
            "code": f["release"]["code"],
            "categoryCode": f["prefix"],
            "refs": refs,
            "samples": f["release"]["samples"],
        },
    )
    rel = ok(
        c, "/release-packages/" + rel["id"] + "/submit", "POST", {}, rel["rowVersion"]
    )
    ok(
        review,
        "/release-packages/" + rel["id"] + "/publish",
        "POST",
        {},
        rel["rowVersion"],
    )
    issued = issue(c, f, request_body(f, name=" sample_alias "))
    assert issued.status_code == 200, issued.text
    assert issued.json()["materialNo"] == f["prefix"] + "-SA"
    old = ok(c, "/reference-data/" + dictionary["id"])
    assert (
        c.patch(
            "/reference-data/" + old["id"],
            json={
                "definition": {"entries": [{"code": "SAMPLE", "numberCode": "CHANGED"}]}
            },
            headers={"If-Match": str(old["rowVersion"])},
        ).status_code
        == 409
    )
    newdef = {"entries": [{"code": "SAMPLE", "numberCode": "CHANGED"}]}
    ok(
        c,
        "/reference-data",
        "POST",
        {"code": old["code"], "categoryCode": f["prefix"], "definition": newdef},
    )
    preview = c.post(
        "/material-number-previews",
        json={
            k: v
            for k, v in request_body(f, name="SAMPLE").items()
            if k != "sourceRecordKey"
        },
    )
    assert (
        preview.status_code == 200
        and preview.json()["materialNo"] == f["prefix"] + "-SA"
    )
    explanation = ok(c, "/material-number-assignments/" + issued.json()["assignmentId"])
    assert (
        explanation["configurationSnapshot"]["dictionaries"][dictionary["id"]][
            "definition"
        ]["entries"][0]["numberCode"]
        == "SA"
    )
    # Case folding cannot make different dictionary codes silently indistinguishable.
    invalid = bundle_for({**f, "release": rel}, c)
    invalid["dictionaries"][dictionary["id"]]["definition"]["entries"].append(
        {"code": "DIFFERENT", "aliases": ["SAMPLE_ALIAS"]}
    )
    with pytest.raises(Exception, match="歧义"):
        engine.compile_bundle(invalid)
