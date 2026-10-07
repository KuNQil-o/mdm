"""Development fixtures only; every category is loaded as configuration JSON."""

import os
from pathlib import Path
from uuid import uuid4
from .common import loads, dumps, password_hash
from .db import pool, one, Jsonb, lock
from .security import Context, PERMISSIONS, install_roles
from . import service as s


def seed():
    if os.getenv("APP_ENV", "development") != "development":
        return
    with pool.connection() as conn:
        lock(conn, "v4-dev-seed")
        for code, name in [
            ("admin", "平台管理员"),
            ("editor", "规则设计员"),
            ("reviewer", "规则审批员"),
            ("reader", "业务查询员"),
            ("auditor", "审计员"),
        ]:
            if not one(conn, "SELECT id FROM app_user WHERE code=%s", (code,)):
                conn.execute(
                    "INSERT INTO app_user(id,code,name,password_hash,platform_admin) VALUES(%s,%s,%s,%s,%s)",
                    (
                        uuid4(),
                        code,
                        name,
                        password_hash("dev-only-change-me"),
                        code == "admin",
                    ),
                )
        for tcode in ["TENANT-A", "TENANT-B"]:
            if not one(conn, "SELECT id FROM tenant WHERE code=%s", (tcode,)):
                conn.execute(
                    "INSERT INTO tenant(id,code,name,status) VALUES(%s,%s,%s,'ACTIVE')",
                    (uuid4(), tcode, "演示企业 " + tcode[-1]),
                )
            tid = one(conn, "SELECT id FROM tenant WHERE code=%s", (tcode,))["id"]
            install_roles(conn, tid)
            for user, roles in [
                ("admin", ["ADMIN"]),
                ("editor", ["DESIGNER", "INTEGRATOR"]),
                ("reviewer", ["APPROVER", "INTEGRATOR"]),
                ("reader", ["READER"]),
                ("auditor", ["AUDITOR"]),
            ]:
                uid = one(conn, "SELECT id FROM app_user WHERE code=%s", (user,))["id"]
                conn.execute(
                    "INSERT INTO membership(tenant_id,user_id,roles) VALUES(%s,%s,%s) ON CONFLICT DO NOTHING",
                    (tid, uid, Jsonb(roles)),
                )
        tid = one(conn, "SELECT id FROM tenant WHERE code='TENANT-A'")["id"]
        editor = one(conn, "SELECT id FROM app_user WHERE code='editor'")["id"]
        reviewer = one(conn, "SELECT id FROM app_user WHERE code='reviewer'")["id"]
        edit = Context(
            tid, str(editor), PERMISSIONS - {"RELEASE_PUBLISH"}, {"*"}, str(uuid4())
        )
        review = Context(
            tid, str(reviewer), {"PREVIEW", "RELEASE_PUBLISH"}, {"*"}, str(uuid4())
        )
        demos = [
            loads(p.read_text())
            for p in sorted(
                (Path(__file__).resolve().parents[1] / "demo").glob("*.json")
            )
        ]
        created = {}
        for demo in demos:
            if not one(
                conn,
                "SELECT id FROM category WHERE tenant_id=%s AND code=%s",
                (tid, demo["categoryCode"]),
            ):
                created[demo["categoryCode"]] = s.save_config(
                    conn,
                    edit,
                    "categories",
                    {
                        "code": demo["categoryCode"],
                        "definition": {
                            "name": demo["name"],
                            "identityReusePolicy": "REUSE_EXISTING",
                            "numberingPolicy": "NUMBER_ONCE",
                        },
                    },
                )
        keys = {}
        for caller_code in ["ERP_DEMO", "PLM_DEMO"]:
            if not one(
                conn,
                "SELECT id FROM caller_system WHERE tenant_id=%s AND code=%s",
                (tid, caller_code),
            ):
                caller = s.save_caller(
                    conn,
                    edit,
                    {
                        "code": caller_code,
                        "name": "ERP 演示调用方"
                        if caller_code == "ERP_DEMO"
                        else "PLM 演示调用方",
                        "systemType": "ERP" if caller_code == "ERP_DEMO" else "PLM",
                        "status": "ACTIVE",
                        "allowedCategoryCodes": [d["categoryCode"] for d in demos],
                    },
                )
                keys[caller_code] = caller["apiKey"]
        for demo in demos:
            code = demo["categoryCode"]
            if code not in created:
                continue
            refs = {"categoryVersionId": str(created[code]["id"])}
            for resource, key, definition in [
                ("schemas", "schemaVersionId", demo["schema"]),
                (
                    "identity-definitions",
                    "identityDefinitionVersionId",
                    demo["identity"],
                ),
                ("code-rules", "codeRuleVersionId", demo["codeRule"]),
            ]:
                cfg = s.save_config(
                    conn,
                    edit,
                    resource,
                    {
                        "code": code + "_" + resource.replace("-", "_").upper(),
                        "categoryCode": code,
                        "definition": definition,
                    },
                )
                refs[key] = str(cfg["id"])
            for resource, key, name in [
                ("validation-rules", "validationRuleVersionIds", "validation"),
                ("derivation-rules", "derivationRuleVersionIds", "derivation"),
            ]:
                if name in demo:
                    cfg = s.save_config(
                        conn,
                        edit,
                        resource,
                        {
                            "code": code + "_" + name.upper(),
                            "categoryCode": code,
                            "definition": demo[name],
                        },
                    )
                    refs[key] = [str(cfg["id"])]
            rel = s.save_release(
                conn,
                edit,
                {
                    "code": code + "_RELEASE",
                    "categoryCode": code,
                    "refs": refs,
                    "samples": demo["samples"],
                },
            )
            rel = s.release_action(conn, edit, rel["id"], "submit", rel["rowVersion"])
            s.release_action(conn, review, rel["id"], "publish", rel["rowVersion"])
    if keys:
        path = Path(
            os.getenv(
                "DEV_CREDENTIAL_FILE",
                str(Path(__file__).resolve().parents[2] / ".runtime/dev-callers.json"),
            )
        )
        path.parent.mkdir(parents=True, exist_ok=True)
        existing = loads(path.read_text()) if path.exists() else {}
        existing.update(keys)
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            f.write(dumps(existing))
