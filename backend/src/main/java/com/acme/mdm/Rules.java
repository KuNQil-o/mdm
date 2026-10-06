package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;

@Service
public class Rules {
  final Db db;
  final Stats stats;
  final ThreadLocal<Long> deadline = new ThreadLocal<>();

  public Rules(Db d, Stats s) {
    db = d;
    stats = s;
  }

  public Map<String, JsonNode> fields(JsonNode b) {
    Map<String, JsonNode> m = new LinkedHashMap<>();
    for (var f : b.path("attributes")) {
      String code = Json.text(f, "code");
      Problem.require(
          code.matches("[A-Za-z][A-Za-z0-9_]{0,79}") && !m.containsKey(code),
          422,
          "ATTRIBUTE_CODE",
          "属性code非法或重复");
      m.put(code, f);
    }
    return m;
  }

  public Set<String> deps(JsonNode ast) {
    Set<String> s = new HashSet<>();
    if (ast.isObject()) {
      if (ast.has("field")) s.add(ast.path("field").asText().split("\\.")[0]);
      ast.elements().forEachRemaining(x -> s.addAll(deps(x)));
    } else if (ast.isArray()) ast.forEach(x -> s.addAll(deps(x)));
    return s;
  }

  int nodes(JsonNode n) {
    int count = 1;
    for (var x : n) count += nodes(x);
    return count;
  }

  public List<String> order(JsonNode b) {
    var f = fields(b);
    List<String> out = new ArrayList<>();
    Set<String> visiting = new HashSet<>(), done = new HashSet<>();
    for (String c : f.keySet()) visit(c, f, visiting, done, out);
    return out;
  }

  void visit(String c, Map<String, JsonNode> f, Set<String> v, Set<String> d, List<String> o) {
    if (d.contains(c)) return;
    Problem.require(!v.contains(c), 422, "CYCLIC_DEPENDENCY", "派生属性循环依赖：" + c);
    v.add(c);
    if (f.get(c).has("derived")) {
      for (String dep : deps(f.get(c).get("derived"))) {
        Problem.require(f.containsKey(dep), 422, "UNKNOWN_DEPENDENCY", "未知依赖属性：" + dep);
        visit(dep, f, v, d, o);
      }
      o.add(c);
    }
    v.remove(c);
    d.add(c);
  }

