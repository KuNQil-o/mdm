from copy import deepcopy
from datetime import datetime, timezone
from decimal import Decimal
from uuid import uuid4
import secrets
import re
import time
from .common import (
    Problem,
    require,
    dumps,
    canonical,
    digest,
    identifier,
    camel,
    password_hash,
)
from .db import one, rows, lock, Jsonb
from . import engine
from .security import PERMISSIONS, install_roles

RESOURCES = {
    "categories": ("CATEGORY", "SCHEMA_DESIGN"),
    "schemas": ("SCHEMA", "SCHEMA_DESIGN"),
    "input-profiles": ("INPUT_PROFILE", "INPUT_DESIGN"),
    "validation-rules": ("VALIDATION", "VALIDATION_DESIGN"),
    "derivation-rules": ("DERIVATION", "DERIVATION_DESIGN"),
    "identity-definitions": ("IDENTITY", "IDENTITY_DESIGN"),
    "code-rules": ("CODE_RULE", "CODE_DESIGN"),
    "reference-data": ("DICTIONARY", "DICTIONARY_DESIGN"),
}
REFS = {
    "categoryVersionId": ("CATEGORY", "category"),
    "schemaVersionId": ("SCHEMA", "schema"),
    "identityDefinitionVersionId": ("IDENTITY", "identity"),
    "codeRuleVersionId": ("CODE_RULE", "codeRule"),
}
ARRAY_REFS = {
    "validationRuleVersionIds": ("VALIDATION", "validations"),
    "derivationRuleVersionIds": ("DERIVATION", "derivations"),
    "inputProfileVersionIds": ("INPUT_PROFILE", "inputProfiles"),
    "dictionaryVersionIds": ("DICTIONARY", "dictionaries"),
}


def audit(conn, c, object_type, object_id, action, details=None, category=None):
    version = None
    if object_type in {kind for kind, _ in RESOURCES.values()}:
        config_row = one(
            conn,
            "SELECT version FROM config_version WHERE tenant_id=%s AND id=%s",
            (c.tenant_id, identifier(object_id)),
        )
        version = config_row["version"] if config_row else None
    elif object_type == "RELEASE":
        release_row = one(
            conn,
            "SELECT version FROM release_package WHERE tenant_id=%s AND id=%s",
            (c.tenant_id, identifier(object_id)),
        )
        version = release_row["version"] if release_row else None
    reason = {
        "ISSUE": "首次正式发号",
        "REUSE_SOURCE": "同来源记录复用永久发号事实",
        "REUSE_IDENTITY": "同Identity跨来源复用",
        "SUBMIT": "提交版本与回归结果供评审",
        "PUBLISH": "由不同成员审批发布",
        "REJECT": "审批驳回",
        "RETIRE": "退役已发布版本",
        "ROTATE_KEY": "轮换调用系统凭据",
    }.get(action, action)
    conn.execute(
        "INSERT INTO operation_log(id,tenant_id,actor,category_code,object_type,object_id,action,details,request_id,version,reason) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
        (
            uuid4(),
            c.tenant_id,
            c.actor,
            category,
            object_type,
            str(object_id),
            action,
            Jsonb(details or {}),
            c.request_id,
            version,
            reason,
        ),
    )


def expect_version(row, expected):
    require(expected is not None, 428, "VERSION_REQUIRED", "请提供 If-Match 版本条件")
    require(
        str(expected).strip('"') == str(row["row_version"]),
        409,
        "VERSION_CONFLICT",
        "记录已变化，请重新加载版本",
    )


def valid_code(code):
    require(
        isinstance(code, str) and re.fullmatch("[A-Za-z][A-Za-z0-9_-]{0,79}", code),
        422,
        "INVALID_CODE",
        "代码须以字母开头，仅允许字母、数字、下划线和短横线",
    )
    return code


def object_body(body, allowed):
    require(
        isinstance(body, dict) and set(body) <= set(allowed),
        400,
        "BAD_REQUEST",
        "请求含未知或不允许的字段",
    )


def category(conn, c, code):
    cat = one(
        conn,
        "SELECT * FROM category WHERE tenant_id=%s AND code=%s",
        (c.tenant_id, code),
    )
    require(cat is not None, 404, "CATEGORY_NOT_FOUND", "类别不存在")
    return cat


def config(conn, c, id, kind=None, for_update=False):
    row = one(
        conn,
        "SELECT v.*,cat.code AS category_code FROM config_version v JOIN category cat ON cat.id=v.category_id "
        "WHERE v.tenant_id=%s AND v.id=%s" + (" FOR UPDATE OF v" if for_update else ""),
        (c.tenant_id, identifier(id)),
    )
    require(
        row is not None and (kind is None or row["kind"] == kind),
        404,
        "CONFIG_NOT_FOUND",
        "配置版本不存在",
    )
    c.check("PREVIEW", row["category_code"]) if c.caller else require(
        "*" in c.scope or row["category_code"] in c.scope,
        403,
        "CATEGORY_FORBIDDEN",
        "类别不在授权范围",
    )
    return row


def config_list(conn, c, kind):
    require(
        bool(
            c.permissions
            & {"PREVIEW", "ASSIGNMENT_READ", "RELEASE_PUBLISH", "SCHEMA_DESIGN"}
        ),
        403,
        "ACTION_FORBIDDEN",
        "没有配置读取权限",
    )
    return [
        camel(r)
        for r in rows(
            conn,
            "SELECT v.*,cat.code AS category_code,cat.enabled AS category_enabled,cat.row_version AS availability_version FROM config_version v "
            "JOIN category cat ON cat.id=v.category_id WHERE v.tenant_id=%s AND v.kind=%s ORDER BY v.created_at DESC,v.version DESC",
            (c.tenant_id, kind),
        )
        if "*" in c.scope or r["category_code"] in c.scope
    ]


