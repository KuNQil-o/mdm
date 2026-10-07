import logging
import os
import time
import secrets
import hmac
import hashlib
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from uuid import uuid4
from urllib.parse import unquote
import psycopg
from fastapi import FastAPI, Request
from psycopg_pool import PoolTimeout
from fastapi.responses import Response
from starlette.concurrency import run_in_threadpool
from .common import (
    Problem,
    require,
    dumps,
    loads,
    identifier,
    camel,
    digest,
    password_hash,
)
from .db import pool, migrate, one, rows, Jsonb
from .security import user_context, caller_context, login, install_roles, PERMISSIONS
from . import service as s

log = logging.getLogger("mdm")
PRODUCTION = os.getenv("APP_ENV", "development") == "production"


@asynccontextmanager
async def lifespan(app):
    pool.open()
    pool.wait(timeout=30)
    migrate()
    if not PRODUCTION:
        from .seed import seed

        seed()
    yield
    pool.close()


app = FastAPI(
    title="Material Identity & Number Governance V4",
    version="4.0.0",
    lifespan=lifespan,
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
)


def response(value, status=200, request_id=None, etag=None):
    headers = {
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
        "Vary": "X-Tenant-Code",
    }
    if request_id:
        headers["X-Request-ID"] = request_id
        headers["X-Trace-ID"] = digest(request_id)[:32]
        if isinstance(value, dict):
            value = {**value, "requestId": request_id, "traceId": headers["X-Trace-ID"]}
    if etag is not None:
        headers["ETag"] = '"' + str(etag) + '"'
    return Response(
        dumps(value), status_code=status, media_type="application/json", headers=headers
    )


def get_session_user(conn, cookie):
    row = one(
        conn,
        "SELECT u.id,u.code,u.name,u.platform_admin FROM auth_session a JOIN app_user u ON u.id=a.user_id WHERE a.token_hash=%s AND a.expires_at>now()",
        (digest(cookie or ""),),
    )
    require(row is not None, 401, "LOGIN_REQUIRED", "请先登录")
    return row


def csrf(headers):
    origin = headers.get("origin")
    if origin:
        from urllib.parse import urlparse

        require(
            origin == os.getenv("PUBLIC_ORIGIN")
            or urlparse(origin).netloc == headers.get("host"),
            403,
            "ORIGIN_FORBIDDEN",
            "跨站写请求被拒绝",
        )
    require(
        headers.get("sec-fetch-site") != "cross-site",
        403,
        "ORIGIN_FORBIDDEN",
        "跨站请求被拒绝",
    )


def verify_signature(conn, c, headers, method, target, raw_digest):
    if not PRODUCTION and not headers.get("x-signature"):
        return
    timestamp = headers.get("x-timestamp", "")
    nonce = headers.get("x-nonce", "")
    require(
        timestamp.isdigit() and abs(time.time() - int(timestamp)) <= 300,
        401,
        "SIGNATURE_EXPIRED",
        "请求签名时间超过5分钟窗口",
    )
    require(
        16 <= len(nonce) <= 100 and re_safe(nonce),
        401,
        "SIGNATURE_INVALID",
        "签名nonce格式无效",
    )
    key = headers.get("x-caller-key") or headers.get("authorization", "")[7:]
    payload = "\n".join([timestamp, nonce, method, target, raw_digest])
    signature = hmac.new(key.encode(), payload.encode(), hashlib.sha256).hexdigest()
    require(
        secrets.compare_digest(signature, headers.get("x-signature", "")),
        401,
        "SIGNATURE_INVALID",
        "请求签名无效",
    )
    # Commit nonce before business processing. Retry with a fresh nonce and the same Idempotency-Key.
    nonce_row = one(
        conn,
        "INSERT INTO caller_nonce(tenant_id,caller_system_id,nonce) VALUES(%s,%s,%s) ON CONFLICT DO NOTHING RETURNING nonce",
        (c.tenant_id, c.caller["id"], nonce),
    )
    conn.commit()
    require(
        nonce_row is not None,
        409,
        "REPLAY_REJECTED",
        "请求nonce已使用，请生成新nonce并保留原幂等键",
    )
    conn.execute("SET LOCAL statement_timeout = '30s'")


