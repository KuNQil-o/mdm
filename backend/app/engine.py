"""Pure, deterministic metadata engine. No ERP, database or industry dependencies."""

import re
from copy import deepcopy
from datetime import date, datetime
from decimal import Decimal, InvalidOperation, localcontext
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
from .common import Problem, require, dumps, digest

TYPES = {"STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "ENUM", "REFERENCE"}
SEGMENTS = {
    "CONSTANT",
    "ATTRIBUTE",
    "DICTIONARY",
    "REFERENCE_CODE",
    "NUMBER_FORMAT",
    "UNIT_FORMAT",
    "SEQUENCE",
    "SEPARATOR",
    "PERIOD",
}
OPS = {
    "exists",
    "eq",
    "ne",
    "gt",
    "gte",
    "lt",
    "lte",
    "in",
    "not_in",
    "and",
    "or",
    "not",
    "add",
    "sub",
    "mul",
    "div",
    "concat",
    "coalesce",
    "if",
    "length",
    "matches",
    "convert",
    "upper",
    "lower",
    "trim",
}


def pattern(value):
    # Deliberately bounded regular subset: no groups, backreferences, lookarounds or repeated wildcards.
    require(
        isinstance(value, str)
        and len(value) <= 256
        and not any(x in value for x in ["(", ")", "\\1", "\\2"])
        and not re.search(r"[+*].*[+*]", value),
        422,
        "CONFIGURATION_ERROR",
        "模式只支持基础、无分组的受控正则",
    )
    try:
        return re.compile(value)
    except re.error:
        raise Problem(422, "CONFIGURATION_ERROR", "正则模式无效")


def number(value):
    require(not isinstance(value, bool), 422, "TYPE_NUMBER", "布尔值不能作为数值")
    try:
        result = Decimal(str(value))
        require(
            result.is_finite()
            and abs(result.adjusted()) <= 100
            and len(result.as_tuple().digits) <= 100,
            422,
            "PRECISION_OVERFLOW",
            "数值必须有限且在精度预算内",
        )
        return result
    except (InvalidOperation, ValueError, TypeError):
        raise Problem(422, "TYPE_NUMBER", "请输入十进制数值")


def scalar(value):
    return value.get("value") if isinstance(value, dict) and "value" in value else value


def get(attrs, path):
    value = attrs
    for part in path.split("."):
        value = value.get(part) if isinstance(value, dict) else None
    return scalar(value)


def dependencies(ast):
    if isinstance(ast, dict):
        result = {ast["field"].split(".")[0]} if "field" in ast else set()
        for value in ast.values():
            result |= dependencies(value)
        return result
    if isinstance(ast, list):
        return set().union(*(dependencies(v) for v in ast)) if ast else set()
    return set()


def ast_check(ast, fields, depth=0):
    require(depth <= 20, 422, "CONFIGURATION_ERROR", "表达式深度超过20")
    if not isinstance(ast, dict):
        require(
            ast is not None and not isinstance(ast, list),
            422,
            "CONFIGURATION_ERROR",
            "数组常量须使用 literal",
        )
        return 1
    require(
        set(ast) <= {"field", "literal", "op", "args"},
        422,
        "CONFIGURATION_ERROR",
        "表达式含不支持字段",
    )
    if "field" in ast:
        require(
            len(ast) == 1
            and isinstance(ast["field"], str)
            and ast["field"].split(".")[0] in fields,
            422,
            "CONFIGURATION_ERROR",
            "表达式引用未知属性",
        )
        return 1
    if "literal" in ast:
        require(
            len(ast) == 1, 422, "CONFIGURATION_ERROR", "literal 不能与其他表达式组合"
        )
        return 1
    op, args = ast.get("op"), ast.get("args", [])
    require(
        op in OPS and isinstance(args, list), 422, "CONFIGURATION_ERROR", "未登记运算符"
    )
    arities = {
        "exists": 1,
        "not": 1,
        "length": 1,
        "upper": 1,
        "lower": 1,
        "trim": 1,
        "if": 3,
        "convert": 3,
    }
    if op in arities:
        require(
            len(args) == arities[op], 422, "CONFIGURATION_ERROR", "表达式参数数量错误"
        )
    elif op in {"and", "or", "concat", "coalesce"}:
        require(1 <= len(args) <= 30, 422, "CONFIGURATION_ERROR", "表达式参数数量错误")
    else:
        require(len(args) == 2, 422, "CONFIGURATION_ERROR", "表达式参数数量错误")
    if op == "matches":
        require(
            isinstance(args[1], (str, dict)),
            422,
            "CONFIGURATION_ERROR",
            "matches 需要固定模式",
        )
        p = args[1].get("literal") if isinstance(args[1], dict) else args[1]
        pattern(p)
    count = 1 + sum(ast_check(x, fields, depth + 1) for x in args)
    require(count <= 200, 422, "CONFIGURATION_ERROR", "表达式超过200节点")
    return count


def convert(value, source, target, units):
    require(
        source in units
        and target in units
        and units[source].get("dimension") == units[target].get("dimension"),
        422,
        "UNIT_DIMENSION",
        "单位不存在或量纲不一致",
    )
    f, t = units[source], units[target]
    with localcontext() as ctx:
        ctx.prec = 110
        ctx.traps[InvalidOperation] = True
        # An exact conversion is required; caller must choose finite decimal conversion factors.
        from decimal import Inexact, DivisionByZero

        ctx.traps[Inexact] = True
        ctx.traps[DivisionByZero] = True
        try:
            return (
                number(value) * number(f.get("factor", 1))
                + number(f.get("offset", 0))
                - number(t.get("offset", 0))
            ) / number(t.get("factor", 1))
        except (Inexact, DivisionByZero, InvalidOperation):
            raise Problem(422, "UNIT_CONVERSION_ERROR", "单位换算不能精确表示")


def evaluate(ast, attrs, units):
    if not isinstance(ast, dict):
        return ast
    if "field" in ast:
        return get(attrs, ast["field"])
    if "literal" in ast:
        return ast["literal"]
    op, args = ast["op"], ast["args"]
    if op == "if":
        return evaluate(
            args[1] if evaluate(args[0], attrs, units) else args[2], attrs, units
        )
    if op == "coalesce":
        for arg in args:
            value = evaluate(arg, attrs, units)
            if value is not None:
                return value
        return None
    values = [evaluate(x, attrs, units) for x in args]
    x = values[0]
    y = values[1] if len(values) > 1 else None
    if op == "exists":
        return x is not None
    if op == "and":
        return all(values)
    if op == "or":
        return any(values)
    if op == "not":
        return not x
    if op == "eq":
        return x == y
    if op == "ne":
        return x != y
    if op in {"in", "not_in"}:
        require(isinstance(y, list), 422, "RULE_TYPE", "in 需要数组常量")
        return (x in y) if op == "in" else (x not in y)
    require(
        all(v is not None for v in values), 422, "DEPENDENCY_MISSING", "表达式依赖为空"
    )
    if op in {"gt", "gte", "lt", "lte"}:
        try:
            return {
                "gt": lambda: x > y,
                "gte": lambda: x >= y,
                "lt": lambda: x < y,
                "lte": lambda: x <= y,
            }[op]()
        except TypeError:
            raise Problem(422, "RULE_TYPE", "比较属性类型不一致")
    if op in {"add", "sub", "mul", "div"}:
        from decimal import Inexact, DivisionByZero

        with localcontext() as ctx:
            ctx.prec = 110
            ctx.traps[Inexact] = True
            try:
                x, y = number(x), number(y)
                return {
                    "add": lambda: x + y,
                    "sub": lambda: x - y,
                    "mul": lambda: x * y,
                    "div": lambda: x / y,
                }[op]()
            except (Inexact, DivisionByZero, InvalidOperation):
                raise Problem(422, "ARITHMETIC_ERROR", "派生计算除零或超出精度")
    if op == "convert":
        return convert(x, y, values[2], units)
    if op == "concat":
        return "".join(text(v) for v in values)
    if op == "length":
        return len(str(x))
    if op == "matches":
        return bool(pattern(y).fullmatch(str(x)))
    if op == "upper":
        return str(x).upper()
    if op == "lower":
        return str(x).lower()
    if op == "trim":
        return str(x).strip()
    raise Problem(422, "CONFIGURATION_ERROR", "未知运算符")


def exact_normalize(value):
    with localcontext() as ctx:
        ctx.prec = 110
        return value.normalize()


def text(value):
    if isinstance(value, Decimal):
        return format(exact_normalize(value), "f")
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, dict):
        return str(value.get("code", value.get("id", "")))
    return str(value)