def basic_definition(kind, definition):
    require(
        isinstance(definition, dict) and len(dumps(definition)) <= 200000,
        422,
        "CONFIGURATION_ERROR",
        "definition须为受限配置对象",
    )
    allowed = {
        "CATEGORY": {"name", "description", "identityReusePolicy", "numberingPolicy"},
        "SCHEMA": {"attributes", "units"},
        "INPUT_PROFILE": {"callerSystemCode", "fields"},
        "VALIDATION": {"rules"},
        "DERIVATION": {"attributes"},
        "IDENTITY": {"attributes"},
        "CODE_RULE": {"segments", "separator", "maxLength", "allowedPattern", "case"},
        "DICTIONARY": {"entries", "description"},
    }[kind]
    require(
        set(definition) <= allowed,
        422,
        "CONFIGURATION_ERROR",
        "配置含不支持字段，禁止SQL、脚本、Join和远程数据源",
    )
    if kind == "CATEGORY":
        require(
            definition.get("identityReusePolicy", "REUSE_EXISTING")
            in {"REUSE_EXISTING", "REVIEW_ON_DUPLICATE"},
            422,
            "CONFIGURATION_ERROR",
            "复用策略无效",
        )
        require(
            definition.get("numberingPolicy", "NUMBER_ONCE") == "NUMBER_ONCE",
            422,
            "CONFIGURATION_ERROR",
            "P0只支持NUMBER_ONCE",
        )
    if kind == "DICTIONARY":
        entries = definition.get("entries")
        require(
            isinstance(entries, list) and 0 < len(entries) <= 10000,
            422,
            "CONFIGURATION_ERROR",
            "字典须有1至10000个稳定代码项",
        )
        codes = [entry.get("code") for entry in entries if isinstance(entry, dict)]
        require(
            len(codes) == len(entries)
            and all(isinstance(x, str) and 0 < len(x) <= 1000 for x in codes)
            and len(set(codes)) == len(codes),
            422,
            "CONFIGURATION_ERROR",
            "字典代码无效或重复",
        )


def save_config(conn, c, resource, body, id=None, expected=None):
    kind, permission = RESOURCES[resource]
    object_body(
        body, {"code", "categoryCode", "definition"} if id is None else {"definition"}
    )
    if id is None:
        code = valid_code(body.get("code"))
        cat_code = code if kind == "CATEGORY" else body.get("categoryCode")
        c.check(permission, cat_code)
        if kind == "CATEGORY":
            conn.execute(
                "INSERT INTO category(id,tenant_id,code) VALUES(%s,%s,%s) ON CONFLICT(tenant_id,code) DO NOTHING",
                (uuid4(), c.tenant_id, code),
            )
        cat = category(conn, c, cat_code)
        definition = body.get("definition")
        basic_definition(kind, definition)
        lock(conn, f"{c.tenant_id}:config:{kind}:{code}")
        previous = one(
            conn,
            "SELECT * FROM config_version WHERE tenant_id=%s AND kind=%s AND code=%s ORDER BY version DESC LIMIT 1",
            (c.tenant_id, kind, code),
        )
        require(
            previous is None or previous["category_id"] == cat["id"],
            409,
            "CONFIG_CATEGORY_CONFLICT",
            "配置代码不能移动到另一个类别",
        )
        version = (previous["version"] + 1) if previous else 1
        newid = uuid4()
        conn.execute(
            "INSERT INTO config_version(id,tenant_id,category_id,kind,code,version,definition,created_by) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)",
            (
                newid,
                c.tenant_id,
                cat["id"],
                kind,
                code,
                version,
                Jsonb(definition),
                identifier(c.actor),
            ),
        )
        audit(
            conn,
            c,
            kind,
            newid,
            "CREATE",
            {"definition": definition, "version": version},
            cat_code,
        )
        return camel(config(conn, c, newid, kind))
    old = config(conn, c, id, kind, True)
    c.check(permission, old["category_code"])
    expect_version(old, expected)
    require(
        not old["ever_published"] and old["status"] == "DRAFT",
        409,
        "IMMUTABLE_VERSION",
        "仅未提交、未发布的草稿可以修改；请创建新版本",
    )
    definition = body.get("definition")
    basic_definition(kind, definition)
    conn.execute(
        "UPDATE config_version SET definition=%s,row_version=row_version+1 WHERE id=%s",
        (Jsonb(definition), old["id"]),
    )
    audit(
        conn,
        c,
        kind,
        id,
        "UPDATE",
        {"before": old["definition"], "after": definition},
        old["category_code"],
    )
    return camel(config(conn, c, id, kind))


def callers(conn, c):
    c.check("CALLER_MANAGE")
    return [
        public_caller(row)
        for row in rows(
            conn,
            "SELECT * FROM caller_system WHERE tenant_id=%s ORDER BY created_at DESC",
            (c.tenant_id,),
        )
    ]


def public_caller(row):
    result = camel(row)
    result.pop("credentialHash", None)
    result["callerSystemId"] = result["id"]
    return result


def save_caller(conn, c, body, id=None, expected=None, rotate=False):
    c.check("CALLER_MANAGE")
    allowed = {
        "code",
        "name",
        "systemType",
        "environment",
        "status",
        "allowedCategoryCodes",
        "owner",
        "description",
        "rateLimit",
    }
    object_body(body, allowed if id is None else allowed - {"code"})
    old = None
    if id:
        old = one(
            conn,
            "SELECT * FROM caller_system WHERE tenant_id=%s AND id=%s FOR UPDATE",
            (c.tenant_id, identifier(id)),
        )
        require(old is not None, 404, "CALLER_SYSTEM_NOT_FOUND", "调用系统不存在")
        expect_version(old, expected)
        require(
            old["status"] != "RETIRED",
            409,
            "CALLER_RETIRED",
            "已退役调用方不能恢复或修改",
        )
    code = old["code"] if old else valid_code(body.get("code"))
    value = lambda api, col, default: body.get(api, old[col] if old else default)
    status = value("status", "status", "DRAFT")
    sys_type = value("systemType", "system_type", "CUSTOM")
    environment = value("environment", "environment", "DEV")
    require(
        status in {"DRAFT", "ACTIVE", "SUSPENDED", "RETIRED"}
        and sys_type in {"ERP", "PLM", "MES", "CUSTOM", "OTHER"}
        and environment in {"DEV", "TEST", "PROD"},
        422,
        "CALLER_CONFIGURATION_ERROR",
        "调用方类型、环境或状态无效",
    )
    scope = value("allowedCategoryCodes", "allowed_category_codes", [])
    require(
        isinstance(scope, list)
        and len(scope) <= 1000
        and all(isinstance(x, str) for x in scope),
        422,
        "CALLER_CONFIGURATION_ERROR",
        "允许类别须为数组",
    )
    for cat_code in scope:
        require(
            cat_code != "*" and ("*" in c.scope or cat_code in c.scope),
            403,
            "CATEGORY_FORBIDDEN",
            "只能授予自身已授权的明确类别",
        )
        category(conn, c, cat_code)
    rate = value("rateLimit", "rate_limit", 10000)
    require(
        isinstance(rate, int) and 1 <= rate <= 100000,
        422,
        "CALLER_CONFIGURATION_ERROR",
        "rateLimit范围1至100000每分钟",
    )
    key = secrets.token_urlsafe(48) if old is None or rotate else None
    credential = digest(key) if key else old["credential_hash"]
    newid = old["id"] if old else uuid4()
    params = (
        value("name", "name", code),
        sys_type,
        environment,
        status,
        credential,
        Jsonb(scope),
        value("owner", "owner", ""),
        value("description", "description", ""),
        rate,
    )
    if old:
        conn.execute(
            "UPDATE caller_system SET name=%s,system_type=%s,environment=%s,status=%s,credential_hash=%s,allowed_category_codes=%s,owner=%s,description=%s,rate_limit=%s,row_version=row_version+1 WHERE id=%s",
            params + (newid,),
        )
    else:
        conn.execute(
            "INSERT INTO caller_system(name,system_type,environment,status,credential_hash,allowed_category_codes,owner,description,rate_limit,id,tenant_id,code) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
            params + (newid, c.tenant_id, code),
        )
    # Never log the key, hash or entire incoming payload.
    audit(
        conn,
        c,
        "CALLER_SYSTEM",
        newid,
        "ROTATE_KEY" if rotate else "UPDATE" if old else "CREATE",
        {"code": code, "status": status, "allowedCategoryCodes": scope},
    )
    result = public_caller(
        one(conn, "SELECT * FROM caller_system WHERE id=%s", (newid,))
    )
    if key:
        result["apiKey"] = key
    return result