def rate_limit(c, body, conn):
    # Commit the budget before numbering, using the same connection to avoid nested pool starvation.
    bucket = one(
        conn,
        "INSERT INTO rate_bucket(tenant_id,caller_system_id,category_code,minute,requests) VALUES(%s,%s,%s,%s,1) "
        "ON CONFLICT(tenant_id,caller_system_id,category_code,minute) DO UPDATE SET requests=rate_bucket.requests+1 RETURNING requests",
        (
            c.tenant_id,
            c.caller["id"],
            str(body.get("categoryCode", ""))[:80],
            int(time.time() / 60),
        ),
    )
    conn.commit()
    require(
        bucket["requests"] <= c.caller["rate_limit"],
        429,
        "RATE_LIMITED",
        "调用频率超过每分钟配额",
    )
    conn.execute("SET LOCAL statement_timeout = '30s'")


def metric(c, path, body, status, code, started, outcome="UNKNOWN"):
    if c is None or path not in {
        "material-number-assignments",
        "material-number-previews",
    }:
        return
    try:
        with pool.connection() as conn:
            conn.execute(
                "INSERT INTO request_metric(tenant_id,caller_code,category_code,request_id,operation,status,code,latency_ms,outcome,sequence_latency_ms) VALUES(%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)",
                (
                    c.tenant_id,
                    c.caller["code"] if c.caller else body.get("callerSystemCode"),
                    str(body.get("categoryCode", ""))[:80],
                    c.request_id,
                    path,
                    status,
                    code,
                    round((time.perf_counter() - started) * 1000, 3),
                    outcome,
                    getattr(c, "sequence_latency_ms", None),
                ),
            )
            if status >= 400:
                s.audit(
                    conn,
                    c,
                    "REQUEST",
                    c.request_id,
                    "REJECTED",
                    {"code": code, "operation": path},
                    str(body.get("categoryCode", ""))[:80],
                )
    except Exception:
        log.error("Metric persistence failed for request %s", c.request_id)