def derive_order(fields):
    visiting, done, result = set(), set(), []

    def visit(key):
        if key in done:
            return
        require(key not in visiting, 422, "CONFIGURATION_ERROR", "派生属性循环依赖")
        visiting.add(key)
        if "derived" in fields[key]:
            for dep in sorted(dependencies(fields[key]["derived"])):
                require(dep in fields, 422, "CONFIGURATION_ERROR", "派生引用未知属性")
                visit(dep)
            result.append(key)
        visiting.remove(key)
        done.add(key)

    for key in fields:
        visit(key)
    return result


def compile_bundle(bundle):
    schema = bundle["schema"]["definition"]
    attributes = deepcopy(schema.get("attributes", []))
    require(
        isinstance(attributes, list) and 1 <= len(attributes) <= 200,
        422,
        "CONFIGURATION_ERROR",
        "Schema须有1至200个属性",
    )
    units = schema.get("units", {})
    require(
        isinstance(units, dict) and len(units) <= 100,
        422,
        "CONFIGURATION_ERROR",
        "单位配置无效",
    )
    for unit, item in units.items():
        require(
            isinstance(item, dict)
            and isinstance(item.get("dimension"), str)
            and item["dimension"],
            422,
            "CONFIGURATION_ERROR",
            "单位须有量纲",
        )
        require(
            number(item.get("factor", 1)) > 0,
            422,
            "CONFIGURATION_ERROR",
            "单位换算系数须为正值",
        )
        number(item.get("offset", 0))
    fields = {}
    for field in attributes:
        require(isinstance(field, dict), 422, "CONFIGURATION_ERROR", "属性定义须为对象")
        require(
            set(field)
            <= {
                "attributeCode",
                "code",
                "name",
                "type",
                "required",
                "length",
                "precision",
                "scale",
                "min",
                "max",
                "unit",
                "enumDictionaryVersionId",
                "referenceDictionaryVersionId",
                "referenceType",
                "options",
                "searchable",
                "displayOrder",
                "trim",
                "case",
                "emptyToNull",
                "minLength",
                "pattern",
                "default",
            },
            422,
            "CONFIGURATION_ERROR",
            "Schema属性含不支持字段",
        )
        key = field.get("attributeCode", field.get("code", ""))
        require(
            isinstance(key, str)
            and re.fullmatch("[A-Za-z][A-Za-z0-9_]{0,79}", key)
            and key not in fields,
            422,
            "CONFIGURATION_ERROR",
            "属性代码无效或重复",
        )
        require(field.get("type") in TYPES, 422, "CONFIGURATION_ERROR", "属性类型无效")
        field["attributeCode"] = key
        fields[key] = field
        require(
            field.get("case", "PRESERVE") in {"PRESERVE", "UPPER", "LOWER"},
            422,
            "CONFIGURATION_ERROR",
            "大小写策略无效",
        )
        if "pattern" in field:
            pattern(field["pattern"])
        for flag in ["required", "searchable", "trim", "emptyToNull"]:
            require(
                flag not in field or isinstance(field[flag], bool),
                422,
                "CONFIGURATION_ERROR",
                "属性标记须为布尔值",
            )
        if "min" in field:
            number(field["min"])
        if "max" in field:
            number(field["max"])
        if "min" in field and "max" in field:
            require(
                number(field["min"]) <= number(field["max"]),
                422,
                "CONFIGURATION_ERROR",
                "数值范围反向",
            )
        for opt, default, maximum in [
            ("length", 1000, 10000),
            ("precision", 38, 100),
            ("scale", 12, 100),
        ]:
            require(
                isinstance(field.get(opt, default), int)
                and 0 <= field.get(opt, default) <= maximum,
                422,
                "CONFIGURATION_ERROR",
                "属性长度或精度配置无效",
            )
        if field.get("unit"):
            require(
                field["unit"] in units, 422, "CONFIGURATION_ERROR", "属性标准单位未定义"
            )
        if field.get("type") in {"ENUM", "REFERENCE"}:
            entries_for(field, bundle)
    for rule in bundle.get("derivations", []):
        for item in rule["definition"].get("attributes", []):
            require(
                item.get("field") in fields and "derived" not in fields[item["field"]],
                422,
                "CONFIGURATION_ERROR",
                "派生目标未知或重复",
            )
            fields[item["field"]]["derived"] = item["expression"]
    for field in fields.values():
        if "derived" in field:
            ast_check(field["derived"], fields)
    derive_order(fields)
    for rule in bundle.get("validations", []):
        for item in rule["definition"].get("rules", []):
            require(
                item.get("severity", "ERROR") in {"ERROR", "WARNING"},
                422,
                "CONFIGURATION_ERROR",
                "校验级别无效",
            )
            ast_check(item.get("assert"), fields)
    identity = bundle["identity"]["definition"].get("attributes", [])
    require(
        isinstance(identity, list) and len(identity) > 0,
        422,
        "CONFIGURATION_ERROR",
        "必须定义Identity属性",
    )
    seen = set()
    for attr in identity:
        require(
            isinstance(attr, (str, dict)),
            422,
            "CONFIGURATION_ERROR",
            "Identity属性配置无效",
        )
        attr = {"field": attr} if isinstance(attr, str) else attr
        require(
            attr.get("field") in fields and attr["field"] not in seen,
            422,
            "CONFIGURATION_ERROR",
            "Identity引用未知或重复属性",
        )
        seen.add(attr["field"])
        require(
            attr.get("nullPolicy", "ERROR") in {"ERROR", "ALLOW"},
            422,
            "CONFIGURATION_ERROR",
            "Identity空值策略无效",
        )
        require(
            attr.get("case", "PRESERVE") in {"PRESERVE", "UPPER", "LOWER"},
            422,
            "CONFIGURATION_ERROR",
            "Identity大小写策略无效",
        )
        if "scale" in attr:
            require(
                isinstance(attr["scale"], int) and 0 <= attr["scale"] <= 100,
                422,
                "CONFIGURATION_ERROR",
                "Identity精度无效",
            )
    rule = bundle["codeRule"]["definition"]
    segments = rule.get("segments", [])
    require(
        isinstance(segments, list) and 1 <= len(segments) <= 50,
        422,
        "CONFIGURATION_ERROR",
        "编码须有1至50个Segment",
    )
    require(
        isinstance(rule.get("maxLength", 128), int)
        and 1 <= rule.get("maxLength", 128) <= 128,
        422,
        "CONFIGURATION_ERROR",
        "料号长度无效",
    )
    require(
        rule.get("case", "PRESERVE") in {"PRESERVE", "UPPER", "LOWER"},
        422,
        "CONFIGURATION_ERROR",
        "编码大小写策略无效",
    )
    if "allowedPattern" in rule:
        pattern(rule["allowedPattern"])
    sequences = set()
    for seg in segments:
        require(isinstance(seg, dict), 422, "CONFIGURATION_ERROR", "Segment须为对象")
        require(
            set(seg)
            <= {
                "type",
                "value",
                "field",
                "map",
                "dictionaryVersionId",
                "scale",
                "unit",
                "includeUnit",
                "padding",
                "padChar",
                "nullPolicy",
                "default",
                "name",
                "width",
                "reset",
                "timezone",
            },
            422,
            "CONFIGURATION_ERROR",
            "Segment含不支持字段",
        )
        if "scale" in seg:
            require(
                isinstance(seg["scale"], int) and 0 <= seg["scale"] <= 100,
                422,
                "CONFIGURATION_ERROR",
                "Segment精度无效",
            )
        require(
            isinstance(seg.get("padChar", "0"), str)
            and len(seg.get("padChar", "0")) == 1,
            422,
            "CONFIGURATION_ERROR",
            "填充字符须为单字符",
        )
        require(
            seg.get("nullPolicy", "ERROR") in {"ERROR", "EMPTY", "DEFAULT"},
            422,
            "CONFIGURATION_ERROR",
            "Segment空值策略无效",
        )
        require(
            seg.get("type") in SEGMENTS, 422, "CONFIGURATION_ERROR", "Segment类型无效"
        )
        if seg["type"] not in {"CONSTANT", "SEPARATOR", "SEQUENCE", "PERIOD"}:
            require(
                seg.get("field", "").split(".")[0] in fields,
                422,
                "CONFIGURATION_ERROR",
                "编码引用未知属性",
            )
        if seg["type"] == "DICTIONARY" and "dictionaryVersionId" in seg:
            require(
                seg["dictionaryVersionId"] in bundle.get("dictionaries", {}),
                422,
                "CONFIGURATION_ERROR",
                "编码字典版本缺失",
            )
        if seg["type"] == "SEQUENCE":
            name = seg.get("name", "MAIN")
            require(
                name not in sequences,
                422,
                "CONFIGURATION_ERROR",
                "每个流水只能出现一次",
            )
            sequences.add(name)
            require(
                seg.get("reset", "NEVER") in {"NEVER", "DAILY", "MONTHLY", "YEARLY"},
                422,
                "CONFIGURATION_ERROR",
                "流水周期无效",
            )
            require(
                isinstance(seg.get("width", 6), int) and 1 <= seg.get("width", 6) <= 18,
                422,
                "CONFIGURATION_ERROR",
                "流水宽度无效",
            )
            if seg.get("reset", "NEVER") != "NEVER":
                require(
                    any(
                        s.get("type") == "PERIOD"
                        and s.get("reset", "YEARLY") == seg["reset"]
                        and s.get("timezone", "UTC") == seg.get("timezone", "UTC")
                        for s in segments
                    ),
                    422,
                    "CONFIGURATION_ERROR",
                    "周期流水须编码同周期、同一时区的日期",
                )
        if seg.get("type") in {"SEQUENCE", "PERIOD"}:
            try:
                ZoneInfo(seg.get("timezone", "UTC"))
            except (ZoneInfoNotFoundError, ValueError):
                raise Problem(422, "CONFIGURATION_ERROR", "时区无效")
        require(
            isinstance(seg.get("padding", 0), int)
            and 0 <= seg.get("padding", 0) <= 128,
            422,
            "CONFIGURATION_ERROR",
            "padding无效",
        )
    for profile in bundle.get("inputProfiles", []):
        rules = profile["definition"].get("fields", [])
        targets = set()
        for item in rules:
            target = item.get("target")
            require(
                target in fields
                and target not in targets
                and "derived" not in fields[target],
                422,
                "CONFIGURATION_ERROR",
                "映射目标未知、重复或是派生字段",
            )
            targets.add(target)
            require(
                set(item)
                <= {
                    "target",
                    "source",
                    "constant",
                    "map",
                    "case",
                    "trim",
                    "prefix",
                    "suffix",
                    "dictionaryVersionId",
                },
                422,
                "CONFIGURATION_ERROR",
                "Input Profile只允许轻量映射",
            )
            require(
                ("source" in item) != ("constant" in item),
                422,
                "CONFIGURATION_ERROR",
                "映射须有source或constant其中之一",
            )
            if "dictionaryVersionId" in item:
                require(
                    item["dictionaryVersionId"] in bundle.get("dictionaries", {}),
                    422,
                    "CONFIGURATION_ERROR",
                    "映射字典版本缺失",
                )
    policy = bundle["category"]["definition"].get(
        "identityReusePolicy", "REUSE_EXISTING"
    )
    require(
        policy in {"REUSE_EXISTING", "REVIEW_ON_DUPLICATE"},
        422,
        "CONFIGURATION_ERROR",
        "Identity复用策略无效",
    )
    return fields