def release(conn, c, id, update=False):
    row = one(
        conn,
        "SELECT r.*,cat.code AS category_code FROM release_package r JOIN category cat ON cat.id=r.category_id "
        "WHERE r.tenant_id=%s AND r.id=%s" + (" FOR UPDATE OF r" if update else ""),
        (c.tenant_id, identifier(id)),
    )
    require(row is not None, 404, "RELEASE_NOT_FOUND", "发布包不存在")
    require(
        "*" in c.scope or row["category_code"] in c.scope,
        403,
        "CATEGORY_FORBIDDEN",
        "发布包类别不在授权范围",
    )
    return row


def current_release(conn, c, cat):
    row = one(
        conn,
        "SELECT r.*,cat.code AS category_code FROM release_package r JOIN category cat ON cat.id=r.category_id "
        "WHERE r.tenant_id=%s AND r.category_id=%s AND r.status='PUBLISHED'",
        (c.tenant_id, cat["id"]),
    )
    require(row is not None, 422, "NO_PUBLISHED_RELEASE", "类别没有当前已发布版本")
    return row


def build_bundle(conn, c, rel, freeze=False):
    refs = rel["refs"]
    require(
        set(refs) <= set(REFS) | set(ARRAY_REFS),
        422,
        "CONFIGURATION_ERROR",
        "发布包包含未知依赖",
    )
    configs = []
    result = {}
    for key, (kind, label) in REFS.items():
        require(refs.get(key), 422, "CONFIGURATION_ERROR", "发布包缺少依赖：" + key)
        row = config(conn, c, refs[key], kind)
        configs.append(row)
        result[label] = camel(row)
    for key, (kind, label) in ARRAY_REFS.items():
        ids = refs.get(key, [])
        require(
            isinstance(ids, list) and len(ids) <= 100 and len(set(ids)) == len(ids),
            422,
            "CONFIGURATION_ERROR",
            "发布依赖数组无效",
        )
        items = []
        for id in ids:
            row = config(conn, c, id, kind)
            configs.append(row)
            items.append(camel(row))
        result[label] = (
            {str(v["id"]): v for v in items} if label == "dictionaries" else items
        )
    for row in configs:
        require(
            row["category_id"] == rel["category_id"],
            422,
            "CONFIGURATION_ERROR",
            "发布包依赖必须属于同一类别",
        )
        require(
            row["status"] != "RETIRED", 422, "CONFIGURATION_ERROR", "不能发布已退役依赖"
        )
    profiles = [
        p["definition"].get("callerSystemCode") for p in result["inputProfiles"]
    ]
    require(
        all(isinstance(p, str) and p for p in profiles)
        and len(profiles) == len(set(profiles)),
        422,
        "CONFIGURATION_ERROR",
        "每个Caller只能有一个Input Profile",
    )
    for p in profiles:
        require(
            one(
                conn,
                "SELECT id FROM caller_system WHERE tenant_id=%s AND code=%s",
                (c.tenant_id, p),
            ),
            422,
            "CONFIGURATION_ERROR",
            "Input Profile调用方不存在",
        )
    engine.compile_bundle(result)
    if freeze:
        for row in configs:
            if not row["ever_published"]:
                conn.execute(
                    "UPDATE config_version SET status='REVIEW',row_version=row_version+1 WHERE id=%s",
                    (row["id"],),
                )
    return result


def save_release(conn, c, body, id=None, expected=None):
    c.check("RELEASE_SUBMIT", body.get("categoryCode") if not id else None)
    object_body(
        body,
        {"code", "categoryCode", "refs", "samples"}
        if id is None
        else {"refs", "samples"},
    )
    require(
        isinstance(body.get("refs"), dict)
        and isinstance(body.get("samples"), list)
        and 1 <= len(body["samples"]) <= 100,
        422,
        "CONFIGURATION_ERROR",
        "发布包须有refs和1至100个回归样本",
    )
    if not id:
        cat = category(conn, c, body.get("categoryCode"))
        code = valid_code(body.get("code"))
        lock(conn, f"{c.tenant_id}:release:{code}")
        version = one(
            conn,
            "SELECT coalesce(max(version),0)+1 AS version FROM release_package WHERE tenant_id=%s AND code=%s",
            (c.tenant_id, code),
        )["version"]
        old = one(
            conn,
            "SELECT category_id FROM release_package WHERE tenant_id=%s AND code=%s LIMIT 1",
            (c.tenant_id, code),
        )
        require(
            old is None or old["category_id"] == cat["id"],
            409,
            "CONFIG_CATEGORY_CONFLICT",
            "发布包代码不能移动类别",
        )
        newid = uuid4()
        conn.execute(
            "INSERT INTO release_package(id,tenant_id,category_id,code,version,refs,samples,created_by) VALUES(%s,%s,%s,%s,%s,%s,%s,%s)",
            (
                newid,
                c.tenant_id,
                cat["id"],
                code,
                version,
                Jsonb(body["refs"]),
                Jsonb(body["samples"]),
                identifier(c.actor),
            ),
        )
        audit(conn, c, "RELEASE", newid, "CREATE", {"refs": body["refs"]}, cat["code"])
        return camel(release(conn, c, newid))
    old = release(conn, c, id, True)
    c.check("RELEASE_SUBMIT", old["category_code"])
    expect_version(old, expected)
    require(
        old["status"] == "DRAFT" and not old["ever_published"],
        409,
        "IMMUTABLE_VERSION",
        "仅草稿发布包可以修改",
    )
    conn.execute(
        "UPDATE release_package SET refs=%s,samples=%s,tests=NULL,row_version=row_version+1 WHERE id=%s",
        (Jsonb(body["refs"]), Jsonb(body["samples"]), old["id"]),
    )
    audit(
        conn, c, "RELEASE", id, "UPDATE", {"refs": body["refs"]}, old["category_code"]
    )
    return camel(release(conn, c, id))


