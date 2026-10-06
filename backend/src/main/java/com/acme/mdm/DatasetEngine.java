package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.sql.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DatasetEngine {
  final Db db;
  final SourceMetadata sources;
  final Rules rules;

  public DatasetEngine(Db d, SourceMetadata s, Rules r) {
    db = d;
    sources = s;
    rules = r;
  }

  record Column(String path, JsonNode field, String sql) {}

  record Plan(
      String from,
      Map<String, Column> columns,
      Map<String, JsonNode> objects,
      JsonNode definition) {}

  static String q(String identifier) {
    return "\"" + SourceMetadata.ident(identifier) + "\"";
  }

  static String family(JsonNode f) {
    String t = f.path("dataType").asText().toUpperCase();
    if (t.matches(".*(INT|NUMBER|NUMERIC|DECIMAL|FLOAT|DOUBLE|REAL).*")) return "NUMBER";
    if (t.contains("BOOL")) return "BOOLEAN";
    if (t.contains("DATE") || t.contains("TIME")) return "DATE";
    return "STRING";
  }

  public Plan plan(Ctx c, JsonNode cfg) {
    var b = cfg.path("definition");
    Problem.require(
        b.path("grain").asText().equals("ONE_ROOT_ROW_ONE_MATERIAL"),
        422,
        "GRAIN_DEFINITION",
        "P0必须声明一条Root对应一条物料");
    String sid = cfg.path("sourceSystemId").asText();
    sources.system(c, sid);
    Map<String, Column> columns = new LinkedHashMap<>();
    Map<String, JsonNode> objects = new LinkedHashMap<>();
    var root = b.path("root");
    String rootAlias = root.path("alias").asText("r");
    var ro = sources.object(c, root.path("objectVersionId").asText());
    Problem.require(
        ro.path("sourceSystemId").asText().equals(sid), 422, "SOURCE_SCOPE", "Dataset对象必须属于同一来源系统");
    addObject(columns, objects, rootAlias, ro);
    StringBuilder from = new StringBuilder(table(ro) + " " + q(rootAlias));
    for (var join : b.path("joins")) {
      String alias = join.path("alias").asText(), type = join.path("type").asText("LEFT");
      Problem.require(
          Set.of("INNER", "LEFT").contains(type), 422, "JOIN_TYPE", "只支持INNER/LEFT JOIN");
      Problem.require(
          Set.of("1:1", "N:1").contains(join.path("cardinality").asText()),
          422,
          "GRAIN_CONFLICT",
          "P0不支持1:N扩张；请使用ERP聚合View");
      Problem.require(
          Set.of("ERROR", "NULL", "DEFAULT").contains(join.path("nullPolicy").asText()),
          422,
          "JOIN_NULL_POLICY",
          "必须显式配置空关联策略");
      var object = sources.object(c, join.path("objectVersionId").asText());
      Problem.require(
          object.path("sourceSystemId").asText().equals(sid), 422, "SOURCE_SCOPE", "关联对象来源归属不一致");
      var previous = new HashSet<>(columns.keySet());
      addObject(columns, objects, alias, object);
      List<String> conditions = new ArrayList<>();
      for (var on : join.path("on")) {
        String left = on.path("left").asText(), right = on.path("right").asText();
        Problem.require(
            previous.contains(left) && right.startsWith(alias + ".") && columns.containsKey(right),
            422,
            "JOIN_FIELD",
            "Join须连接已有对象与当前新对象的登记字段");
        Problem.require(
            family(columns.get(left).field).equals(family(columns.get(right).field)),
            422,
            "JOIN_TYPE_MISMATCH",
            "关联字段类型不兼容：" + left + " → " + right);
        conditions.add(columns.get(left).sql + "=" + columns.get(right).sql);
      }
      Problem.require(!conditions.isEmpty(), 422, "JOIN_FIELDS", "Join至少一组等值条件");
      from.append(" ")
          .append(type)
          .append(" JOIN ")
          .append(table(object))
          .append(" ")
          .append(q(alias))
          .append(" ON ")
          .append(String.join(" AND ", conditions));
    }
    Problem.require(
        b.path("sourceKey").isArray() && !b.path("sourceKey").isEmpty(),
        422,
        "SOURCE_KEY_REQUIRED",
        "Dataset必须配置稳定Root键");
    Set<String> rootKey = new HashSet<>();
    for (var key : b.path("sourceKey")) {
      String path = key.asText();
      Problem.require(
          path.startsWith(rootAlias + ".") && columns.containsKey(path),
          422,
          "SOURCE_KEY_REQUIRED",
          "来源键必须使用Root登记字段");
      rootKey.add(path.substring(rootAlias.length() + 1));
    }
    boolean unique =
        new HashSet<>(toStrings(ro.path("definition").path("primaryKey"))).equals(rootKey);
    for (var uk : ro.path("definition").path("uniqueKeys"))
      if (new HashSet<>(toStrings(uk)).equals(rootKey)) unique = true;
    Problem.require(unique, 422, "SOURCE_KEY_NOT_UNIQUE", "来源键必须匹配登记的PK/Unique Key");
    Problem.require(
        b.path("output").isObject() && !b.path("output").isEmpty(),
        422,
        "DATASET_OUTPUT",
        "请选择输出字段");
    b.path("output")
        .fields()
        .forEachRemaining(
            e ->
                Problem.require(
                    columns.containsKey(e.getValue().asText()),
                    422,
                    "DATASET_FIELD",
                    "输出引用未登记字段：" + e.getValue().asText()));
    for (String key : List.of("existingNoField", "sourceVersionField", "sourceStatusField"))
      if (b.hasNonNull(key))
        Problem.require(
            columns.containsKey(b.path(key).asText()), 422, "DATASET_FIELD", "来源观察字段不存在");
    var inc = b.path("incremental");
    Problem.require(
        Set.of("RESCAN_UNISSUED", "UNIFIED_VIEW_VERSION", "NOTIFICATIONS", "DEPENDENCY_RESCAN")
            .contains(inc.path("referenceStrategy").asText("RESCAN_UNISSUED")),
        422,
        "REFERENCE_PROPAGATION",
        "必须声明参考表变化传播方式");
    return new Plan(from.toString(), columns, objects, b);
  }

  void addObject(
      Map<String, Column> fields, Map<String, JsonNode> objects, String alias, JsonNode o) {
    SourceMetadata.ident(alias);
    Problem.require(!objects.containsKey(alias), 422, "JOIN_ALIAS", "对象别名重复");
    objects.put(alias, o);
    for (var f : o.path("definition").path("fields")) {
      String name = f.path("name").asText();
      fields.put(alias + "." + name, new Column(alias + "." + name, f, q(alias) + "." + q(name)));
    }
  }

  String table(JsonNode o) {
    return q(o.path("schemaName").asText()) + "." + q(o.path("objectName").asText());
  }

  static List<String> toStrings(JsonNode n) {
    List<String> x = new ArrayList<>();
    n.forEach(v -> x.add(v.asText()));
    return x;
  }

  Set<String> needed(Plan p) {
    Set<String> used = new LinkedHashSet<>();
    p.definition.path("output").elements().forEachRemaining(x -> used.add(x.asText()));
    p.definition.path("sourceKey").forEach(x -> used.add(x.asText()));
    for (String field : List.of("existingNoField", "sourceVersionField", "sourceStatusField"))
      if (p.definition.hasNonNull(field)) used.add(p.definition.path(field).asText());
    collectPaths(p.definition.path("candidateFilter"), used);
    for (var j : p.definition.path("joins")) {
      for (var on : j.path("on")) {
        used.add(on.path("left").asText());
        used.add(on.path("right").asText());
      }
      if (j.hasNonNull("activeField"))
        used.add(j.path("alias").asText() + "." + j.path("activeField").asText());
    }
    return used;
  }

  void collectPaths(JsonNode ast, Set<String> paths) {
    if (ast.isObject() && ast.has("field")) paths.add(ast.path("field").asText());
    for (var node : ast) collectPaths(node, paths);
  }

  List<JsonNode> keyValues(JsonNode b, String key) {
    var keys = b.path("sourceKey");
    Problem.require(
        key != null && !key.isBlank(), 422, "SOURCE_KEY_REQUIRED", "必须提供sourceRecordKey");
    if (keys.size() == 1) return List.of(TextNode.valueOf(key));
    JsonNode n = Json.read(key);
    Problem.require(
        n.isArray() && n.size() == keys.size(),
        422,
        "SOURCE_KEY_SHAPE",
        "组合键必须为按sourceKey顺序的JSON数组");
    List<JsonNode> values = new ArrayList<>();
    n.forEach(values::add);
    return values;
  }

  String recordKey(JsonNode b, JsonNode row) {
    ArrayNode keys = Json.arr();
    for (var k : b.path("sourceKey")) {
      var v = row.path(k.asText());
      Problem.require(!v.isMissingNode() && !v.isNull(), 422, "SOURCE_KEY_REQUIRED", "来源键为空");
      keys.add(v);
    }
    return keys.size() == 1
        ? (keys.get(0).isNumber() ? Json.canonical(keys.get(0)) : keys.get(0).asText())
        : Json.canonical(keys);
  }

  public ObjectNode preview(Ctx c, JsonNode cfg, String key, JsonNode push) {
    Plan p = plan(c, cfg);
    var sys = sources.system(c, cfg.path("sourceSystemId").asText());
    if (!sys.path("type").asText().equals("DATABASE")) return apiPreview(c, cfg, p, key, push, sys);
    var select = new ArrayList<String>();
    for (var col : p.columns.values())
      if (needed(p).contains(col.path)) select.add(col.sql + " AS " + "\"" + col.path + "\"");
    var conditions = new ArrayList<String>();
    for (var k : p.definition.path("sourceKey"))
      conditions.add(p.columns.get(k.asText()).sql + "=?");
    String sql =
        "SELECT "
            + String.join(",", select)
            + " FROM "
            + p.from
            + " WHERE "
            + String.join(" AND ", conditions)
            + " LIMIT 3";
    try (var con = sources.connection(sys);
        var stmt = con.prepareStatement(sql)) {
      stmt.setQueryTimeout(10);
      var values = keyValues(p.definition, key);
      for (int i = 0; i < values.size(); i++) {
        var col = p.columns.get(p.definition.path("sourceKey").get(i).asText());
        if (family(col.field).equals("NUMBER"))
          stmt.setBigDecimal(i + 1, new BigDecimal(values.get(i).asText()));
        else stmt.setString(i + 1, values.get(i).asText());
      }
      ArrayNode rows = Json.arr();
      try (var rs = stmt.executeQuery()) {
        while (rs.next()) rows.add(readRow(rs));
      }
      Problem.require(!rows.isEmpty(), 422, "SOURCE_INCOMPLETE", "来源Root记录不存在或INNER JOIN未命中");
      Problem.require(rows.size() == 1, 409, "GRAIN_CONFLICT", "同一来源键关联得到多条记录，禁止随机选第一条");
      var row = (ObjectNode) rows.get(0);
      var joins = Json.arr();
      for (var j : p.definition.path("joins")) {
        String alias = j.path("alias").asText();
        boolean found = false;
        for (var f : p.objects.get(alias).path("definition").path("fields"))
          if (row.hasNonNull(alias + "." + f.path("name").asText())) found = true;
        var hit = Json.object("alias", alias, "matched", found, "on", j.path("on"));
        joins.add(hit);
        if (!found) {
          String policy = j.path("nullPolicy").asText();
          if (policy.equals("ERROR"))
            throw new Problem(
                422,
                "SOURCE_INCOMPLETE",
                "参考关联未命中：" + alias,
                Json.arr()
                    .add(
                        Json.object(
                            "fieldPath",
                            "/joins/" + alias,
                            "code",
                            "SOURCE_INCOMPLETE",
                            "join",
                            hit,
                            "sourceRecordKey",
                            key,
                            "sourceValue",
                            row,
                            "suggestion",
                            "请在ERP补齐关联记录")));
          if (policy.equals("DEFAULT")) {
            Problem.require(j.path("defaults").isObject(), 422, "JOIN_DEFAULT", "DEFAULT需要显式默认值");
            j.path("defaults")
                .fields()
                .forEachRemaining(e -> row.set(alias + "." + e.getKey(), e.getValue()));
          }
        }
        if (found && j.hasNonNull("activeField"))
          Problem.require(
              row.path(alias + "." + j.path("activeField").asText()).equals(j.path("activeValue")),
              422,
              "REFERENCE_INACTIVE",
              "来源参考记录已停用：" + alias);
      }
      return output(cfg, p, row, key, joins, sql);
    } catch (Problem e) {
      throw e;
    } catch (SQLException e) {
      throw new Problem(503, "SOURCE_OBJECT_CHANGED", "来源结构/权限或读取失败，请检查结构Diff与来源日志");
    } catch (NumberFormatException e) {
      throw new Problem(422, "SOURCE_KEY_SHAPE", "数值来源键非法");
    }
  }

  ObjectNode readRow(ResultSet rs) throws SQLException {
    ObjectNode row = Json.obj();
    var md = rs.getMetaData();
    for (int i = 1; i <= md.getColumnCount(); i++) {
      Object v = rs.getObject(i);
      String name = md.getColumnLabel(i);
      if (v == null) row.putNull(name);
      else if (v instanceof Timestamp t) row.put(name, t.toInstant().toString());
      else if (v instanceof java.sql.Date date) row.put(name, date.toLocalDate().toString());
      else if (v instanceof Double || v instanceof Float) row.put(name, rs.getBigDecimal(i));
      else if (md.getColumnTypeName(i).equals("jsonb") || md.getColumnTypeName(i).equals("json"))
        row.set(name, Json.read(v.toString()));
      else row.set(name, Json.M.valueToTree(v));
    }
    return row;
  }

  ObjectNode output(
      JsonNode cfg, Plan p, ObjectNode row, String key, ArrayNode joins, String explain) {
    var output = Json.obj();
    p.definition
        .path("output")
        .fields()
        .forEachRemaining(e -> output.set(e.getKey(), row.path(e.getValue().asText())));
    return Json.object(
        "sourceRecordKey",
        key,
        "root",
        row,
        "joins",
        joins,
        "output",
        output,
        "duplicateCount",
        0,
        "nullFields",
        Json.M.valueToTree(p.columns.keySet().stream().filter(x -> row.path(x).isNull()).toList()),
        "fieldTypes",
        Json.M.valueToTree(
            p.columns.entrySet().stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, x -> family(x.getValue().field)))),
        "sourceVersion",
        row.path(p.definition.path("sourceVersionField").asText()),
        "existingMaterialNo",
        row.path(p.definition.path("existingNoField").asText()),
        "readPlan",
        explain,
        "candidateEligible",
        !p.definition.path("candidateFilter").isObject()
            || p.definition.path("candidateFilter").isEmpty()
            || evaluate(p.definition.path("candidateFilter"), row),
        "sourceStatus",
        row.path(p.definition.path("sourceStatusField").asText()),
        "datasetVersionId",
        cfg.path("id"));
  }

  ObjectNode apiPreview(Ctx c, JsonNode cfg, Plan p, String key, JsonNode push, JsonNode sys) {
    JsonNode raw = push;
    if (raw == null || raw.isNull() || raw.isMissingNode()) {
      String resource = p.definition.path("resource").asText();
      Problem.require(
          resource.startsWith("/") && !resource.contains(".."),
          422,
          "API_RESOURCE",
          "API对象必须使用登记的相对资源路径");
      raw = httpGet(sys, resource.replace("{key}", encode(key)));
    }
    Problem.require(raw.isObject(), 422, "DATASET_SHAPE", "API输入必须为对象");
    String alias = p.definition.path("root").path("alias").asText("r");
    ObjectNode row = Json.obj();
    for (var f : p.objects.get(alias).path("definition").path("fields")) {
      String name = f.path("name").asText();
      if (needed(p).contains(alias + "." + name)) row.set(alias + "." + name, raw.path(name));
    }
    Set<String> names = new HashSet<>();
    p.objects
        .get(alias)
        .path("definition")
        .path("fields")
        .forEach(f -> names.add(f.path("name").asText()));
    if (push != null && !push.isNull() && !push.isMissingNode())
      raw.fieldNames()
          .forEachRemaining(
              x -> Problem.require(names.contains(x), 422, "UNKNOWN_FIELD", "API推送输入含未登记字段：" + x));
    Problem.require(
        recordKey(p.definition, row).equals(key), 422, "SOURCE_KEY_MISMATCH", "推送输入的来源键与请求不一致");
    Problem.require(
        p.definition.path("joins").isEmpty(), 422, "API_DATASET", "API来源必须返回契约定义的逻辑输入对象，关联由来源完成");
    return output(cfg, p, row, key, Json.arr(), "GET registered resource / signed API payload");
  }

  static String encode(String x) {
    return java.net.URLEncoder.encode(x, java.nio.charset.StandardCharsets.UTF_8);
  }

  public JsonNode httpGet(JsonNode sys, String path) {
    try {
      var profile = sys.path("connectionProfile");
      String base = profile.path("baseUrl").asText();
      SourceMetadata.url(base);
      sources.connections.destination(base);
      var builder =
          java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + path))
              .timeout(java.time.Duration.ofSeconds(profile.path("timeoutSeconds").asInt(5)))
              .GET();
      if (profile.has("tokenEnv"))
        builder.header(
            "Authorization",
            "Bearer " + sources.env(profile, "tokenEnv", sys.path("tenantId").asText()));
      var response =
          java.net.http.HttpClient.newHttpClient()
              .send(builder.build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      Problem.require(response.statusCode() == 200, 503, "SOURCE_UNAVAILABLE", "来源读取不可用");
      return Json.read(response.body());
    } catch (Problem e) {
      throw e;
    } catch (Exception e) {
      throw new Problem(503, "SOURCE_UNAVAILABLE", "来源读取不可用，请检查端点与凭据引用");
    }
  }

  public JsonNode scan(Ctx c, JsonNode cfg, JsonNode cursor, boolean manual) {
    Plan p = plan(c, cfg);
    var sys = sources.system(c, cfg.path("sourceSystemId").asText());
    ArrayNode result = Json.arr();
    if (!sys.path("type").asText().equals("DATABASE")) {
      String resource = p.definition.path("scanResource").asText();
      Problem.require(
          resource.startsWith("/") && !resource.contains(".."), 422, "API_RESOURCE", "请登记扫描资源");
      JsonNode list = httpGet(sys, resource);
      Problem.require(list.isArray(), 422, "SOURCE_SCAN_SHAPE", "扫描响应必须是逻辑来源记录数组");
      for (var row : list) {
        String alias = p.definition.path("root").path("alias").asText("r");
        var flat = Json.obj();
        row.fields().forEachRemaining(e -> flat.set(alias + "." + e.getKey(), e.getValue()));
        var filter = p.definition.path("candidateFilter");
        if (filter.isObject() && !filter.isEmpty() && !evaluate(filter, flat)) continue;
        result.add(
            Json.object(
                "key",
                recordKey(p.definition, flat),
                "version",
                flat.path(p.definition.path("sourceVersionField").asText())));
      }
      return result;
    }
    String alias = p.definition.path("root").path("alias").asText("r");
    var root = p.objects.get(alias);
    List<String> columns = new ArrayList<>();
    for (var f : root.path("definition").path("fields"))
      columns.add(
          q(alias)
              + "."
              + q(f.path("name").asText())
              + " AS \""
              + alias
              + "."
              + f.path("name").asText()
              + "\"");
    String query =
        "SELECT "
            + String.join(",", columns)
            + " FROM "
            + table(root)
            + " "
            + q(alias)
            + " ORDER BY "
            + p.columns.get(p.definition.path("sourceKey").path(0).asText()).sql;
    try (var con = sources.connection(sys);
        var stmt = con.createStatement()) {
      stmt.setQueryTimeout(30);
      stmt.setFetchSize(500);
      try (var rs = stmt.executeQuery(query)) {
        while (rs.next()) {
          var row = readRow(rs);
          String key = recordKey(p.definition, row);
          var filter = p.definition.path("candidateFilter");
          if (filter.isObject() && !filter.isEmpty() && !evaluate(filter, row)) continue;
          String
              strategy =
                  p.definition.path("incremental").path("strategy").asText("PENDING_PREDICATE"),
              field = p.definition.path("sourceVersionField").asText();
          var previous = cursor.path("value");
          boolean newer = previous.isMissingNode() || compare(row.path(field), previous) > 0;
          if (strategy.equals("VERSION_COLUMN")) {
            var observed =
                db.maybe(
                    "select source_version from material_source_snapshot where tenant_id=? and"
                        + " source_system_id=? and dataset_code=? and source_record_key=?",
                    c.tid(),
                    SourceMetadata.id(cfg.path("sourceSystemId").asText()),
                    cfg.path("code").asText(),
                    key);
            newer =
                observed == null
                    || !row.path(field).asText().equals(observed.path("sourceVersion").asText());
          }
          boolean issued =
              db.count(
                      "select count(*) from material_assignment where tenant_id=? and"
                          + " dataset_code=? and source_record_key=?",
                      c.tid(),
                      cfg.path("code").asText(),
                      key)
                  > 0;
          if (manual
              || strategy.equals("PENDING_PREDICATE")
              || strategy.equals("REQUEST_TRIGGER")
              || newer
              || (!issued
                  && p.definition
                      .path("incremental")
                      .path("referenceStrategy")
                      .asText("RESCAN_UNISSUED")
                      .equals("RESCAN_UNISSUED")))
            result.add(Json.object("key", key, "version", row.path(field)));
        }
      }
      return result;
    } catch (SQLException e) {
      throw new Problem(503, "SOURCE_SCAN_ERROR", "来源扫描失败，请检查结构与只读权限");
    }
  }

  static int compare(JsonNode a, JsonNode b) {
    if (a.isNumber() && b.isNumber()) return a.decimalValue().compareTo(b.decimalValue());
    return a.asText().compareTo(b.asText());
  }

  boolean evaluate(JsonNode ast, JsonNode row) {
    var ctx = Json.obj();
    row.fields()
        .forEachRemaining(
            e -> {
              String[] path = e.getKey().split("\\.", 2);
              var node = ctx.withObject("/" + path[0]);
              node.set(path[1], e.getValue());
            });
    return Json.truth(rules.eval(ast, ctx));
  }
}