def entries_for(field, bundle):
    key = field.get(
        "enumDictionaryVersionId", field.get("referenceDictionaryVersionId")
    )
    if key:
        require(
            key in bundle.get("dictionaries", {}),
            422,
            "CONFIGURATION_ERROR",
            "Schema字典依赖缺失",
        )
        entries = bundle["dictionaries"][key]["definition"].get("entries", [])
    else:
        entries = field.get("options", [])
    require(
        isinstance(entries, list) and 0 < len(entries) <= 10000,
        422,
        "CONFIGURATION_ERROR",
        "枚举或引用须定义稳定代码字典",
    )
    codes = set()
    tokens = {}
    for item in entries:
        require(
            isinstance(item, dict)
            and isinstance(item.get("code"), str)
            and item["code"]
            and item["code"] not in codes,
            422,
            "CONFIGURATION_ERROR",
            "字典稳定代码无效或重复",
        )
        code = item["code"]
        codes.add(code)
        aliases = item.get("aliases", [])
        require(
            isinstance(aliases, list) and all(isinstance(x, str) for x in aliases),
            422,
            "CONFIGURATION_ERROR",
            "字典别名须为字符串数组",
        )
        for value in [code] + aliases + ([item["name"]] if item.get("name") else []):
            require(
                isinstance(value, str), 422, "CONFIGURATION_ERROR", "字典名称须为文本"
            )
            token = string_normalize(value, field)
            require(
                token not in tokens or tokens[token] == code,
                422,
                "CONFIGURATION_ERROR",
                "标准化后字典别名存在歧义",
            )
            tokens[token] = code
    return entries