def structural_diff(before, after, path=""):
    result = []
    if isinstance(before, dict) and isinstance(after, dict):
        for key in sorted(set(before) | set(after)):
            result += structural_diff(before.get(key), after.get(key), path + "/" + key)
    elif canonical(before) != canonical(after):
        result.append({"path": path or "/", "before": before, "after": after})
    return result


def test_release(conn, c, rel, bundle=None):
    c.check("PREVIEW", rel["category_code"])
    bundle = bundle or build_bundle(conn, c, rel)
    outputs = []
    identities = {}
    codes = {}
    passed = True
    for index, sample in enumerate(rel["samples"]):
        object_body(
            sample,
            {
                "callerSystemCode",
                "attributes",
                "expectedMaterialNo",
                "expectedIdentityCanonical",
                "expectedError",
                "allowDuplicateIdentity",
            },
        )
        try:
            prepared = engine.prepare(
                bundle, sample.get("callerSystemCode", ""), sample.get("attributes")
            )
            rendered = engine.render(bundle, prepared["normalizedAttributes"])
            canonical_id = prepared["identityCanonical"]
            no = rendered["materialNo"]
            require(
                "expectedError" not in sample,
                422,
                "SAMPLE_EXPECTATION",
                "样本预期错误但实际通过",
            )
            if "expectedMaterialNo" in sample:
                require(
                    no == sample["expectedMaterialNo"],
                    422,
                    "SAMPLE_EXPECTATION",
                    "料号回归预期不匹配",
                )
            if "expectedIdentityCanonical" in sample:
                require(
                    canonical_id == sample["expectedIdentityCanonical"],
                    422,
                    "SAMPLE_EXPECTATION",
                    "Identity回归预期不匹配",
                )
            require(
                canonical_id not in identities
                or sample.get("allowDuplicateIdentity", False),
                422,
                "IDENTITY_CONFLICT",
                "发布回归样本中出现重复Identity；须明确allowDuplicateIdentity",
            )
            if not any(
                s["type"] == "SEQUENCE"
                for s in bundle["codeRule"]["definition"]["segments"]
            ):
                require(
                    no not in codes or codes[no] == canonical_id,
                    422,
                    "CODE_CONFLICT",
                    "不同Identity样本生成同一料号",
                )
            identities[canonical_id] = index
            codes[no] = canonical_id
            outputs.append({"index": index, "passed": True, **prepared, **rendered})
        except Problem as p:
            ok = sample.get("expectedError") == p.code
            passed = passed and ok
            outputs.append(
                {
                    "index": index,
                    "passed": ok,
                    "code": p.code,
                    "message": p.message,
                    "details": p.details,
                }
            )
    current = one(
        conn,
        "SELECT * FROM release_package WHERE tenant_id=%s AND category_id=%s AND status='PUBLISHED' AND id<>%s",
        (c.tenant_id, rel["category_id"], rel["id"]),
    )
    before = current["dependency_snapshot"] if current else None

    def semantics(value):
        if not value:
            return None
        return {
            k: (
                {x: y["definition"] for x, y in v.items()}
                if k == "dictionaries"
                else [i["definition"] for i in v]
                if isinstance(v, list)
                else v["definition"]
            )
            for k, v in value.items()
        }

    diff = structural_diff(semantics(before), semantics(bundle))
    passed = passed and any(
        o.get("passed") and "identityCanonical" in o for o in outputs
    )
    return {
        "passed": passed,
        "samples": outputs,
        "diff": diff,
        "previousReleaseId": str(current["id"]) if current else None,
        "dependencyDigest": digest(dumps(canonical(bundle))),
        "checkedAt": datetime.now(timezone.utc),
    }


