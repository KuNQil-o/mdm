import hashlib
import secrets
from datetime import date, datetime
from decimal import Decimal, localcontext
from uuid import UUID
import simplejson


class Problem(Exception):
    def __init__(self, status, code, message, details=None):
        self.status, self.code, self.message = status, code, message
        self.details = details or []


def require(condition, status, code, message, details=None):
    if not condition:
        raise Problem(status, code, message, details)


def dumps(value, **kw):
    return simplejson.dumps(
        value,
        ensure_ascii=False,
        use_decimal=True,
        default=lambda v: v.isoformat() if isinstance(v, (date, datetime)) else str(v),
        **kw,
    )


def loads(raw):
    return simplejson.loads(raw, use_decimal=True)


def canonical(value):
    # Decimal spellings (1, 1.0, 1e0) have identical request semantics.
    if isinstance(value, Decimal):
        with localcontext() as ctx:
            ctx.prec = max(110, len(value.as_tuple().digits))
            return (
                int(value) if value == value.to_integral_value() else value.normalize()
            )
    if isinstance(value, dict):
        return {k: canonical(v) for k, v in sorted(value.items())}
    if isinstance(value, list):
        return [canonical(v) for v in value]
    return value


def digest(value):
    return hashlib.sha256(value.encode()).hexdigest()


def identifier(value):
    try:
        return UUID(str(value))
    except (ValueError, TypeError):
        raise Problem(400, "INVALID_ID", "版本或记录 ID 无效")


def password_hash(password, salt=None):
    salt = salt or secrets.token_hex(16)
    encoded = hashlib.pbkdf2_hmac(
        "sha256", password.encode(), salt.encode(), 310000
    ).hex()
    return f"pbkdf2-sha256${salt}${encoded}"


def password_matches(password, stored):
    try:
        _, salt, _ = stored.split("$")
        return secrets.compare_digest(password_hash(password, salt), stored)
    except (ValueError, TypeError):
        return False


def camel(row):
    if row is None:
        return None
    return {
        k.split("_")[0] + "".join(x.title() for x in k.split("_")[1:]): v
        for k, v in row.items()
    }