def tenant_routes(conn, c, root, parts, method, body, expected):
    c.check("TENANT_ADMIN")
    id = parts[1] if len(parts) > 1 else None
    if root == "tenants":
        require(c.platform_admin, 403, "PLATFORM_FORBIDDEN", "需要平台管理员权限")
        if method == "GET":
            return [camel(x) for x in rows(conn, "SELECT * FROM tenant ORDER BY code")]
        if method == "POST" and id is None:
            s.object_body(body, {"code", "name", "adminUserId"})
            admin = identifier(body.get("adminUserId", c.actor))
            newid = uuid4()
            code = s.valid_code(body.get("code"))
            require(
                one(conn, "SELECT id FROM app_user WHERE id=%s", (admin,)),
                422,
                "USER_NOT_FOUND",
                "初始管理员不存在",
            )
            conn.execute(
                "INSERT INTO tenant(id,code,name,status) VALUES(%s,%s,%s,'ACTIVE')",
                (newid, code, body.get("name", code)),
            )
            install_roles(conn, newid)
            conn.execute(
                "INSERT INTO membership(tenant_id,user_id,roles) VALUES(%s,%s,%s)",
                (newid, admin, Jsonb(["ADMIN"])),
            )
            s.audit(conn, c, "TENANT", newid, "CREATE", {"code": code})
            return camel(one(conn, "SELECT * FROM tenant WHERE id=%s", (newid,)))
        if method == "PATCH" and id:
            old = one(
                conn, "SELECT * FROM tenant WHERE id=%s FOR UPDATE", (identifier(id),)
            )
            require(old is not None, 404, "TENANT_NOT_FOUND", "租户不存在")
            s.expect_version(old, expected)
            s.object_body(body, {"status", "name"})
            require(
                old["status"] != "ARCHIVED",
                409,
                "TENANT_ARCHIVED",
                "已归档租户不可修改",
            )
            status = body.get("status", old["status"])
            require(
                status in {"DRAFT", "ACTIVE", "SUSPENDED", "ARCHIVED"},
                422,
                "TENANT_STATUS",
                "租户状态无效",
            )
            conn.execute(
                "UPDATE tenant SET status=%s,name=%s,row_version=row_version+1 WHERE id=%s",
                (status, body.get("name", old["name"]), old["id"]),
            )
            s.audit(conn, c, "TENANT", id, "UPDATE", {"status": status})
            return camel(one(conn, "SELECT * FROM tenant WHERE id=%s", (old["id"],)))
    if root == "users":
        require(c.platform_admin, 403, "PLATFORM_FORBIDDEN", "需要平台管理员权限")
        if method == "GET":
            return [
                camel(x)
                for x in rows(
                    conn,
                    "SELECT id,code,name,platform_admin FROM app_user ORDER BY code",
                )
            ]
        if method == "POST" and id is None:
            s.object_body(body, {"code", "name", "password"})
            require(
                isinstance(body.get("password"), str)
                and 12 <= len(body["password"]) <= 256,
                422,
                "PASSWORD_POLICY",
                "密码须为12至256字符",
            )
            code = s.valid_code(body.get("code"))
            newid = uuid4()
            conn.execute(
                "INSERT INTO app_user(id,code,name,password_hash) VALUES(%s,%s,%s,%s)",
                (newid, code, body.get("name", code), password_hash(body["password"])),
            )
            s.audit(conn, c, "USER", newid, "CREATE", {"code": code})
            return {"id": newid, "code": code, "name": body.get("name", code)}
    if root == "members":
        if method == "GET":
            return [
                camel(x)
                for x in rows(
                    conn,
                    "SELECT m.*,u.code,u.name FROM membership m JOIN app_user u ON u.id=m.user_id WHERE m.tenant_id=%s ORDER BY u.code",
                    (c.tenant_id,),
                )
            ]
        if method in {"POST", "PATCH"}:
            s.object_body(body, {"userId", "roles", "categoryScope", "active"})
            uid = identifier(id or body.get("userId"))
            old = one(
                conn,
                "SELECT * FROM membership WHERE tenant_id=%s AND user_id=%s FOR UPDATE",
                (c.tenant_id, uid),
            )
            if old:
                s.expect_version(old, expected)
            roles = body.get("roles", old["roles"] if old else [])
            scope = body.get("categoryScope", old["category_scope"] if old else [])
            require(
                isinstance(roles, list)
                and isinstance(scope, list)
                and all(isinstance(x, str) for x in roles + scope),
                422,
                "MEMBERSHIP_CONFIGURATION",
                "roles/scope须为字符串数组",
            )
            available = {
                x["code"]
                for x in rows(
                    conn, "SELECT code FROM role WHERE tenant_id=%s", (c.tenant_id,)
                )
            }
            require(
                set(roles) <= available, 422, "MEMBERSHIP_CONFIGURATION", "角色不存在"
            )
            for code in scope:
                if code != "*":
                    s.category(conn, c, code)
            active = body.get("active", True)
            require(
                isinstance(active, bool),
                422,
                "MEMBERSHIP_CONFIGURATION",
                "active须为布尔值",
            )
            if old and "ADMIN" in old["roles"] and (not active or "ADMIN" not in roles):
                require(
                    one(
                        conn,
                        "SELECT 1 FROM membership WHERE tenant_id=%s AND user_id<>%s AND active AND roles @> '[\"ADMIN\"]' LIMIT 1",
                        (c.tenant_id, uid),
                    ),
                    409,
                    "LAST_ADMIN",
                    "不能移除最后一个管理员",
                )
            conn.execute(
                "INSERT INTO membership(tenant_id,user_id,roles,category_scope,active) VALUES(%s,%s,%s,%s,%s) ON CONFLICT(tenant_id,user_id) DO UPDATE SET roles=excluded.roles,category_scope=excluded.category_scope,active=excluded.active,row_version=membership.row_version+1",
                (c.tenant_id, uid, Jsonb(roles), Jsonb(scope), active),
            )
            s.audit(
                conn,
                c,
                "MEMBERSHIP",
                uid,
                "UPDATE",
                {"roles": roles, "categoryScope": scope, "active": active},
            )
            return camel(
                one(
                    conn,
                    "SELECT * FROM membership WHERE tenant_id=%s AND user_id=%s",
                    (c.tenant_id, uid),
                )
            )
    if root == "roles":
        if method == "GET":
            return [
                camel(x)
                for x in rows(
                    conn,
                    "SELECT * FROM role WHERE tenant_id=%s ORDER BY code",
                    (c.tenant_id,),
                )
            ]
        if method in {"POST", "PATCH"}:
            s.object_body(body, {"code", "permissions"})
            code = id or s.valid_code(body.get("code"))
            permissions = body.get("permissions")
            require(
                isinstance(permissions, list) and set(permissions) <= PERMISSIONS,
                422,
                "ROLE_CONFIGURATION",
                "权限代码无效",
            )
            require(code != "ADMIN", 409, "BUILTIN_ROLE", "管理员基础角色不可修改")
            old = one(
                conn,
                "SELECT * FROM role WHERE tenant_id=%s AND code=%s FOR UPDATE",
                (c.tenant_id, code),
            )
            if old:
                s.expect_version(old, expected)
            conn.execute(
                "INSERT INTO role(tenant_id,code,permissions) VALUES(%s,%s,%s) ON CONFLICT(tenant_id,code) DO UPDATE SET permissions=excluded.permissions,row_version=role.row_version+1",
                (c.tenant_id, code, Jsonb(permissions)),
            )
            s.audit(conn, c, "ROLE", code, "UPDATE", {"permissions": permissions})
            return camel(
                one(
                    conn,
                    "SELECT * FROM role WHERE tenant_id=%s AND code=%s",
                    (c.tenant_id, code),
                )
            )
    raise Problem(405, "METHOD_NOT_ALLOWED", "该管理资源不支持此操作")


