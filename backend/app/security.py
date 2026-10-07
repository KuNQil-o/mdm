import os
import secrets
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from .common import (
    Problem,
    require,
    digest,
    password_matches,
    password_hash,
    identifier,
    camel,
)
from .db import one, rows, Jsonb

PERMISSIONS = {
    "CALLER_MANAGE",
    "SCHEMA_DESIGN",
    "INPUT_DESIGN",
    "VALIDATION_DESIGN",
    "DERIVATION_DESIGN",
    "IDENTITY_DESIGN",
    "CODE_DESIGN",
    "RELEASE_SUBMIT",
    "RELEASE_PUBLISH",
    "PREVIEW",
    "ASSIGNMENT_READ",
    "AUDIT_READ",
    "MONITOR_READ",
    "TENANT_ADMIN",
    "DICTIONARY_DESIGN",
}
DEFAULT_ROLES = {
    "ADMIN": sorted(PERMISSIONS),
    "DESIGNER": [
        "SCHEMA_DESIGN",
        "INPUT_DESIGN",
        "VALIDATION_DESIGN",
        "DERIVATION_DESIGN",
        "IDENTITY_DESIGN",
        "CODE_DESIGN",
        "DICTIONARY_DESIGN",
        "RELEASE_SUBMIT",
        "PREVIEW",
        "ASSIGNMENT_READ",
        "MONITOR_READ",
    ],
    "APPROVER": [
        "RELEASE_PUBLISH",
        "PREVIEW",
        "ASSIGNMENT_READ",
        "AUDIT_READ",
        "MONITOR_READ",
    ],
    "INTEGRATOR": ["CALLER_MANAGE", "PREVIEW", "ASSIGNMENT_READ", "MONITOR_READ"],
    "READER": ["ASSIGNMENT_READ", "PREVIEW"],
    "AUDITOR": ["AUDIT_READ", "ASSIGNMENT_READ", "MONITOR_READ"],
}


@dataclass
class Context:
    tenant_id: object
    actor: str
    permissions: set
    scope: set
    request_id: str
    tenant_status: str = "ACTIVE"
    caller: dict | None = None
    platform_admin: bool = False

    def check(self, action, category=None):
        require(
            action in self.permissions,
            403,
            "ACTION_FORBIDDEN",
            "当前成员没有此操作权限",
        )
        if category:
            require(
                "*" in self.scope or category in self.scope,
                403,
                "CATEGORY_FORBIDDEN",
                "类别不在授权范围",
            )

    def active(self):
        require(
            self.tenant_status == "ACTIVE",
            409,
            "TENANT_SUSPENDED",
            "租户未启用或已暂停",
        )


def tenant(conn, code):
    t = one(conn, "SELECT * FROM tenant WHERE code=%s", (code,))
    require(t is not None, 404, "TENANT_NOT_FOUND", "租户不存在")
    return t


def user_context(conn, headers, cookie, request_id, write=False):
    session = one(
        conn,
        "SELECT u.* FROM auth_session s JOIN app_user u ON u.id=s.user_id WHERE token_hash=%s AND expires_at>now()",
        (digest(cookie or ""),),
    )
    require(session is not None, 401, "LOGIN_REQUIRED", "请先登录")
    t = tenant(conn, headers.get("x-tenant-code", ""))
    member = one(
        conn,
        "SELECT * FROM membership WHERE tenant_id=%s AND user_id=%s AND active",
        (t["id"], session["id"]),
    )
    require(member is not None, 403, "MEMBERSHIP_REQUIRED", "不是当前租户的有效成员")
    permissions = set()
    for r in rows(
        conn,
        "SELECT permissions FROM role WHERE tenant_id=%s AND code=ANY(%s)",
        (t["id"], member["roles"]),
    ):
        permissions.update(r["permissions"])
    c = Context(
        t["id"],
        str(session["id"]),
        permissions,
        set(member["category_scope"]),
        request_id,
        t["status"],
        platform_admin=session["platform_admin"],
    )
    if write:
        c.active()
    return c


def caller_context(conn, headers, request_id, write=False):
    t = tenant(conn, headers.get("x-tenant-code", ""))
    key = headers.get("x-caller-key")
    if not key and headers.get("authorization", "").startswith("Bearer "):
        key = headers["authorization"][7:]
    require(
        key and len(key) <= 200, 401, "CALLER_AUTH_FAILED", "必须提供 Caller API Key"
    )
    caller = one(
        conn,
        "SELECT * FROM caller_system WHERE tenant_id=%s AND credential_hash=%s",
        (t["id"], digest(key)),
    )
    require(caller is not None, 401, "CALLER_AUTH_FAILED", "凭据与当前租户不匹配")
    require(
        caller["status"] == "ACTIVE",
        403,
        "CALLER_SYSTEM_SUSPENDED",
        "调用系统未启用或已暂停",
    )
    c = Context(
        t["id"],
        str(caller["id"]),
        {"PREVIEW", "ASSIGNMENT_READ"},
        set(caller["allowed_category_codes"]),
        request_id,
        t["status"],
        caller=caller,
    )
    if write:
        c.active()
    return c


def login(conn, body, dev=False):
    user = one(
        conn, "SELECT * FROM app_user WHERE code=%s", (body.get("userCode", ""),)
    )
    if dev:
        require(
            os.getenv("APP_ENV", "development") == "development",
            404,
            "NOT_FOUND",
            "开发登录已关闭",
        )
        require(
            body.get("userCode")
            in {"admin", "editor", "reviewer", "reader", "auditor"},
            401,
            "AUTH_FAILED",
            "演示用户无效",
        )
    else:
        require(
            len(str(body.get("password", ""))) <= 256, 400, "BAD_REQUEST", "密码过长"
        )
        # Fixed-cost hash even for nonexistent users.
        require(
            password_matches(
                str(body.get("password", "")),
                user["password_hash"]
                if user
                else password_hash("not-a-user", "dummy-fixed-salt"),
            ),
            401,
            "AUTH_FAILED",
            "用户名或密码错误",
        )
    require(user is not None, 401, "AUTH_FAILED", "用户不存在")
    token = secrets.token_urlsafe(48)
    conn.execute(
        "INSERT INTO auth_session(token_hash,user_id,expires_at) VALUES(%s,%s,%s)",
        (digest(token), user["id"], datetime.now(timezone.utc) + timedelta(hours=8)),
    )
    return {
        "id": user["id"],
        "code": user["code"],
        "name": user["name"],
        "platformAdmin": user["platform_admin"],
    }, token


def install_roles(conn, tid):
    for code, permissions in DEFAULT_ROLES.items():
        conn.execute(
            "INSERT INTO role(tenant_id,code,permissions) VALUES(%s,%s,%s) ON CONFLICT DO NOTHING",
            (tid, code, Jsonb(permissions)),
        )