def string_normalize(value, field):
    if field.get("trim", True):
        value = value.strip()
    case = field.get("case", "PRESERVE")
    return (
        value.upper()
        if case == "UPPER"
        else value.lower()
        if case == "LOWER"
        else value
    )


def typed(value, field, bundle):
    kind = field["type"]
    if value is None:
        return None
    if kind in {"INTEGER", "DECIMAL"}:
        unit = field.get("unit", "")
        if isinstance(value, dict):
            require(
                set(value) <= {"value", "unit"} and "value" in value,
                422,
                "TYPE_NUMBER",
                "数值对象只允许value/unit",
            )
            source = value.get("unit", unit)
            value = value["value"]
            require(
                bool(unit) or not source, 422, "UNIT_DIMENSION", "无单位属性不接受单位"
            )
        else:
            source = unit
        value = number(value)
        if unit:
            value = convert(
                value, source, unit, bundle["schema"]["definition"].get("units", {})
            )
        if kind == "INTEGER":
            require(
                value == value.to_integral_value(),
                422,
                "TYPE_INTEGER",
                "整数不接受小数",
            )
        stripped = exact_normalize(value)
        require(
            max(0, -stripped.as_tuple().exponent) <= field.get("scale", 12)
            and max(len(stripped.as_tuple().digits), stripped.adjusted() + 1)
            <= field.get("precision", 38),
            422,
            "PRECISION_OVERFLOW",
            "数值精度溢出",
        )
        require(
            ("min" not in field or value >= number(field["min"]))
            and ("max" not in field or value <= number(field["max"])),
            422,
            "OUT_OF_RANGE",
            "数值不在允许范围",
        )
        return value
    if kind == "BOOLEAN":
        if isinstance(value, str) and value.strip().lower() in {"true", "false"}:
            value = value.strip().lower() == "true"
        require(isinstance(value, bool), 422, "TYPE_BOOLEAN", "应为true或false")
        return value
    if kind == "DATE":
        require(
            isinstance(value, str)
            and re.fullmatch(r"\d{4}-\d{2}-\d{2}", value.strip()),
            422,
            "TYPE_DATE",
            "日期应为YYYY-MM-DD",
        )
        try:
            return date.fromisoformat(value.strip()).isoformat()
        except ValueError:
            raise Problem(422, "TYPE_DATE", "日期无效")
    if kind == "REFERENCE" and isinstance(value, dict):
        require(
            set(value) <= {"code", "id", "type"},
            422,
            "TYPE_REFERENCE",
            "引用只允许稳定code/id/type",
        )
        if "type" in value:
            require(
                value["type"] == field.get("referenceType"),
                422,
                "TYPE_REFERENCE",
                "引用类型不一致",
            )
        value = value.get("code", value.get("id"))
    require(isinstance(value, str), 422, "TYPE_STRING", "属性应为文本或稳定代码")
    value = string_normalize(value, field)
    if value == "" and field.get("emptyToNull", True):
        return None
    require(
        len(value) <= field.get("length", 1000)
        and len(value) >= field.get("minLength", 0),
        422,
        "LENGTH",
        "文本长度不在允许范围",
    )
    if "pattern" in field:
        require(
            bool(pattern(field["pattern"]).fullmatch(value)),
            422,
            "PATTERN",
            "文本格式不合法",
        )
    if kind in {"ENUM", "REFERENCE"}:
        for entry in entries_for(field, bundle):
            codes = (
                [str(entry["code"])]
                + [str(a) for a in entry.get("aliases", [])]
                + ([entry["name"]] if entry.get("name") else [])
            )
            if value in [string_normalize(x, field) for x in codes] and entry.get(
                "active", True
            ):
                return str(entry["code"])
        raise Problem(
            422,
            "ENUM_INVALID" if kind == "ENUM" else "REFERENCE_INVALID",
            "枚举或引用代码不存在或已停用",
        )
    return value