def dispatch(conn, c, path, method, body, headers, query):
    parts = path.split("/")
    root = parts[0]
    id = parts[1] if len(parts) > 1 else None
    action = parts[2] if len(parts) > 2 else None
    expected = headers.get("if-match")
    if c.caller:
        require(
            root in {"material-number-assignments", "material-number-previews"},
            403,
            "CALLER_SCOPE",
            "Caller凭据只能使用发号、预览和自身查询接口",
        )
    if root == "context" and method == "GET":
        require(not c.caller, 403, "CALLER_SCOPE", "调用系统不能读取成员上下文")
        return {
            "tenantId": c.tenant_id,
            "userId": c.actor,
            "permissions": sorted(c.permissions),
            "categoryScope": sorted(c.scope),
            "tenantStatus": c.tenant_status,
        }
    if root in s.RESOURCES:
        kind, _ = s.RESOURCES[root]
        if method == "GET":
            if action == "diff":
                cfg = s.config(conn, c, id, kind)
                before = s.config(conn, c, query.get("against"), kind)
                require(
                    before["code"] == cfg["code"],
                    422,
                    "DIFF_CONFIGURATION",
                    "只能比较同一配置的版本",
                )
                return s.structural_diff(before["definition"], cfg["definition"])
            return (
                s.config_list(conn, c, kind)
                if id is None
                else camel(s.config(conn, c, id, kind))
            )
        if method == "POST" and id is None:
            return s.save_config(conn, c, root, body)
        if method == "PATCH" and id and not action:
            return s.save_config(conn, c, root, body, id, expected)
        if (
            method == "POST"
            and root == "categories"
            and action in {"suspend", "activate"}
        ):
            cfg = s.config(conn, c, id, "CATEGORY")
            c.check("SCHEMA_DESIGN", cfg["category_code"])
            cat = one(
                conn,
                "SELECT * FROM category WHERE id=%s FOR UPDATE",
                (cfg["category_id"],),
            )
            s.expect_version(cat, expected)
            conn.execute(
                "UPDATE category SET enabled=%s,row_version=row_version+1 WHERE id=%s",
                (action == "activate", cat["id"]),
            )
            s.audit(
                conn, c, "CATEGORY", cat["id"], action.upper(), category=cat["code"]
            )
            return camel(one(conn, "SELECT * FROM category WHERE id=%s", (cat["id"],)))
        if method == "GET" and action == "diff":
            cfg = s.config(conn, c, id, kind)
            before = s.config(conn, c, query["against"], kind)
            require(
                before["code"] == cfg["code"],
                422,
                "DIFF_CONFIGURATION",
                "只能比较同一配置的版本",
            )
            return s.structural_diff(before["definition"], cfg["definition"])
    if root == "caller-systems":
        if method == "GET":
            data = s.callers(conn, c)
            if id:
                found = next((x for x in data if str(x["id"]) == id), None)
                require(found, 404, "CALLER_SYSTEM_NOT_FOUND", "调用系统不存在")
                return found
            return data
        if method == "POST" and id is None:
            return s.save_caller(conn, c, body)
        if method == "PATCH" and id and not action:
            return s.save_caller(conn, c, body, id, expected)
        if method == "POST" and action == "rotate-key":
            return s.save_caller(conn, c, body, id, expected, True)
    if root == "release-packages":
        if method == "GET":
            require(
                bool(c.permissions & {"PREVIEW", "ASSIGNMENT_READ", "RELEASE_PUBLISH"}),
                403,
                "ACTION_FORBIDDEN",
                "没有发布包读取权限",
            )
            if id:
                rel = s.release(conn, c, id)
                if action == "diff":
                    return s.test_release(conn, c, rel, rel["dependency_snapshot"])[
                        "diff"
                    ]
                return camel(rel)
            return [
                camel(x)
                for x in rows(
                    conn,
                    "SELECT r.*,cat.code AS category_code FROM release_package r JOIN category cat ON cat.id=r.category_id WHERE r.tenant_id=%s ORDER BY r.created_at DESC",
                    (c.tenant_id,),
                )
                if "*" in c.scope or x["category_code"] in c.scope
            ]
        if method == "POST" and id is None:
            return s.save_release(conn, c, body)
        if method == "PATCH" and id and not action:
            return s.save_release(conn, c, body, id, expected)
        if method == "POST" and id and action:
            return s.release_action(conn, c, id, action, expected)
    if path == "material-number-previews" and method == "POST":
        return s.preview(conn, c, body)
    if root == "material-number-assignments":
        if method == "POST" and id is None:
            return s.issue(conn, c, body, headers.get("idempotency-key"))
        if method == "GET":
            if id is None:
                return s.search(conn, c, query)
            if id == "by-no":
                no = "/".join(parts[2:])
                found = one(
                    conn,
                    "SELECT id FROM material_assignment WHERE tenant_id=%s AND material_no=%s",
                    (c.tenant_id, no),
                )
                require(found, 404, "ASSIGNMENT_NOT_FOUND", "料号不存在")
                return s.explain(conn, c, found["id"])
            if id == "by-source":
                require(
                    len(parts) >= 4,
                    400,
                    "BAD_REQUEST",
                    "来源查询须提供callerSystemCode/sourceRecordKey",
                )
                caller_code = parts[2]
                key = "/".join(parts[3:])
                if c.caller:
                    require(
                        caller_code == c.caller["code"],
                        403,
                        "CALLER_SCOPE",
                        "不能查询其他调用方的来源键",
                    )
                result = s.search(
                    conn,
                    c,
                    {**query, "callerSystemCode": caller_code, "sourceRecordKey": key},
                )
                require(
                    result["total"] > 0, 404, "ASSIGNMENT_NOT_FOUND", "来源绑定不存在"
                )
                if result["total"] > 1:
                    require(
                        query.get("categoryCode"),
                        400,
                        "CATEGORY_REQUIRED",
                        "来源键跨类别重复，请指定categoryCode",
                    )
                return s.explain(conn, c, result["items"][0]["assignmentId"])
            return s.explain(conn, c, id)
    if root == "audit-logs" and method == "GET":
        c.check("AUDIT_READ")
        predicate = (
            ""
            if "*" in c.scope
            else " AND (category_code=ANY(%s) OR category_code IS NULL)"
        )
        args = (c.tenant_id,) + (() if "*" in c.scope else (list(c.scope),))
        return [
            camel(x)
            for x in rows(
                conn,
                "SELECT * FROM operation_log WHERE tenant_id=%s"
                + predicate
                + " ORDER BY created_at DESC LIMIT 200",
                args,
            )
        ]
    if root in {"overview", "monitoring"} and method == "GET":
        return s.monitoring(conn, c)
    if root in {"tenants", "members", "roles", "users"}:
        return tenant_routes(conn, c, root, parts, method, body, expected)
    raise Problem(
        405
        if root
        in set(s.RESOURCES)
        | {
            "caller-systems",
            "release-packages",
            "material-number-assignments",
            "material-number-previews",
        }
        else 404,
        "METHOD_NOT_ALLOWED"
        if root
        in set(s.RESOURCES)
        | {
            "caller-systems",
            "release-packages",
            "material-number-assignments",
            "material-number-previews",
        }
        else "NOT_FOUND",
        "接口或操作不存在",
    )


