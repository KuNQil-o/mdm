import os
from pathlib import Path
from psycopg.rows import dict_row
from psycopg.types.json import Jsonb, set_json_dumps, set_json_loads
from psycopg_pool import ConnectionPool
from .common import dumps, loads

set_json_dumps(dumps)
set_json_loads(loads)
pool = ConnectionPool(
    os.getenv("DATABASE_URL", "postgresql://mdm:mdm_v4_dev_only@localhost:5433/mdm_v4"),
    min_size=2,
    max_size=24,
    open=False,
    kwargs={"row_factory": dict_row},
    timeout=30,
)


def one(conn, sql, params=()):
    return conn.execute(sql, params).fetchone()


def rows(conn, sql, params=()):
    return conn.execute(sql, params).fetchall()


def lock(conn, key):
    conn.execute("SELECT pg_advisory_xact_lock(hashtextextended(%s,0))", (str(key),))


def migrate():
    with pool.connection() as conn:
        lock(conn, "mdm-v4-migration")
        conn.execute(
            "CREATE TABLE IF NOT EXISTS migration_history(version text PRIMARY KEY, checksum text NOT NULL, applied_at timestamptz DEFAULT now())"
        )
        from .common import digest

        for path in sorted(
            (Path(__file__).resolve().parents[1] / "migrations").glob("*.sql")
        ):
            sql = path.read_text()
            old = one(
                conn,
                "SELECT checksum FROM migration_history WHERE version=%s",
                (path.name,),
            )
            if old:
                if old["checksum"] != digest(sql):
                    raise RuntimeError("Applied migration changed: " + path.name)
            else:
                conn.execute(sql)
                conn.execute(
                    "INSERT INTO migration_history(version,checksum) VALUES(%s,%s)",
                    (path.name, digest(sql)),
                )
