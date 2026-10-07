"""Initial production administrator, entered interactively; no demo data or credentials."""

import getpass
import os
import sys
from pathlib import Path
from uuid import uuid4

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
from app.db import pool, migrate, one, Jsonb
from app.common import password_hash
from app.security import install_roles
from app.service import valid_code

if not os.getenv("DATABASE_URL"):
    raise SystemExit("Set DATABASE_URL to the intended production database first.")
code = valid_code(input("管理员用户代码: ").strip())
name = input("管理员姓名: ").strip()
tenant_code = valid_code(input("初始租户代码: ").strip())
tenant_name = input("租户名称: ").strip()
password = getpass.getpass("初始密码（至少12字符）: ")
if len(password) < 12 or password != getpass.getpass("重复密码: "):
    raise SystemExit("密码长度不足或两次不一致。")
pool.open()
pool.wait()
migrate()
with pool.connection() as conn:
    if one(conn, "SELECT 1 FROM app_user LIMIT 1"):
        raise SystemExit("已有用户。该命令只用于空数据库的初次引导。")
    uid, tid = uuid4(), uuid4()
    conn.execute(
        "INSERT INTO app_user(id,code,name,password_hash,platform_admin) VALUES(%s,%s,%s,%s,true)",
        (uid, code, name, password_hash(password)),
    )
    conn.execute(
        "INSERT INTO tenant(id,code,name,status) VALUES(%s,%s,%s,'ACTIVE')",
        (tid, tenant_code, tenant_name),
    )
    install_roles(conn, tid)
    conn.execute(
        "INSERT INTO membership(tenant_id,user_id,roles) VALUES(%s,%s,%s)",
        (tid, uid, Jsonb(["ADMIN"])),
    )
pool.close()
print("管理员和租户已创建。")