def error_detail(field, code, message, raw, normalized, version):
    return {
        "fieldPath": "attributes." + field,
        "errorCode": code,
        "code": code,
        "message": message,
        "sourceValue": raw,
        "normalizedValue": normalized,
        "ruleVersion": str(version),
        "suggestion": "请检查该属性的Schema和规则配置",
    }


def prepare(bundle, caller, raw):
    fields = compile_bundle(bundle)
    require(
        isinstance(raw, dict), 422, "SCHEMA_VALIDATION_ERROR", "attributes必须为对象"
    )
    profile = next(
        (
            p
            for p in bundle.get("inputProfiles", [])
            if p["definition"].get("callerSystemCode") == caller
        ),
        None,
    )
    mapped = deepcopy(raw)
    if profile:
        mapped = {}
        mappings = profile["definition"].get("fields", [])
        consumed = {r["source"] for r in mappings if "source" in r}
        # Preserve directly named Schema attributes. Aliases consumed only by the profile.
        for key, value in raw.items():
            require(
                key in fields or key in consumed,
                422,
                "INPUT_MAPPING_ERROR",
                "输入映射含未知字段：" + key,
            )
            if key in fields and key not in consumed:
                mapped[key] = value
        for rule in mappings:
            target = rule["target"]
            require(
                target not in mapped or rule.get("source") == target,
                422,
                "INPUT_MAPPING_ERROR",
                "标准字段与别名重复：" + target,
            )
            value = (
                rule.get("constant") if "constant" in rule else raw.get(rule["source"])
            )
            if value is None:
                continue
            if "dictionaryVersionId" in rule:
                entries = bundle["dictionaries"][rule["dictionaryVersionId"]][
                    "definition"
                ].get("entries", [])
                lookup = {
                    str(a): e["code"]
                    for e in entries
                    if e.get("active", True)
                    for a in [e["code"]] + e.get("aliases", [])
                }
            else:
                lookup = rule.get("map")
            if lookup is not None:
                require(
                    str(value) in lookup,
                    422,
                    "INPUT_MAPPING_ERROR",
                    "输入字典映射缺失：" + target,
                )
                value = lookup[str(value)]
            if isinstance(value, str):
                value = (
                    rule.get("prefix", "")
                    + string_normalize(value, rule)
                    + rule.get("suffix", "")
                )
            mapped[target] = value
    errors = []
    normalized = {}
    for key in mapped:
        if key not in fields:
            errors.append(
                error_detail(
                    key,
                    "UNKNOWN_FIELD",
                    "未知属性",
                    mapped[key],
                    None,
                    bundle["schema"]["id"],
                )
            )
        elif "derived" in fields[key]:
            errors.append(
                error_detail(
                    key,
                    "DERIVED_READONLY",
                    "受保护派生字段不可由调用方提交",
                    mapped[key],
                    None,
                    bundle["schema"]["id"],
                )
            )
    for key, field in fields.items():
        if "derived" in field:
            continue
        value = mapped.get(key)
        if (
            isinstance(value, str)
            and field.get("trim", True)
            and not value.strip()
            and field.get("emptyToNull", True)
        ):
            value = None
        if value is None and "default" in field:
            value = field["default"]
        try:
            normalized[key] = typed(value, field, bundle)
        except Problem as p:
            errors.append(
                error_detail(
                    key,
                    p.code,
                    p.message,
                    mapped.get(key),
                    None,
                    bundle["schema"]["id"],
                )
            )
    if errors:
        raise Problem(422, "SCHEMA_VALIDATION_ERROR", "Schema校验失败", errors)
    derived = {}
    units = bundle["schema"]["definition"].get("units", {})
    for key in derive_order(fields):
        try:
            derived[key] = typed(
                evaluate(fields[key]["derived"], normalized, units), fields[key], bundle
            )
            normalized[key] = derived[key]
        except Problem as p:
            raise Problem(
                422,
                "DERIVATION_ERROR",
                "派生计算失败",
                [
                    error_detail(
                        key, p.code, p.message, None, None, bundle["schema"]["id"]
                    )
                ],
            )
    for key, field in fields.items():
        if field.get("required") and normalized.get(key) is None:
            errors.append(
                error_detail(
                    key,
                    "REQUIRED",
                    "属性必填",
                    mapped.get(key),
                    None,
                    bundle["schema"]["id"],
                )
            )
    if errors:
        raise Problem(422, "SCHEMA_VALIDATION_ERROR", "必填属性缺失", errors)
    validation = []
    for rule in bundle.get("validations", []):
        for item in rule["definition"].get("rules", []):
            try:
                passed = bool(evaluate(item["assert"], normalized, units))
                message = item.get("message", "业务规则未通过")
                code = item.get("errorCode", "RULE_FAILED")
            except Problem as p:
                passed = False
                message = p.message
                code = p.code
            result = error_detail(
                item.get("field", ""),
                code,
                message,
                mapped.get(item.get("field", "")),
                normalized.get(item.get("field", "")),
                rule["id"],
            )
            result.update({"passed": passed, "severity": item.get("severity", "ERROR")})
            validation.append(result)
            if not passed and result["severity"] == "ERROR":
                errors.append(result)
    if errors:
        raise Problem(422, "VALIDATION_ERROR", "业务校验失败", errors)
    pairs = []
    for attr in bundle["identity"]["definition"]["attributes"]:
        attr = {"field": attr} if isinstance(attr, str) else attr
        key = attr["field"]
        value = normalized.get(key)
        require(
            value is not None or attr.get("nullPolicy", "ERROR") == "ALLOW",
            422,
            "IDENTITY_INCOMPLETE",
            "Identity属性缺失：" + key,
        )
        if isinstance(value, str):
            value = string_normalize(value, attr)
        if isinstance(value, (int, Decimal)) and not isinstance(value, bool):
            value = number(value)
            if attr.get("unit"):
                value = convert(value, fields[key].get("unit", ""), attr["unit"], units)
            if "scale" in attr:
                with localcontext() as ctx:
                    ctx.prec = 110
                    scaled = value.quantize(Decimal(1).scaleb(-attr["scale"]))
                    require(
                        scaled == value,
                        422,
                        "IDENTITY_PRECISION",
                        "Identity精度不能丢失属性信息",
                    )
                    value = format(scaled, "f")
            else:
                value = format(exact_normalize(value), "f")
        pairs.append([key, value])
    canonical = dumps(pairs, separators=(",", ":"))
    return {
        "mappedAttributes": mapped,
        "normalizedAttributes": normalized,
        "derivedAttributes": derived,
        "validationResults": validation,
        "identityCanonical": canonical,
        "identityHash": digest(canonical),
        "inputProfileVersionId": profile["id"] if profile else None,
    }