def release_action(conn, c, id, action, expected):
    rel = release(conn, c, id, True)
    expect_version(rel, expected)
    require(not c.caller, 403, "ACTION_FORBIDDEN", "调用系统不能执行发布治理")
    if action == "test":
        require(
            rel["status"] == "DRAFT",
            409,
            "RELEASE_STATE",
            "测试仅作用于草稿；已提交版本查看固化测试结果",
        )
        results = test_release(conn, c, rel)
        conn.execute(
            "UPDATE release_package SET tests=%s,row_version=row_version+1 WHERE id=%s",
            (Jsonb(results), rel["id"]),
        )
        audit(
            conn,
            c,
            "RELEASE",
            id,
            "TEST",
            {"passed": results["passed"]},
            rel["category_code"],
        )
    elif action == "submit":
        c.check("RELEASE_SUBMIT", rel["category_code"])
        require(rel["status"] == "DRAFT", 409, "RELEASE_STATE", "仅草稿可提交")
        lock(conn, f"{c.tenant_id}:governance:{rel['category_id']}")
        bundle = build_bundle(conn, c, rel, True)
        tests = test_release(conn, c, rel, bundle)
        require(
            tests["passed"],
            422,
            "RELEASE_TEST_FAILED",
            "回归样本未全部通过",
            tests["samples"],
        )
        conn.execute(
            "UPDATE release_package SET status='REVIEW',dependency_snapshot=%s,tests=%s,submitted_by=%s,row_version=row_version+1 WHERE id=%s",
            (Jsonb(bundle), Jsonb(tests), identifier(c.actor), rel["id"]),
        )
        audit(
            conn,
            c,
            "RELEASE",
            id,
            "SUBMIT",
            {"testsPassed": True},
            rel["category_code"],
        )
    elif action == "publish":
        c.check("RELEASE_PUBLISH", rel["category_code"])
        require(
            rel["status"] == "REVIEW" and str(rel["submitted_by"]) != c.actor,
            409,
            "SEPARATION_OF_DUTIES",
            "必须由不同成员审批已提交的发布包",
        )
        lock(conn, f"{c.tenant_id}:governance:{rel['category_id']}")
        bundle = build_bundle(conn, c, rel)
        # Status/rowVersion may change while reviewed config content is frozen. Compare only semantic definitions.
        original = rel["dependency_snapshot"]

        def definitions(b):
            return {
                k: {str(x["id"]): x["definition"] for x in v.values()}
                if k == "dictionaries"
                else {str(x["id"]): x["definition"] for x in v}
                if isinstance(v, list)
                else {"id": str(v["id"]), "definition": v["definition"]}
                for k, v in b.items()
            }

        require(
            canonical(definitions(bundle)) == canonical(definitions(original)),
            409,
            "DEPENDENCY_CHANGED",
            "审核期间依赖已变化，请重新提交",
        )
        tests = test_release(conn, c, rel, original)
        require(tests["passed"], 422, "RELEASE_TEST_FAILED", "审批前回归失败")
        conn.execute(
            "UPDATE release_package SET status='RETIRED',row_version=row_version+1 WHERE tenant_id=%s AND category_id=%s AND status='PUBLISHED'",
            (c.tenant_id, rel["category_id"]),
        )
        for cfg in (
            [v for v in original.values() if isinstance(v, dict) and "id" in v]
            + [x for v in original.values() if isinstance(v, list) for x in v]
            + list(original["dictionaries"].values())
        ):
            conn.execute(
                "UPDATE config_version SET status='PUBLISHED',ever_published=true,row_version=row_version+1 WHERE tenant_id=%s AND id=%s",
                (c.tenant_id, identifier(cfg["id"])),
            )
        conn.execute(
            "UPDATE release_package SET status='PUBLISHED',ever_published=true,approved_by=%s,published_at=now(),row_version=row_version+1 WHERE id=%s",
            (identifier(c.actor), rel["id"]),
        )
        audit(
            conn,
            c,
            "RELEASE",
            id,
            "PUBLISH",
            {"submittedBy": rel["submitted_by"], "approvedBy": c.actor},
            rel["category_code"],
        )
    elif action == "reject":
        c.check("RELEASE_PUBLISH", rel["category_code"])
        require(
            rel["status"] == "REVIEW" and str(rel["submitted_by"]) != c.actor,
            409,
            "RELEASE_STATE",
            "需要其他审批员驳回待审包",
        )
        conn.execute(
            "UPDATE release_package SET status='DRAFT',dependency_snapshot=NULL,tests=NULL,submitted_by=NULL,row_version=row_version+1 WHERE id=%s",
            (rel["id"],),
        )
        # Unfreeze only configs that no other REVIEW/PUBLISHED package references.
        for cfg in rows(
            conn,
            "SELECT * FROM config_version WHERE tenant_id=%s AND category_id=%s AND status='REVIEW' AND NOT ever_published",
            (c.tenant_id, rel["category_id"]),
        ):
            used = one(
                conn,
                "SELECT id FROM release_package WHERE tenant_id=%s AND status IN ('REVIEW','PUBLISHED') AND refs::text LIKE %s LIMIT 1",
                (c.tenant_id, "%" + str(cfg["id"]) + "%"),
            )
            if not used:
                conn.execute(
                    "UPDATE config_version SET status='DRAFT',row_version=row_version+1 WHERE id=%s",
                    (cfg["id"],),
                )
        audit(conn, c, "RELEASE", id, "REJECT", category=rel["category_code"])
    elif action == "retire":
        c.check("RELEASE_PUBLISH", rel["category_code"])
        require(rel["status"] == "PUBLISHED", 409, "RELEASE_STATE", "仅已发布包可退役")
        lock(conn, f"{c.tenant_id}:governance:{rel['category_id']}")
        conn.execute(
            "UPDATE release_package SET status='RETIRED',row_version=row_version+1 WHERE id=%s",
            (rel["id"],),
        )
        audit(conn, c, "RELEASE", id, "RETIRE", category=rel["category_code"])
    else:
        raise Problem(404, "NOT_FOUND", "发布操作不存在")
    return camel(release(conn, c, id))


def assignment_request(conn, c, body, preview=False):
    object_body(
        body,
        {
            "callerSystemCode",
            "sourceRecordKey",
            "categoryCode",
            "attributes",
            "releasePackageVersionId",
        }
        if preview
        else {"callerSystemCode", "sourceRecordKey", "categoryCode", "attributes"},
    )
    code = body.get("categoryCode")
    caller_code = body.get("callerSystemCode")
    require(
        isinstance(code, str) and isinstance(caller_code, str),
        400,
        "BAD_REQUEST",
        "必须提供类别和调用系统代码",
    )
    if c.caller:
        require(
            caller_code == c.caller["code"],
            403,
            "CALLER_SCOPE",
            "payload调用方与鉴权凭据不匹配",
        )
        c.check("PREVIEW" if preview else "ASSIGNMENT_READ", code)
    else:
        c.check("PREVIEW" if preview else "ASSIGNMENT_READ", code)
        require(
            preview,
            403,
            "MANUAL_ASSIGN_DISABLED",
            "正式发号必须使用Caller凭据；人工发号默认关闭",
        )
        caller = one(
            conn,
            "SELECT * FROM caller_system WHERE tenant_id=%s AND code=%s",
            (c.tenant_id, caller_code),
        )
        require(caller is not None, 404, "CALLER_SYSTEM_NOT_FOUND", "调用系统不存在")
    cat = category(conn, c, code)
    require(preview or cat["enabled"], 409, "CATEGORY_SUSPENDED", "类别已暂停正式发号")
    if not preview:
        key = body.get("sourceRecordKey")
        require(
            isinstance(key, str) and bool(key.strip()) and len(key) <= 1000,
            400,
            "SOURCE_KEY_REQUIRED",
            "正式发号必须提供稳定sourceRecordKey",
        )
    require(
        isinstance(body.get("attributes"), dict),
        422,
        "SCHEMA_VALIDATION_ERROR",
        "attributes必须为对象",
    )
    require(
        len(dumps(body["attributes"])) <= 200000,
        413,
        "PAYLOAD_TOO_LARGE",
        "属性输入超过预算",
    )
    return cat


