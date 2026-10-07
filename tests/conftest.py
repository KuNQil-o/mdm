import os
import sys
import time
import subprocess
from pathlib import Path
from uuid import uuid4
import pytest
import httpx
import psycopg

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "backend"))
BASE_DB = os.getenv(
    "TEST_DATABASE_URL", "postgresql://mdm:mdm_v4_dev_only@localhost:5433/mdm_v4_test"
)
os.environ["DATABASE_URL"] = BASE_DB
os.environ["DEV_CREDENTIAL_FILE"] = str(ROOT / ".runtime/test-callers.json")
from app.db import pool, migrate, one, rows
from app.seed import seed


@pytest.fixture(scope="session", autouse=True)
def database():
    if not os.getenv("TEST_DATABASE_URL"):
        with psycopg.connect(
            "postgresql://mdm:mdm_v4_dev_only@localhost:5433/postgres", autocommit=True
        ) as conn:
            if not conn.execute(
                "SELECT 1 FROM pg_database WHERE datname='mdm_v4_test'"
            ).fetchone():
                conn.execute("CREATE DATABASE mdm_v4_test")
    pool.open()
    pool.wait()
    migrate()
    seed()
    yield
    pool.close()


@pytest.fixture(scope="session")
def server(database):
    (ROOT / ".runtime").mkdir(exist_ok=True)
    stream = open(ROOT / ".runtime/test-api.log", "w")
    proc = subprocess.Popen(
        [
            sys.executable,
            "-m",
            "uvicorn",
            "app.api:app",
            "--app-dir",
            str(ROOT / "backend"),
            "--port",
            "8081",
            "--no-access-log",
        ],
        env=os.environ.copy(),
        stdout=stream,
        stderr=stream,
    )
    base = "http://127.0.0.1:8081/api/v1"
    for _ in range(80):
        try:
            if httpx.get(base + "/health", trust_env=False).status_code == 200:
                break
        except httpx.HTTPError:
            pass
        if proc.poll() is not None:
            raise RuntimeError((ROOT / ".runtime/test-api.log").read_text())
        time.sleep(0.1)
    else:
        raise RuntimeError("Test API startup timeout")
    yield base
    proc.terminate()
    proc.wait(timeout=10)
    stream.close()


@pytest.fixture
def clients(server):
    result = {}
    for user in ["editor", "reviewer", "admin", "reader", "auditor"]:
        client = httpx.Client(base_url=server, trust_env=False, timeout=60)
        assert client.post("/dev/login", json={"userCode": user}).status_code == 200
        client.headers["X-Tenant-Code"] = "TENANT-A"
        result[user] = client
    yield result
    for client in result.values():
        client.close()


def ok(client, path, method="GET", body=None, version=None, headers=None):
    h = headers or {}
    if version is not None:
        h["If-Match"] = '"' + str(version) + '"'
    r = client.request(method, path, json=body, headers=h)
    assert r.status_code < 300, (path, r.status_code, r.text)
    return r.json()