def handle(
    path, method, body, headers, cookie, query, raw_digest="", target="", remote=""
):
    started = time.perf_counter()
    request_id = headers.get("x-request-id") or str(uuid4())
    if len(request_id) > 100 or not re_safe(request_id):
        request_id = str(uuid4())
    c = None
    status = 200
    code = "OK"
    outcome = "UNKNOWN"
    try:
        with pool.connection() as conn:
            conn.execute("SET LOCAL statement_timeout = '30s'")
            if path == "health" and method == "GET":
                return response(
                    {"status": "UP", "version": "4.0.0"}, request_id=request_id
                )
            if path in {"auth/login", "dev/login"} and method == "POST":
                csrf(headers)
                bucket = one(
                    conn,
                    "INSERT INTO login_bucket(client_hash,minute,attempts) VALUES(%s,%s,1) ON CONFLICT(client_hash,minute) DO UPDATE SET attempts=login_bucket.attempts+1 RETURNING attempts",
                    (digest(remote), int(time.time() / 60)),
                )
                conn.commit()
                require(
                    bucket["attempts"] <= (60 if PRODUCTION else 1000),
                    429,
                    "RATE_LIMITED",
                    "登录尝试过于频繁，请稍后重试",
                )
                user, token = login(conn, body, path == "dev/login")
                result = response(user, request_id=request_id)
                result.set_cookie(
                    "mdm_session",
                    token,
                    httponly=True,
                    secure=PRODUCTION,
                    samesite="strict",
                    max_age=28800,
                    path="/",
                )
                return result
            if path == "auth/logout" and method == "POST":
                csrf(headers)
                conn.execute(
                    "DELETE FROM auth_session WHERE token_hash=%s",
                    (digest(cookie or ""),),
                )
                result = response({"loggedOut": True}, request_id=request_id)
                result.delete_cookie("mdm_session")
                return result
            if path == "dev/users" and method == "GET":
                require(not PRODUCTION, 404, "NOT_FOUND", "开发接口已关闭")
                return response(
                    [
                        camel(x)
                        for x in rows(
                            conn,
                            "SELECT id,code,name FROM app_user WHERE code IN ('admin','editor','reviewer','reader','auditor') ORDER BY code",
                        )
                    ],
                    request_id=request_id,
                )
            if path in {"me", "me/tenants"} and method == "GET":
                user = get_session_user(conn, cookie)
                if path == "me":
                    return response(camel(user), request_id=request_id)
                return response(
                    [
                        camel(x)
                        for x in rows(
                            conn,
                            "SELECT t.*,m.roles,m.category_scope FROM tenant t JOIN membership m ON m.tenant_id=t.id WHERE m.user_id=%s AND m.active ORDER BY t.code",
                            (user["id"],),
                        )
                    ],
                    request_id=request_id,
                )
            write = method != "GET" and path != "material-number-previews"
            if headers.get("x-caller-key") or headers.get("authorization"):
                c = caller_context(conn, headers, request_id, write)
                verify_signature(
                    conn, c, headers, method, target or "/api/v1/" + path, raw_digest
                )
            else:
                c = user_context(
                    conn,
                    headers,
                    cookie,
                    request_id,
                    write and path.split("/")[0] != "tenants",
                )
                if method != "GET":
                    csrf(headers)
            if c.caller and path in {
                "material-number-assignments",
                "material-number-previews",
            }:
                c.check("PREVIEW", body.get("categoryCode"))
                rate_limit(c, body, conn)
            value = dispatch(conn, c, path, method, body, headers, query)
            outcome = (
                value.get("status", "PREVIEWED") if isinstance(value, dict) else "OK"
            )
            return response(
                value,
                request_id=request_id,
                etag=value.get("rowVersion") if isinstance(value, dict) else None,
            )
    except Problem as p:
        status = p.status
        code = p.code
        return response(
            {
                "requestId": request_id,
                "code": p.code,
                "message": p.message,
                "details": p.details,
            },
            p.status,
            request_id,
        )
    except psycopg.errors.UniqueViolation:
        status = 409
        code = "UNIQUE_CONFLICT"
        return response(
            {
                "requestId": request_id,
                "code": code,
                "message": "唯一约束冲突，操作已回滚",
                "details": [],
            },
            status,
            request_id,
        )
    except psycopg.errors.ForeignKeyViolation:
        status = 422
        code = "CONFIGURATION_ERROR"
        return response(
            {
                "requestId": request_id,
                "code": code,
                "message": "依赖或成员不存在，操作已回滚",
                "details": [],
            },
            status,
            request_id,
        )
    except (ValueError, TypeError, KeyError):
        status = 400
        code = "BAD_REQUEST"
        log.warning("Invalid request shape: %s %s", path, request_id)
        return response(
            {
                "requestId": request_id,
                "code": code,
                "message": "请求字段或参数格式无效",
                "details": [],
            },
            status,
            request_id,
        )
    except (
        psycopg.OperationalError,
        PoolTimeout,
        psycopg.errors.QueryCanceled,
        psycopg.errors.DeadlockDetected,
    ):
        status = 503
        code = "TEMPORARILY_UNAVAILABLE"
        return response(
            {
                "requestId": request_id,
                "code": code,
                "message": "暂时不可用，请使用原幂等键重试",
                "details": [],
            },
            status,
            request_id,
        )
    except Exception:
        status = 500
        code = "INTERNAL_ERROR"
        # Do not log SQL parameters, raw input, tokens or credentials.
        log.error("Internal API error: %s %s", path, request_id)
        return response(
            {
                "requestId": request_id,
                "code": code,
                "message": "内部错误，请凭requestId查询",
                "details": [],
            },
            status,
            request_id,
        )
    finally:
        metric(c, path, body, status, code, started, outcome)