def preview(conn, c, body):
    cat = assignment_request(conn, c, body, True)
    if body.get("releasePackageVersionId"):
        require(
            not c.caller and any(p.endswith("_DESIGN") for p in c.permissions),
            403,
            "DRAFT_PREVIEW_FORBIDDEN",
            "草稿预览仅对设计员开放",
        )
        rel = release(conn, c, body["releasePackageVersionId"])
        require(
            rel["category_id"] == cat["id"],
            422,
            "CONFIGURATION_ERROR",
            "预览版本与类别不一致",
        )
    else:
        rel = current_release(conn, c, cat)
    bundle = rel["dependency_snapshot"] or build_bundle(conn, c, rel)
    prepared = engine.prepare(bundle, body["callerSystemCode"], body["attributes"])
    output = {
        **prepared,
        **engine.render(bundle, prepared["normalizedAttributes"]),
        "releaseVersion": f"4.0.{rel['version']}",
        "releasePackageVersionId": rel["id"],
        "sequenceReserved": False,
    }
    audit(
        conn,
        c,
        "PREVIEW",
        rel["id"],
        "PREVIEW",
        {"identityHash": prepared["identityHash"]},
        cat["code"],
    )
    return output


def assignment_result(row, rel, reused):
    return {
        "assignmentId": row["id"],
        "materialNo": row["material_no"],
        "status": "REUSED" if reused else "ISSUED",
        "reused": reused,
        "categoryCode": row["category_code"],
        "releaseVersion": f"4.0.{rel['version']}",
        "releasePackageVersionId": rel["id"],
        "issuedAt": row["issued_at"],
    }


def assignment_row(conn, c, id):
    row = one(
        conn,
        "SELECT a.*,cat.code AS category_code FROM material_assignment a JOIN category cat ON cat.id=a.category_id WHERE a.tenant_id=%s AND a.id=%s",
        (c.tenant_id, identifier(id)),
    )
    require(row is not None, 404, "ASSIGNMENT_NOT_FOUND", "发号记录不存在")
    c.check("ASSIGNMENT_READ", row["category_code"])
    if c.caller:
        require(
            one(
                conn,
                "SELECT 1 FROM source_binding WHERE tenant_id=%s AND caller_system_id=%s AND assignment_id=%s",
                (c.tenant_id, c.caller["id"], row["id"]),
            ),
            404,
            "ASSIGNMENT_NOT_FOUND",
            "调用方未绑定该发号记录",
        )
    return row