@pytest.fixture
def factory(clients):
    def create(
        sequence=True,
        policy="REUSE_EXISTING",
        attributes=None,
        identity=None,
        segments=None,
        validation=None,
        derivation=None,
        dictionary=None,
        profile=None,
    ):
        edit = clients["editor"]
        review = clients["reviewer"]
        prefix = "T" + uuid4().hex[:12].upper()
        cat = ok(
            edit,
            "/categories",
            "POST",
            {
                "code": prefix,
                "definition": {"name": prefix, "identityReusePolicy": policy},
            },
        )
        schema = ok(
            edit,
            "/schemas",
            "POST",
            {
                "code": prefix + "_SCHEMA",
                "categoryCode": prefix,
                "definition": attributes
                or {
                    "attributes": [
                        {
                            "attributeCode": "name",
                            "type": "STRING",
                            "required": True,
                            "trim": True,
                            "case": "UPPER",
                            "length": 10000,
                        },
                        {
                            "attributeCode": "width",
                            "type": "DECIMAL",
                            "scale": 3,
                            "min": 0,
                            "max": 10000,
                            "unit": "MM",
                        },
                        {"attributeCode": "note", "type": "STRING"},
                    ],
                    "units": {
                        "MM": {"dimension": "LENGTH", "factor": 1},
                        "CM": {"dimension": "LENGTH", "factor": 10},
                    },
                },
            },
        )
        ident = ok(
            edit,
            "/identity-definitions",
            "POST",
            {
                "code": prefix + "_ID",
                "categoryCode": prefix,
                "definition": identity or {"attributes": ["name"]},
            },
        )
        code = ok(
            edit,
            "/code-rules",
            "POST",
            {
                "code": prefix + "_CODE",
                "categoryCode": prefix,
                "definition": {
                    "separator": "-",
                    "segments": segments
                    or (
                        [
                            {"type": "CONSTANT", "value": prefix},
                            {"type": "SEQUENCE", "width": 6},
                        ]
                        if sequence
                        else [
                            {"type": "CONSTANT", "value": prefix},
                            {"type": "ATTRIBUTE", "field": "name"},
                        ]
                    ),
                },
            },
        )
        refs = {
            "categoryVersionId": cat["id"],
            "schemaVersionId": schema["id"],
            "identityDefinitionVersionId": ident["id"],
            "codeRuleVersionId": code["id"],
        }
        callers = []
        for n in ["A", "B"]:
            callers.append(
                ok(
                    edit,
                    "/caller-systems",
                    "POST",
                    {
                        "code": prefix + "_CALLER_" + n,
                        "name": "Test " + n,
                        "status": "ACTIVE",
                        "allowedCategoryCodes": [prefix],
                    },
                )
            )
        for res, key, definition in [
            ("validation-rules", "validationRuleVersionIds", validation),
            ("derivation-rules", "derivationRuleVersionIds", derivation),
            ("reference-data", "dictionaryVersionIds", dictionary),
            ("input-profiles", "inputProfileVersionIds", profile),
        ]:
            if definition:
                if res == "input-profiles":
                    definition = {**definition, "callerSystemCode": callers[0]["code"]}
                item = ok(
                    edit,
                    "/" + res,
                    "POST",
                    {
                        "code": prefix + "_" + res.replace("-", "_"),
                        "categoryCode": prefix,
                        "definition": definition,
                    },
                )
                refs[key] = [item["id"]]
        samples = [
            {
                "callerSystemCode": callers[0]["code"],
                "attributes": {"name": "SAMPLE", "width": 10},
            }
        ]
        if profile:
            samples[0]["attributes"] = {"vendor": "SAMPLE", "width": 10}
        rel = ok(
            edit,
            "/release-packages",
            "POST",
            {
                "code": prefix + "_RELEASE",
                "categoryCode": prefix,
                "refs": refs,
                "samples": samples,
            },
        )
        rel = ok(
            edit,
            "/release-packages/" + rel["id"] + "/submit",
            "POST",
            {},
            rel["rowVersion"],
        )
        rel = ok(
            review,
            "/release-packages/" + rel["id"] + "/publish",
            "POST",
            {},
            rel["rowVersion"],
        )
        return {
            "category": cat,
            "schema": schema,
            "identity": ident,
            "code": code,
            "release": rel,
            "callers": callers,
            "prefix": prefix,
        }

    return create


def request_body(f, source="record-1", name="ABC", caller=0, **attrs):
    return {
        "callerSystemCode": f["callers"][caller]["code"],
        "categoryCode": f["prefix"],
        "sourceRecordKey": source,
        "attributes": {"name": name, **attrs},
    }


def issue(client, f, body=None, key=None, caller=0):
    return client.post(
        "/material-number-assignments",
        json=body or request_body(f, caller=caller),
        headers={
            "X-Caller-Key": f["callers"][caller]["apiKey"],
            "Idempotency-Key": key or str(uuid4()),
        },
    )
