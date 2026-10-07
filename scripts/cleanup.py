"""Retention of transient auth/replay/rate data only. Never touches ledger, sequence or audit."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "backend"))
from app.db import pool

pool.open()
pool.wait()
with pool.connection() as conn:
    conn.execute("DELETE FROM auth_session WHERE expires_at<now()")
    conn.execute("DELETE FROM caller_nonce WHERE created_at<now()-interval '1 day'")
    conn.execute(
        "DELETE FROM rate_bucket WHERE minute<floor(extract(epoch from now())/60)-1440"
    )
    conn.execute(
        "DELETE FROM login_bucket WHERE minute<floor(extract(epoch from now())/60)-1440"
    )
pool.close()
print("Transient records cleaned; immutable facts retained.")