def period(segment, now):
    instant = now.astimezone(ZoneInfo(segment.get("timezone", "UTC")))
    return {
        "NEVER": "NEVER",
        "DAILY": instant.strftime("%Y%m%d"),
        "MONTHLY": instant.strftime("%Y%m"),
        "YEARLY": instant.strftime("%Y"),
    }[segment.get("reset", "YEARLY")]


def render(bundle, attrs, allocate=None, now=None):
    now = now or datetime.now().astimezone()
    rule = bundle["codeRule"]["definition"]
    segments = []
    parts = []
    units = bundle["schema"]["definition"].get("units", {})
    fields = compile_bundle(bundle)
    for index, segment in enumerate(rule["segments"]):
        kind = segment["type"]
        value = get(attrs, segment.get("field", ""))
        output = ""
        if kind in {"CONSTANT", "SEPARATOR"}:
            output = str(segment.get("value", ""))
        elif kind == "PERIOD":
            output = period(segment, now)
        elif kind == "SEQUENCE":
            name = segment.get("name", "MAIN")
            key = period({**segment, "reset": segment.get("reset", "NEVER")}, now)
            width = segment.get("width", 6)
            if allocate:
                value = allocate(name, key)
                require(
                    len(str(value)) <= width,
                    422,
                    "SEQUENCE_ERROR",
                    "流水容量耗尽，请发布新规则",
                )
                output = str(value).zfill(width)
            else:
                output = "{SEQUENCE:" + name + ":" + str(width) + "}"
        else:
            if value is None:
                policy = segment.get("nullPolicy", "ERROR")
                require(
                    policy in {"EMPTY", "DEFAULT"},
                    422,
                    "CODE_GENERATION_ERROR",
                    "编码依赖为空",
                )
                output = segment.get("default", "") if policy == "DEFAULT" else ""
            elif kind == "DICTIONARY":
                mapping = segment.get("map", {})
                if "dictionaryVersionId" in segment:
                    entries = bundle["dictionaries"][segment["dictionaryVersionId"]][
                        "definition"
                    ].get("entries", [])
                    mapping = {
                        e["code"]: e.get("numberCode", e["code"])
                        for e in entries
                        if e.get("active", True)
                    }
                require(
                    text(value) in mapping,
                    422,
                    "CODE_GENERATION_ERROR",
                    "编码字典映射缺失",
                )
                output = str(mapping[text(value)])
            elif kind in {"NUMBER_FORMAT", "UNIT_FORMAT"}:
                value = number(value)
                if kind == "UNIT_FORMAT":
                    value = convert(
                        value,
                        fields[segment["field"].split(".")[0]].get("unit", ""),
                        segment["unit"],
                        units,
                    )
                if "scale" in segment:
                    with localcontext() as ctx:
                        ctx.prec = 110
                        scaled = value.quantize(Decimal(1).scaleb(-segment["scale"]))
                        require(
                            scaled == value,
                            422,
                            "CODE_GENERATION_ERROR",
                            "编码格式不能舍弃数值精度",
                        )
                        output = format(scaled, "f")
                else:
                    output = text(value)
                if kind == "UNIT_FORMAT" and segment.get("includeUnit"):
                    output += segment["unit"]
            else:
                output = text(value)
        if kind != "SEQUENCE":
            output = str(output).rjust(
                segment.get("padding", 0), segment.get("padChar", "0")
            )
        segments.append(
            {"index": index, "definition": segment, "input": value, "output": output}
        )
        parts.append(output)
    result = rule.get("separator", "").join(parts)
    result = string_normalize(
        result, {"trim": False, "case": rule.get("case", "PRESERVE")}
    )
    if allocate or not any(s["type"] == "SEQUENCE" for s in rule["segments"]):
        require(
            0 < len(result) <= rule.get("maxLength", 128),
            422,
            "CODE_GENERATION_ERROR",
            "料号长度不在允许范围",
        )
        require(
            bool(
                pattern(rule.get("allowedPattern", "[A-Za-z0-9_./-]+")).fullmatch(
                    result
                )
            ),
            422,
            "CODE_GENERATION_ERROR",
            "料号字符集不合法",
        )
    else:
        # Validate a representative padded value without allocating a counter.
        sample = re.sub(
            r"\{SEQUENCE:[^}]+:(\d+)\}", lambda m: "0" * (int(m[1]) - 1) + "1", result
        )
        require(
            0 < len(sample) <= rule.get("maxLength", 128)
            and bool(
                pattern(rule.get("allowedPattern", "[A-Za-z0-9_./-]+")).fullmatch(
                    sample
                )
            ),
            422,
            "CODE_GENERATION_ERROR",
            "预览模板长度或字符集不合法",
        )
    return {"materialNo": result, "codeSegments": segments}