def re_safe(value):
    import re

    return bool(re.fullmatch("[A-Za-z0-9_.:-]+", value))


@app.api_route("/api/v1/{path:path}", methods=["GET", "POST", "PATCH", "DELETE", "PUT"])
async def api(request: Request, path: str):
    request_id = str(uuid4())
    if PRODUCTION and request.url.scheme != "https" and path != "health":
        return response(
            {
                "requestId": request_id,
                "code": "HTTPS_REQUIRED",
                "message": "生产接口必须通过HTTPS访问",
                "details": [],
            },
            403,
            request_id,
        )
    body = {}
    try:
        data = bytearray()
        async for chunk in request.stream():
            data.extend(chunk)
            require(len(data) <= 1048576, 413, "PAYLOAD_TOO_LARGE", "请求体超过1MB")
        if data:
            require(
                request.headers.get("content-type", "").split(";")[0]
                == "application/json",
                415,
                "CONTENT_TYPE",
                "请求体须为application/json",
            )
            try:
                body = loads(bytes(data))
            except (ValueError, UnicodeError, RecursionError):
                raise Problem(400, "BAD_JSON", "JSON格式无效")
            require(isinstance(body, dict), 400, "BAD_REQUEST", "请求体须为JSON对象")
    except Problem as p:
        return response(
            {
                "requestId": request_id,
                "code": p.code,
                "message": p.message,
                "details": p.details,
            },
            p.status,
            request_id,
        )
    return await run_in_threadpool(
        handle,
        path,
        request.method,
        body,
        dict(request.headers),
        request.cookies.get("mdm_session"),
        dict(request.query_params),
        digest(bytes(data).decode("utf-8")),
        request.url.path + ("?" + request.url.query if request.url.query else ""),
        request.client.host if request.client else "",
    )


@app.get("/api/openapi.json")
def openapi_contract():
    from .contract import document

    return response(document())


@app.get("/api/docs")
def api_docs():
    from fastapi.openapi.docs import get_swagger_ui_html

    return get_swagger_ui_html(
        openapi_url="/api/openapi.json", title="V4 Assignment API"
    )


# Production builds can serve the Vue application from the same HTTPS origin.
from pathlib import Path
from fastapi.staticfiles import StaticFiles

static_dir = Path(
    os.getenv("STATIC_DIR", str(Path(__file__).resolve().parents[2] / "frontend/dist"))
)
if static_dir.is_dir():
    app.mount("/", StaticFiles(directory=static_dir, html=True), name="workbench")