def issue(conn, c, body, idempotency_key, failpoint=None):
    require(
        c.caller is not None,
        403,
        "MANUAL_ASSIGN_DISABLED",
        "正式发号必须使用Caller凭据",
    )
    c.active()
    require(
        isinstance(idempotency_key, str) and 0 < len(idempotency_key) <= 200,
        400,
        "IDEMPOTENCY_REQUIRED",
        "必须提供有效Idempotency-Key",
    )
    cat = assignment_request(conn, c, body)
    caller = c.caller
    source = body["sourceRecordKey"]
    request_hash = digest(dumps(canonical(body), separators=(",", ":")))
    lock(conn, f"{c.tenant_id}:idem:{caller['id']}:{idempotency_key}")
    old = one(
        conn,
        "SELECT * FROM assignment_idempotency WHERE tenant_id=%s AND caller_system_id=%s AND idempotency_key=%s",
        (c.tenant_id, caller["id"], idempotency_key),
    )
    if old:
        require(
            old["body_hash"] == request_hash,
            409,
            "IDEMPOTENCY_CONFLICT",
            "同幂等键的请求语义不同",
        )
        return {**old["result"], "status": "REUSED", "reused": True}
    lock(conn, f"{c.tenant_id}:source:{caller['id']}:{cat['id']}:{source}")
    bound = one(
        conn,
        "SELECT assignment_id FROM source_binding WHERE tenant_id=%s AND caller_system_id=%s AND category_id=%s AND source_record_key=%s",
        (c.tenant_id, caller["id"], cat["id"], source),
    )
    if bound:
        ledger = assignment_row(conn, c, bound["assignment_id"])
        # Evaluate with the original pinned release; today's rule changes cannot alter historical bindings.
        rel = release(conn, c, ledger["release_id"])
        prepared = engine.prepare(
            rel["dependency_snapshot"], caller["code"], body["attributes"]
        )
        require(
            prepared["identityCanonical"] == ledger["identity_canonical"],
            409,
            "SOURCE_IDENTITY_CHANGED_AFTER_ISSUE",
            "已发号来源记录的Identity发生变化，须创建新业务记录",
        )
        result = assignment_result(ledger, rel, True)
        audit(
            conn,
            c,
            "ASSIGNMENT",
            ledger["id"],
            "REUSE_SOURCE",
            {"callerSystemCode": caller["code"], "sourceRecordKey": source},
            cat["code"],
        )
    else:
        # Publication uses the same per-category lock. A request commits entirely under one release.
        # Shared lock permits distinct identities to number concurrently; publication takes exclusive lock.
        conn.execute(
            "SELECT pg_advisory_xact_lock_shared(hashtextextended(%s,0))",
            (f"{c.tenant_id}:governance:{cat['id']}",),
        )
        rel = current_release(conn, c, cat)
        bundle = rel["dependency_snapshot"]
        prepared = engine.prepare(bundle, caller["code"], body["attributes"])
        lock(conn, str(c.tenant_id) + str(cat["id"]) + prepared["identityHash"])
        bucket = rows(
            conn,
            "SELECT a.*,cat.code AS category_code FROM material_assignment a JOIN category cat ON cat.id=a.category_id WHERE a.tenant_id=%s AND a.category_id=%s AND a.identity_hash=%s",
            (c.tenant_id, cat["id"], prepared["identityHash"]),
        )
        ledger = next(
            (
                a
                for a in bucket
                if a["identity_canonical"] == prepared["identityCanonical"]
            ),
            None,
        )
        if ledger:
            require(
                bundle["category"]["definition"].get(
                    "identityReusePolicy", "REUSE_EXISTING"
                )
                == "REUSE_EXISTING",
                409,
                "IDENTITY_CONFLICT",
                "同Identity的其他来源记录需要人工确认",
            )
            result = assignment_result(
                ledger, release(conn, c, ledger["release_id"]), True
            )
        else:

            def allocate(name, key):
                started = time.perf_counter()
                row = one(
                    conn,
                    "INSERT INTO sequence_counter(tenant_id,code_rule_version_id,sequence_name,period_key,last_value) VALUES(%s,%s,%s,%s,1) "
                    "ON CONFLICT(tenant_id,code_rule_version_id,sequence_name,period_key) DO UPDATE SET last_value=sequence_counter.last_value+1 RETURNING last_value",
                    (c.tenant_id, identifier(bundle["codeRule"]["id"]), name, key),
                )
                c.sequence_latency_ms = (
                    getattr(c, "sequence_latency_ms", 0)
                    + (time.perf_counter() - started) * 1000
                )
                if failpoint:
                    failpoint("after_sequence", conn)
                return row["last_value"]

            rendered = engine.render(bundle, prepared["normalizedAttributes"], allocate)
            no = rendered["materialNo"]
            lock(conn, f"{c.tenant_id}:number:{no}")
            require(
                one(
                    conn,
                    "SELECT id FROM material_assignment WHERE tenant_id=%s AND material_no=%s",
                    (c.tenant_id, no),
                )
                is None,
                409,
                "CODE_CONFLICT",
                "不同Identity生成相同料号，请发布新的区分规则",
            )
            newid = uuid4()
            conn.execute(
                "INSERT INTO material_assignment(id,tenant_id,category_id,material_no,identity_canonical,identity_hash,collision_index,release_id,"
                "schema_version_id,input_profile_version_id,validation_rule_version_ids,derivation_rule_version_ids,identity_definition_version_id,code_rule_version_id,category_version_id,"
                "input_snapshot,mapped_snapshot,normalized_snapshot,derived_snapshot,code_segment_snapshot,validation_snapshot,issue_request_id) "
                "VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (
                    newid,
                    c.tenant_id,
                    cat["id"],
                    no,
                    prepared["identityCanonical"],
                    prepared["identityHash"],
                    max((x["collision_index"] for x in bucket), default=-1) + 1,
                    rel["id"],
                    identifier(bundle["schema"]["id"]),
                    identifier(prepared["inputProfileVersionId"])
                    if prepared["inputProfileVersionId"]
                    else None,
                    Jsonb([str(x["id"]) for x in bundle["validations"]]),
                    Jsonb([str(x["id"]) for x in bundle["derivations"]]),
                    identifier(bundle["identity"]["id"]),
                    identifier(bundle["codeRule"]["id"]),
                    identifier(bundle["category"]["id"]),
                    Jsonb(body["attributes"]),
                    Jsonb(prepared["mappedAttributes"]),
                    Jsonb(prepared["normalizedAttributes"]),
                    Jsonb(prepared["derivedAttributes"]),
                    Jsonb(rendered["codeSegments"]),
                    Jsonb(prepared["validationResults"]),
                    c.request_id,
                ),
            )
            ledger = one(
                conn,
                "SELECT a.*,cat.code AS category_code FROM material_assignment a JOIN category cat ON cat.id=a.category_id WHERE a.id=%s",
                (newid,),
            )
            result = assignment_result(ledger, rel, False)
        conn.execute(
            "INSERT INTO source_binding(tenant_id,caller_system_id,category_id,source_record_key,assignment_id) VALUES(%s,%s,%s,%s,%s)",
            (c.tenant_id, caller["id"], cat["id"], source, ledger["id"]),
        )
        audit(
            conn,
            c,
            "ASSIGNMENT",
            ledger["id"],
            "REUSE_IDENTITY" if result["reused"] else "ISSUE",
            {
                "callerSystemCode": caller["code"],
                "sourceRecordKey": source,
                "materialNo": result["materialNo"],
            },
            cat["code"],
        )
    conn.execute(
        "INSERT INTO assignment_idempotency(tenant_id,caller_system_id,idempotency_key,body_hash,result) VALUES(%s,%s,%s,%s,%s)",
        (c.tenant_id, caller["id"], idempotency_key, request_hash, Jsonb(result)),
    )
    return result


def explain(conn, c, id):
    row = assignment_row(conn, c, id)
    rel = release(conn, c, row["release_id"])
    bindings = rows(
        conn,
        "SELECT s.code AS caller_system_code,b.source_record_key,b.bound_at FROM source_binding b JOIN caller_system s ON s.id=b.caller_system_id WHERE b.tenant_id=%s AND b.assignment_id=%s"
        + (" AND b.caller_system_id=%s" if c.caller else ""),
        (c.tenant_id, row["id"]) + ((c.caller["id"],) if c.caller else ()),
    )
    result = camel(row)
    result.update(
        {
            "assignmentId": row["id"],
            "status": "ISSUED",
            "sourceBindings": [camel(b) for b in bindings],
            "releasePackageVersionId": rel["id"],
            "releaseVersion": f"4.0.{rel['version']}",
            "configurationSnapshot": rel["dependency_snapshot"],
            "explanationChain": [
                "Caller / Source",
                "Raw Input",
                "Input Profile",
                "Normalized / Derived",
                "Validation",
                "Identity",
                "Code Rule / Segments",
                "Material No",
                "Release Package",
            ],
        }
    )
    return result