  public void compile(JsonNode b) {
    var f = fields(b);
    Problem.require(!f.isEmpty() && f.size() <= 200, 422, "SCHEMA_FIELDS", "配置须有1—200个属性");
    for (var e : f.entrySet()) {
      var p = e.getValue();
      Problem.require(
          Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "DATE", "ENUM", "REFERENCE")
              .contains(Json.text(p, "type")),
          422,
          "ATTRIBUTE_TYPE",
          "未知属性类型");
      for (String k : List.of("derived", "requiredWhen", "visibleWhen"))
        if (p.has(k)) {
          Problem.require(nodes(p.get(k)) <= 200, 422, "RULE_BUDGET", "AST超过200节点");
          checkAst(p.get(k), f);
        }
      if (p.has("pattern")) safePattern(p.path("pattern").asText());
      if (p.path("required").asBoolean()
          && p.has("visibleWhen")
          && p.get("visibleWhen").isBoolean()
          && !p.path("visibleWhen").asBoolean())
        throw new Problem(422, "CONDITION_CONFLICT", "隐藏字段不能无条件必填");
      if (p.has("requiredWhen")
          && p.has("visibleWhen")
          && p.path("requiredWhen").asBoolean(false)
          && p.path("visibleWhen").isBoolean()
          && !p.path("visibleWhen").asBoolean())
        throw new Problem(422, "CONDITION_CONFLICT", "条件必填与隐藏冲突");
    }
    order(b);
    for (var s : b.path("codeRule").path("segments")) {
      String type = s.path("type").asText();
      Problem.require(
          Set.of("CONST", "ATTR", "LOOKUP", "SEQUENCE", "PERIOD").contains(type),
          422,
          "CODE_RULE",
          "不支持的编码段");
      if (s.has("field"))
        Problem.require(
            f.containsKey(s.path("field").asText().split("\\.")[0]), 422, "CODE_RULE", "编码使用未知属性");
      if (type.equals("SEQUENCE")) {
        String reset = s.path("reset").asText("NEVER");
        Problem.require(
            Set.of("NEVER", "DAILY", "YEARLY").contains(reset), 422, "SEQUENCE_PERIOD", "流水周期无效");
        if (!reset.equals("NEVER"))
          Problem.require(
              b.path("codeRule").path("segments").toString().contains("PERIOD")
                  && s.has("timezone"),
              422,
              "SEQUENCE_PERIOD",
              "重置流水须含周期编码及固定时区");
      }
    }
    if (b.has("parseRule") && b.path("parseRule").has("pattern"))
      safePattern(b.path("parseRule").path("pattern").asText());
  }

  void checkAst(JsonNode n, Map<String, JsonNode> f) {
    if (n.isObject()) {
      if (n.has("field"))
        Problem.require(
            f.containsKey(n.path("field").asText().split("\\.")[0]),
            422,
            "UNKNOWN_DEPENDENCY",
            "条件引用未知字段");
      if (n.has("op"))
        Problem.require(
            Set.of(
                    "eq", "ne", "gt", "gte", "lt", "lte", "in", "and", "or", "not", "exists", "add",
                    "sub", "mul", "div")
                .contains(n.path("op").asText()),
            422,
            "UNKNOWN_OPERATOR",
            "未登记运算符");
      for (var x : n) checkAst(x, f);
    } else if (n.isArray()) for (var x : n) checkAst(x, f);
  }

  Pattern safePattern(String p) {
    Problem.require(
        p.length() <= 512 && !p.matches(".*\\([^)]*[+*][^)]*\\)[+*{].*"),
        422,
        "INVALID_REGEX",
        "正则过长或包含嵌套重复");
    try {
      return Pattern.compile(p);
    } catch (PatternSyntaxException e) {
      throw new Problem(422, "INVALID_REGEX", "正则表达式无效");
    }
  }

  JsonNode scalar(JsonNode x) {
    return x != null && x.isObject() && x.has("value")
        ? x.get("value")
        : x == null ? NullNode.instance : x;
  }

  public JsonNode eval(JsonNode n, JsonNode a) {
    Long end = deadline.get();
    Problem.require(end == null || System.nanoTime() <= end, 422, "RULE_TIMEOUT", "规则执行超过100ms预算");
    if (n == null) return NullNode.instance;
    if (!n.isObject()) return n;
    if (n.has("field")) return scalar(Json.path(a, n.path("field").asText()));
    if (n.has("literal")) return n.get("literal");
    String op = n.path("op").asText();
    List<JsonNode> args = new ArrayList<>();
    n.path("args").forEach(x -> args.add(eval(x, a)));
    JsonNode x = args.isEmpty() ? NullNode.instance : args.getFirst(),
        y = args.size() > 1 ? args.get(1) : NullNode.instance;
    boolean result;
    switch (op) {
      case "and":
        return BooleanNode.valueOf(args.stream().allMatch(Json::truth));
      case "or":
        return BooleanNode.valueOf(args.stream().anyMatch(Json::truth));
      case "not":
        return BooleanNode.valueOf(!Json.truth(x));
      case "exists":
        return BooleanNode.valueOf(!x.isNull());
      case "eq":
        return BooleanNode.valueOf(equal(x, y));
      case "ne":
        return BooleanNode.valueOf(!equal(x, y));
      case "in":
        result = false;
        for (var v : y) if (equal(x, v)) result = true;
        return BooleanNode.valueOf(result);
      case "gt":
      case "gte":
      case "lt":
      case "lte":
        int c = compare(x, y);
        return BooleanNode.valueOf(
            switch (op) {
              case "gt" -> c > 0;
              case "gte" -> c >= 0;
              case "lt" -> c < 0;
              default -> c <= 0;
            });
      case "add":
      case "sub":
      case "mul":
      case "div":
        BigDecimal p = Json.decimal(x), q = Json.decimal(y);
        try {
          return DecimalNode.valueOf(
              switch (op) {
                case "add" -> p.add(q);
                case "sub" -> p.subtract(q);
                case "mul" -> p.multiply(q);
                default -> p.divide(q);
              });
        } catch (ArithmeticException e) {
          throw new Problem(
              422, q.signum() == 0 ? "DIVIDE_BY_ZERO" : "PRECISION_OVERFLOW", "派生计算除零或无法精确表示");
        }
      default:
        throw new Problem(422, "UNKNOWN_OPERATOR", "规则运算无效");
    }
  }

  public boolean equal(JsonNode x, JsonNode y) {
    x = scalar(x);
    y = scalar(y);
    if (x.isNumber() && y.isNumber()) return x.decimalValue().compareTo(y.decimalValue()) == 0;
    return x.equals(y);
  }

  int compare(JsonNode x, JsonNode y) {
    Problem.require(!x.isNull() && !y.isNull(), 422, "DEPENDENCY_MISSING", "比较规则缺少依赖");
    if (x.isNumber() || y.isNumber()) return Json.decimal(x).compareTo(Json.decimal(y));
    return x.asText().compareTo(y.asText());
  }

  public BigDecimal convert(JsonNode b, BigDecimal value, String from, String to) {
    if (from.equals(to) && from.isEmpty()) return value;
    var u = b.path("units");
    var f = u.path(from);
    var t = u.path(to);
    Problem.require(
        !f.isMissingNode()
            && !t.isMissingNode()
            && f.path("dimension").asText().equals(t.path("dimension").asText()),
        422,
        "UNIT_DIMENSION",
        "单位不存在或量纲不一致");
    try {
      return value
          .multiply(new BigDecimal(f.path("factor").asText("1")))
          .add(new BigDecimal(f.path("offset").asText("0")))
          .subtract(new BigDecimal(t.path("offset").asText("0")))
          .divide(new BigDecimal(t.path("factor").asText("1")));
    } catch (ArithmeticException e) {
      throw new Problem(422, "PRECISION_OVERFLOW", "单位换算无法精确表示");
    }
  }

  public ObjectNode normalize(Ctx c, JsonNode b, JsonNode input, boolean full, boolean client) {
    long start = System.nanoTime();
    deadline.set(start + 100_000_000L);
    try {
      return normalizeValues(c, b, input, full, client);
    } finally {
      stats.rule(c.tenant(), System.nanoTime() - start);
      deadline.remove();
    }
  }

  ObjectNode normalizeValues(Ctx c, JsonNode b, JsonNode input, boolean full, boolean client) {
    Problem.require(input.isObject(), 422, "ATTRIBUTES_SHAPE", "attributes必须为对象");
    ObjectNode out = Json.obj();
    var fs = fields(b);
    ArrayNode errors = Json.arr();
    input
        .fieldNames()
        .forEachRemaining(
            k -> {
              if (!fs.containsKey(k)) errors.add(error(k, "UNKNOWN_FIELD", "未知属性", b));
            });
    for (var e : fs.entrySet()) {
      String k = e.getKey();
      var f = e.getValue();
      JsonNode v = input.get(k);
      if (client && f.has("derived") && v != null)
        errors.add(error(k, "DERIVED_READONLY", "派生属性不可由客户端写入", b));
      if (f.has("derived")) continue;
      if (v == null && f.has("default")) v = f.get("default");
      if (v == null) continue;
      if (v.isNull()) {
        out.putNull(k);
        continue;
      }
      try {
        out.set(k, typed(c, b, f, v, full));
      } catch (Problem p) {
        errors.add(error(k, p.code, p.getMessage(), b));
      }
    }
    for (String k : order(b)) {
      var f = fs.get(k);
      try {
        JsonNode d = eval(f.get("derived"), out);
        JsonNode v =
            Set.of("DECIMAL", "INTEGER").contains(Json.text(f, "type"))
                ? Json.object("value", d, "unit", f.path("unit").asText())
                : d;
        out.set(k, typed(c, b, f, v, full));
      } catch (Problem p) {
        if (full || !p.code.equals("DEPENDENCY_MISSING"))
          errors.add(error(k, p.code, p.getMessage(), b));
      }
    }
    for (var e : fs.entrySet()) {
      var f = e.getValue();
      String k = e.getKey();
      try {
        boolean required =
            f.path("required").asBoolean()
                || (f.has("requiredWhen") && Json.truth(eval(f.get("requiredWhen"), out)));
        boolean absent =
            !out.has(k)
                || out.path(k).isNull()
                || (out.path(k).isTextual() && out.path(k).asText().isEmpty());
        if (full && required && absent) errors.add(error(k, "REQUIRED", "此属性必填", b));
        if (full
            && required
            && absent
            && f.has("visibleWhen")
            && !Json.truth(eval(f.get("visibleWhen"), out)))
          errors.add(error(k, "CONDITION_CONFLICT", "字段隐藏但需填写，请修正配置", b));
      } catch (Problem p) {
        errors.add(error(k, p.code, p.getMessage(), b));
      }
    }
    for (var rule : b.path("validations")) {
      try {
        if (full && !Json.truth(eval(rule.path("assert"), out))) {
          var er =
              error(
                  rule.path("field").asText(),
                  "RULE_FAILED",
                  rule.path("message").asText("业务规则未通过"),
                  b);
          if (!rule.path("severity").asText().equals("warning")) errors.add(er);
        }
      } catch (Problem p) {
        if (full) errors.add(error("", p.code, p.getMessage(), b));
      }
    }
    if (full && b.path("dataSchema").isObject() && errors.isEmpty()) {
      var rawSchema = (ObjectNode) b.path("dataSchema").deepCopy();
      rawSchema.remove("required");
      var rawValidator =
          com.networknt.schema.JsonSchemaFactory.getInstance(
                  com.networknt.schema.SpecVersion.VersionFlag.V202012)
              .getSchema(rawSchema);
      for (var message : rawValidator.validate(input))
        errors.add(
            error(
                message.getInstanceLocation().toString(),
                "SCHEMA_STRUCTURE",
                message.getMessage(),
                b));
      var schema =
          com.networknt.schema.JsonSchemaFactory.getInstance(
                  com.networknt.schema.SpecVersion.VersionFlag.V202012)
              .getSchema(b.path("dataSchema"));
      for (var message : schema.validate(out))
        errors.add(
            error(
                message.getInstanceLocation().toString(),
                "SCHEMA_STRUCTURE",
                message.getMessage(),
                b));
    }
    if (!errors.isEmpty()) throw new Problem(422, "VALIDATION_ERROR", "物料校验未通过", errors);
    return out;
  }

  ObjectNode error(String k, String code, String msg, JsonNode b) {
    return Json.object(
        "fieldPath",
        "/attributes/" + k,
        "code",
        code,
        "message",
        msg,
        "ruleVersion",
        b.path("version").asInt(1),
        "suggestion",
        "请检查属性类型、单位及对应配置");
  }

  JsonNode typed(Ctx c, JsonNode b, JsonNode f, JsonNode v, boolean full) {
    String type = Json.text(f, "type");
    switch (type) {
      case "STRING":
        Problem.require(v.isTextual(), 422, "TYPE_STRING", "应为文本");
        Problem.require(
            v.asText().length() <= f.path("maxLength").asInt(1000), 422, "MAX_LENGTH", "文本过长");
        if (f.has("pattern"))
          Problem.require(
              safePattern(f.path("pattern").asText()).matcher(v.asText()).matches(),
              422,
              "PATTERN",
              "文本格式错误");
        return v;
      case "BOOLEAN":
        Problem.require(v.isBoolean(), 422, "TYPE_BOOLEAN", "应为true或false");
        return v;
      case "DATE":
        try {
          Problem.require(
              v.isTextual() && v.asText().matches("\\d{4}-\\d{2}-\\d{2}"),
              422,
              "TYPE_DATE",
              "日期应为YYYY-MM-DD");
          LocalDate.parse(v.asText());
        } catch (DateTimeParseException e) {
          throw new Problem(422, "TYPE_DATE", "日期无效");
        }
        return v;
      case "INTEGER":
      case "DECIMAL":
        Problem.require(
            v.isObject() && v.has("value") && v.path("value").isNumber(),
            422,
            "TYPE_NUMBER",
            "数值应为{value,unit}，value必须是数值");
        v.fieldNames()
            .forEachRemaining(
                k ->
                    Problem.require(
                        Set.of("value", "unit").contains(k),
                        422,
                        "NUMBER_SHAPE",
                        "数值对象只允许value/unit"));
        BigDecimal value = Json.decimal(v);
        String standard = f.path("unit").asText("");
        String unit = v.path("unit").asText("");
        if (!standard.isEmpty()) {
          Problem.require(!unit.isEmpty(), 422, "UNIT_REQUIRED", "缺少单位");
          value = convert(b, value, unit, standard);
        } else Problem.require(unit.isEmpty(), 422, "UNIT_UNEXPECTED", "无单位量不能附带单位");
        if (type.equals("INTEGER"))
          Problem.require(value.stripTrailingZeros().scale() <= 0, 422, "TYPE_INTEGER", "整数不接受小数");
        Problem.require(
            value.stripTrailingZeros().scale() <= f.path("scale").asInt(12)
                && Math.max(value.precision(), value.precision() - value.scale())
                    <= f.path("precision").asInt(38),
            422,
            "PRECISION_OVERFLOW",
            "数值精度溢出");
        if (f.has("min"))
          Problem.require(
              value.compareTo(Json.decimal(f.get("min"))) >= 0, 422, "OUT_OF_RANGE", "小于最小值");
        if (f.has("max"))
          Problem.require(
              value.compareTo(Json.decimal(f.get("max"))) <= 0, 422, "OUT_OF_RANGE", "大于最大值");
        ObjectNode num = Json.object("value", value.stripTrailingZeros());
        if (!standard.isEmpty()) num.put("unit", standard);
        return num;
      case "ENUM":
        Problem.require(v.isTextual(), 422, "TYPE_ENUM", "枚举应为code");
        boolean ok = false;
        for (var x : f.path("options"))
          if (x.path("code").asText().equals(v.asText()) && x.path("active").asBoolean(true))
            ok = true;
        Problem.require(ok, 422, "ENUM_INVALID", "字典项不存在或已停用");
        return v;
      case "REFERENCE":
        Problem.require(
            v.isObject()
                && v.path("type").asText().equals(f.path("referenceType").asText())
                && v.hasNonNull("id"),
            422,
            "TYPE_REFERENCE",
            "引用应为{type,id}且类型一致");
        var r =
            db.maybe(
                "select * from reference_entity where tenant_id=? and type=? and id=?",
                c.tid(),
                v.path("type").asText(),
                v.path("id").asText());
        Problem.require(r != null, 422, "REFERENCE_OWNERSHIP", "引用不属于当前租户或不存在");
        if (full) Problem.require(r.path("active").asBoolean(), 422, "REFERENCE_INACTIVE", "引用已停用");
        return Json.object("type", v.path("type").asText(), "id", v.path("id").asText());
      default:
        throw new Problem(422, "ATTRIBUTE_TYPE", "未知类型");
    }
  }

  public Set<String> protectedFields(JsonNode b) {
    Set<String> s = new HashSet<>();
    for (var part : b.path("codeRule").path("segments"))
      if (part.has("field")) s.add(part.path("field").asText().split("\\.")[0]);
    var f = fields(b);
    boolean changed = true;
    while (changed) {
      changed = false;
      for (String k : new HashSet<>(s))
        if (f.containsKey(k) && f.get(k).has("derived"))
          if (s.addAll(deps(f.get(k).get("derived")))) changed = true;
    }
    return s;
  }

  public String number(Ctx c, String schemaId, JsonNode b, JsonNode a, boolean reserve) {
    List<String> parts = new ArrayList<>();
    for (var p : b.path("codeRule").path("segments")) {
      String type = p.path("type").asText(), v = "";
      switch (type) {
        case "CONST":
          v = p.path("value").asText();
          break;
        case "ATTR":
          var x = scalar(Json.path(a, p.path("field").asText()));
          Problem.require(!x.isNull(), 422, "CODE_DEPENDENCY", "编码缺少属性");
          if (x.isNumber()) {
            BigDecimal n = x.decimalValue();
            if (p.has("unit")) {
              String code = p.path("field").asText().split("\\.")[0];
              n = convert(b, n, a.path(code).path("unit").asText(), p.path("unit").asText());
            }
            v =
                p.has("scale")
                    ? n.setScale(p.path("scale").asInt(), RoundingMode.UNNECESSARY).toPlainString()
                    : n.stripTrailingZeros().toPlainString();
          } else v = x.asText();
          break;
        case "LOOKUP":
          var lookup = scalar(Json.path(a, p.path("field").asText()));
          if (lookup.isObject()) lookup = lookup.path("id");
          v = p.path("map").path(lookup.asText()).asText("");
          Problem.require(!v.isEmpty(), 422, "CODE_LOOKUP", "编码映射缺失");
          break;
        case "PERIOD":
          v = period(p);
          break;
        case "SEQUENCE":
          int width = p.path("width").asInt(6);
          Problem.require(width >= 1 && width <= 18, 422, "SEQUENCE_WIDTH", "流水位数须为1—18");
          if (!reserve) v = "{流水:" + width + "}";
          else {
            String pr = p.path("reset").asText("NEVER").equals("NEVER") ? "NEVER" : period(p);
            var n =
                db.one(
                    "insert into number_sequence(tenant_id,rule_id,name,period,next_value)"
                        + " values(?,?,?,?,1) on conflict(tenant_id,rule_id,name,period) do update"
                        + " set next_value=number_sequence.next_value+1 returning next_value",
                    c.tid(),
                    UUID.fromString(p.path("ruleId").asText(schemaId)),
                    p.path("name").asText("MAIN"),
                    pr);
            long next = n.path("nextValue").asLong();
            Problem.require(
                Long.toString(next).length() <= width, 422, "SEQUENCE_EXHAUSTED", "流水容量耗尽，请发布扩展规则");
            v = String.format("%0" + width + "d", next);
          }
          break;
        default:
          throw new Problem(422, "CODE_RULE", "编码段无效");
      }
      parts.add(v);
    }
    String result = String.join(b.path("codeRule").path("separator").asText("-"), parts);
    Problem.require(
        !result.isEmpty() && result.length() <= 128, 422, "CODE_LENGTH", "料号为空或超过128字符");
    if (reserve)
      Problem.require(
          result.matches(b.path("codeRule").path("allowedPattern").asText("[A-Za-z0-9_./-]+")),
          422,
          "CODE_FORMAT",
          "料号字符格式不允许");
    return result;
  }

  String period(JsonNode p) {
    var z = ZoneId.of(p.path("timezone").asText("UTC"));
    var date = LocalDate.now(z);
    return p.path("reset").asText("YEARLY").equals("DAILY")
        ? date.toString().replace("-", "")
        : Integer.toString(date.getYear());
  }

  public ObjectNode parse(Ctx c, JsonNode schema, String no) {
    Problem.require(no.length() <= 128, 422, "CODE_LENGTH", "料号超过128字符");
    JsonNode b = schema.path("bundle"), p = b.path("parseRule");
    Problem.require(!p.isMissingNode(), 422, "PARSE_RULE_MISSING", "此版本无解析规则");
    ObjectNode attrs = Json.obj();
    ArrayNode errors = Json.arr(), unparsed = Json.arr();
    boolean[] covered = new boolean[no.length()];
    boolean fixed = !p.path("kind").asText("REGEX").equals("REGEX");
    Matcher m = null;
    if (p.path("kind").asText("REGEX").equals("REGEX")) {
      m = safePattern(p.path("pattern").asText()).matcher(no);
      if (!m.matches())
        return Json.object(
            "attributes",
            attrs,
            "parseRuleVersionId",
            schema.path("id"),
            "matchType",
            "NONE",
            "unparsedSegments",
            Json.arr().add(no),
            "errors",
            Json.arr().add(error("", "PARSE_NO_MATCH", "旧料号无法匹配，请人工补录", b)));
    }
    var fs = fields(b);
    for (var it = p.path("fields").fields(); it.hasNext(); ) {
      var e = it.next();
      var conf = e.getValue();
      int start = conf.path("start").asInt(), length = conf.path("length").asInt();
      if (fixed) {
        Problem.require(
            start >= 0 && length > 0 && start + length <= no.length(),
            422,
            "PARSE_RANGE",
            "固定段超出料号范围");
        java.util.Arrays.fill(covered, start, start + length, true);
      }
      String raw =
          m != null ? m.group(conf.path("group").asText()) : no.substring(start, start + length);
      var f = fs.get(e.getKey());
      if (f == null) continue;
      JsonNode v = TextNode.valueOf(raw);
      if (conf.has("map")) v = conf.path("map").path(raw);
      String type = f.path("type").asText();
      try {
        if (type.equals("DECIMAL") || type.equals("INTEGER"))
          v =
              Json.object(
                  "value",
                  new BigDecimal(raw),
                  "unit",
                  conf.path("unit").asText(f.path("unit").asText()));
        if (type.equals("REFERENCE")) v = Json.object("type", f.path("referenceType"), "id", v);
        attrs.set(e.getKey(), v);
      } catch (Exception ex) {
        errors.add(error(e.getKey(), "PARSE_VALUE", "解析值非法", b));
      }
    }
    if (fixed) {
      for (int i = 0; i < covered.length; ) {
        if (covered[i]) {
          i++;
          continue;
        }
        int start = i;
        while (i < covered.length && !covered[i]) i++;
        unparsed.add(no.substring(start, i));
      }
    }
    try {
      normalize(c, b, attrs, true, true);
    } catch (Problem er) {
      errors.addAll((ArrayNode) er.errors);
    }
    return Json.object(
        "attributes",
        attrs,
        "parseRuleVersionId",
        schema.path("id"),
        "matchType",
        unparsed.isEmpty() ? "EXACT_SYNTAX" : "PARTIAL_SYNTAX",
        "unparsedSegments",
        unparsed,
        "errors",
        errors,
        "warnings",
        Json.arr().add("语法匹配不代表业务真实性"));
  }

  public ObjectNode inputOnly(JsonNode b, JsonNode attrs) {
    ObjectNode o = (ObjectNode) attrs.deepCopy();
    fields(b)
        .forEach(
            (k, v) -> {
              if (v.has("derived")) o.remove(k);
            });
    return o;
  }

  public ObjectNode referenceSnapshot(Ctx c, JsonNode b, JsonNode attrs) {
    ObjectNode o = Json.obj();
    fields(b)
        .forEach(
            (k, v) -> {
              if (v.path("type").asText().equals("REFERENCE") && attrs.hasNonNull(k)) {
                var a = attrs.path(k);
                o.set(
                    k,
                    db.one(
                        "select * from reference_entity where tenant_id=? and type=? and id=?",
                        c.tid(),
                        a.path("type").asText(),
                        a.path("id").asText()));
              }
            });
    return o;
  }
}