def search(conn, c, params):
    c.check("ASSIGNMENT_READ")
    where = ["a.tenant_id=%s"]
    args = [c.tenant_id]
    if "*" not in c.scope:
        where.append("cat.code=ANY(%s)")
        args.append(list(c.scope))
    if c.caller:
        where.append(
            "EXISTS(SELECT 1 FROM source_binding bs WHERE bs.tenant_id=a.tenant_id AND bs.assignment_id=a.id AND bs.caller_system_id=%s)"
        )
        args.append(c.caller["id"])
    for key, col in [
        ("categoryCode", "cat.code"),
        ("materialNo", "a.material_no"),
        ("identityHash", "a.identity_hash"),
        ("identityCanonical", "a.identity_canonical"),
    ]:
        if params.get(key):
            where.append(col + "=%s")
            args.append(params[key])
    for key, op in [("from", ">="), ("to", "<=")]:
        if params.get(key):
            try:
                datetime.fromisoformat(params[key].replace("Z", "+00:00"))
            except (ValueError, TypeError):
                raise Problem(400, "BAD_REQUEST", "时间过滤须为ISO格式")
            where.append("a.issued_at" + op + "%s")
            args.append(params[key])
    if params.get("callerSystemCode") or params.get("sourceRecordKey"):
        pred = ["b.tenant_id=a.tenant_id", "b.assignment_id=a.id"]
        if params.get("callerSystemCode"):
            pred.append("s.code=%s")
            args.append(params["callerSystemCode"])
        if params.get("sourceRecordKey"):
            pred.append("b.source_record_key=%s")
            args.append(params["sourceRecordKey"])
        where.append(
            "EXISTS(SELECT 1 FROM source_binding b JOIN caller_system s ON s.id=b.caller_system_id WHERE "
            + " AND ".join(pred)
            + ")"
        )
    try:
        offset = max(0, int(params.get("offset", 0)))
        limit = min(200, max(1, int(params.get("limit", 50))))
    except (ValueError, TypeError):
        raise Problem(400, "BAD_REQUEST", "分页参数须为整数")
    query = (
        " FROM material_assignment a JOIN category cat ON cat.id=a.category_id WHERE "
        + " AND ".join(where)
    )
    total = one(conn, "SELECT count(*) AS total" + query, args)["total"]
    result = rows(
        conn,
        "SELECT a.id AS assignment_id,a.material_no,cat.code AS category_code,a.identity_hash,a.issued_at,a.release_id,"
        "(SELECT count(*) FROM source_binding b WHERE b.tenant_id=a.tenant_id AND b.assignment_id=a.id) AS source_binding_count"
        + query
        + " ORDER BY a.issued_at DESC,a.id LIMIT %s OFFSET %s",
        args + [limit, offset],
    )
    return {
        "items": [camel(r) for r in result],
        "total": total,
        "limit": limit,
        "offset": offset,
    }


def monitoring(conn, c):
    c.check("MONITOR_READ")
    scope_sql = (
        ""
        if "*" in c.scope
        else " AND (category_code=ANY(%s) OR category_code IS NULL)"
    )
    args = (c.tenant_id,) + (() if "*" in c.scope else (list(c.scope),))
    summary = one(
        conn,
        "SELECT count(*) AS requests,count(*) FILTER(WHERE status<400) AS successes,count(*) FILTER(WHERE status>=400) AS failures,"
        "count(*) FILTER(WHERE status=409) AS conflicts,count(*) FILTER(WHERE outcome='REUSED') AS reused,count(*) FILTER(WHERE outcome='ISSUED') AS issued,"
        "count(*) FILTER(WHERE operation='material-number-assignments') AS assignment_requests,count(*) FILTER(WHERE operation='material-number-previews') AS preview_requests,"
        "coalesce(percentile_cont(0.5) WITHIN GROUP(ORDER BY latency_ms),0) AS p50_ms,coalesce(percentile_cont(0.99) WITHIN GROUP(ORDER BY latency_ms),0) AS p99_ms,"
        "coalesce(percentile_cont(0.95) WITHIN GROUP(ORDER BY sequence_latency_ms),0) AS sequence_p95_ms,coalesce(percentile_cont(0.95) WITHIN GROUP(ORDER BY latency_ms),0) AS p95_ms,"
        "coalesce(avg(latency_ms),0) AS average_ms FROM request_metric WHERE tenant_id=%s AND created_at>now()-interval '24 hours'"
        + scope_sql,
        args,
    )
    count = summary["requests"]
    resolved = summary["reused"] + summary["issued"]
    summary["reuse_rate"] = (
        Decimal(summary["reused"]) / resolved if resolved else Decimal(0)
    )
    summary["failure_rate"] = (
        Decimal(summary["failures"]) / count if count else Decimal(0)
    )
    summary["conflict_rate"] = (
        Decimal(summary["conflicts"]) / count if count else Decimal(0)
    )
    errors = rows(
        conn,
        "SELECT code,count(*) AS count FROM request_metric WHERE tenant_id=%s AND status>=400 AND created_at>now()-interval '24 hours'"
        + scope_sql
        + " GROUP BY code ORDER BY count DESC",
        args,
    )
    sequences = rows(
        conn,
        "SELECT s.*,cat.code AS category_code FROM sequence_counter s JOIN config_version v ON v.id=s.code_rule_version_id JOIN category cat ON cat.id=v.category_id WHERE s.tenant_id=%s",
        (c.tenant_id,),
    )
    sequences = [
        camel(s) for s in sequences if "*" in c.scope or s["category_code"] in c.scope
    ]
    recent = rows(
        conn,
        "SELECT * FROM request_metric WHERE tenant_id=%s"
        + scope_sql
        + " ORDER BY created_at DESC LIMIT 30",
        args,
    )
    caller_health = rows(
        conn,
        "SELECT caller_code,count(*) AS requests,count(*) FILTER(WHERE status>=400) AS failures,max(created_at) AS last_call_at FROM request_metric WHERE tenant_id=%s AND created_at>now()-interval '24 hours'"
        + scope_sql
        + " GROUP BY caller_code ORDER BY requests DESC",
        args,
    )
    published = rows(
        conn,
        "SELECT r.id,cat.code AS category_code,r.version,r.published_at FROM release_package r JOIN category cat ON cat.id=r.category_id WHERE r.tenant_id=%s AND r.status='PUBLISHED'",
        (c.tenant_id,),
    )
    return {
        "callerHealth": [camel(x) for x in caller_health],
        "publishedReleases": [
            camel(x)
            for x in published
            if "*" in c.scope or x["category_code"] in c.scope
        ],
        "summary": camel(summary),
        "errors": [camel(x) for x in errors],
        "recentRequests": [camel(x) for x in recent],
        "sequences": sequences,
        "database": {
            "healthy": one(conn, "SELECT 1 AS ok")["ok"] == 1,
            "lockWaiting": one(
                conn,
                "SELECT count(*) AS n FROM pg_locks WHERE NOT granted AND database=(SELECT oid FROM pg_database WHERE datname=current_database())",
            )["n"],
            "lockScope": "共享数据库",
        },
        "window": "最近24小时",
        "recentAssignments": search(conn, c, {"limit": 6})
        if "ASSIGNMENT_READ" in c.permissions
        else {"items": [], "total": 0},
    }
