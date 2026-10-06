package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Service;

@Service
public class SourceMetadata {
  final java.util.concurrent.ConcurrentHashMap<String, JsonNode> health =
      new java.util.concurrent.ConcurrentHashMap<>();
  final Db db;
  final Models models;
  final SourceConnections connections;
  final java.util.concurrent.Semaphore readSlots = new java.util.concurrent.Semaphore(32, true);

  public SourceMetadata(Db d, Models m, SourceConnections p) {
    db = d;
    models = m;
    connections = p;
  }

  static UUID id(String x) {
    try {
      return UUID.fromString(x);
    } catch (Exception e) {
      throw new Problem(400, "BAD_ID", "UUID格式错误");
    }
  }

  public void scope(Ctx c, String sid) {
    var s = db.one("select * from source_system where tenant_id=? and id=?", c.tid(), id(sid));
    var member =
        db.one(
            "select source_scope from tenant_member where tenant_id=? and user_id=? and active",
            c.tid(),
            c.uid());
    boolean allowed = false;
    for (var x : member.path("sourceScope"))
      if (x.asText().equals("*")
          || x.asText().equals(sid)
          || x.asText().equals(Json.text(s, "code"))) allowed = true;
    Problem.require(allowed, 403, "SOURCE_SCOPE", "数据源不在当前成员授权范围");
  }

  public ObjectNode system(Ctx c, String sid) {
    scope(c, sid);
    return db.one("select * from source_system where tenant_id=? and id=?", c.tid(), id(sid));
  }

  public List<ObjectNode> systems(Ctx c) {
    c.check("SOURCE_READ", null);
    return db.list("select * from source_system where tenant_id=? order by code", c.tid()).stream()
        .filter(
            s -> {
              try {
                scope(c, Json.text(s, "id"));
                return true;
              } catch (Problem p) {
                return false;
              }
            })
        .map(
            s -> {
              s.remove("credentialHash");
              return s;
            })
        .toList();
  }

  public JsonNode save(Ctx c, JsonNode b, String sid, String version) {
    c.check("SOURCE_EDIT", null);
    var profile = b.path("connectionProfile");
    if (sid != null) {
      var old = system(c, sid);
      Db.version(old, version);
      if (profile.isMissingNode()) profile = old.path("connectionProfile");
    }
    Problem.require(profile.isObject(), 422, "CONNECTION_PROFILE", "请提供连接配置");
    rejectCredentials(profile);
    if (sid != null) {
      var old = system(c, sid);
      if (db.count(
              "select count(*) from release_package where tenant_id=? and source_system_id=? and"
                  + " ever_published",
              c.tid(),
              id(sid))
          > 0) {
        for (String endpoint : List.of("jdbcUrl", "baseUrl", "writebackBaseUrl"))
          Problem.require(
              old.path("connectionProfile").path(endpoint).equals(profile.path(endpoint)),
              409,
              "SOURCE_ENDPOINT_IMMUTABLE",
              "已发布来源地址不可改指向其他事实源，请新建来源");
      }
    }
    String type =
        b.path("type").asText(sid == null ? "DATABASE" : system(c, sid).path("type").asText());
    Problem.require(
        Set.of("DATABASE", "REST", "INTERFACE_PLATFORM", "FILE").contains(type),
        422,
        "SOURCE_TYPE",
        "未知来源类型");
    if (type.equals("DATABASE")) {
      String url = profile.path("jdbcUrl").asText();
      Problem.require(
          url.startsWith("jdbc:postgresql://") && !url.matches("(?i).*([?&](password|user)=|@).*"),
          422,
          "CONNECTION_PROFILE",
          "JDBC仅接受登记的PostgreSQL端点且不允许内嵌凭据");
    } else if (!type.equals("FILE")) url(profile.path("baseUrl").asText());
    UUID key = sid == null ? UUID.randomUUID() : id(sid);
    if (sid == null) {
      String code = b.path("code").asText();
      Problem.require(code.matches("[A-Za-z][A-Za-z0-9_-]{0,79}"), 422, "SOURCE_CODE", "数据源code无效");
      db.run(
          "insert into"
              + " source_system(id,tenant_id,code,name,type,environment,status,connection_profile,owner_user_id,credential_hash)"
              + " values(?,?,?,?,?,?,'DRAFT',?::jsonb,?,?)",
          key,
          c.tid(),
          code,
          b.path("name").asText(code),
          type,
          b.path("environment").asText("DEV"),
          Json.str(profile),
          c.uid(),
          b.hasNonNull("clientKey") ? Json.hash(b.path("clientKey").asText()) : null);
    } else {
      String status = b.path("status").asText(system(c, sid).path("status").asText());
      Problem.require(
          Set.of("DRAFT", "ACTIVE", "SUSPENDED", "RETIRED").contains(status),
          422,
          "SOURCE_STATE",
          "来源状态无效");
      var old = system(c, sid);
      Problem.require(
          db.run(
                  "update source_system set"
                      + " name=?,status=?,connection_profile=?::jsonb,row_version=row_version+1"
                      + " where tenant_id=? and id=? and row_version=?",
                  b.path("name").asText(system(c, sid).path("name").asText()),
                  status,
                  Json.str(profile),
                  c.tid(),
                  key,
                  old.path("rowVersion").asLong())
              == 1,
          409,
          "VERSION_CONFLICT",
          "来源配置已被其他操作修改");
    }
    db.log(
        c,
        "SOURCE_SYSTEM",
        key.toString(),
        1,
        sid == null ? "CREATE" : "UPDATE",
        b.path("reason").asText("来源配置"),
        Json.object("type", type),
        null);
    var result = system(c, key.toString());
    result.remove("credentialHash");
    return result;
  }

  void rejectCredentials(JsonNode n) {
    if (n.isObject())
      n.fields()
          .forEachRemaining(
              e -> {
                Problem.require(
                    !Set.of("password", "token", "secret", "authorization")
                        .contains(e.getKey().toLowerCase()),
                    422,
                    "CREDENTIAL_REFERENCE",
                    "凭据必须使用环境引用，不保存明文");
                rejectCredentials(e.getValue());
              });
    else if (n.isArray()) n.forEach(this::rejectCredentials);
  }

  static void url(String raw) {
    try {
      var u = java.net.URI.create(raw);
      Problem.require(
          Set.of("http", "https").contains(u.getScheme())
              && u.getHost() != null
              && u.getUserInfo() == null
              && u.getRawQuery() == null
              && u.getRawFragment() == null,
          422,
          "CONNECTION_PROFILE",
          "来源地址无效或包含凭据");
    } catch (IllegalArgumentException e) {
      throw new Problem(422, "CONNECTION_PROFILE", "来源地址无效");
    }
  }

  String env(JsonNode p, String key, String tenant) {
    String ref = p.path(key).asText();
    Problem.require(
        ref.matches("[A-Z][A-Z0-9_]{1,100}"), 422, "CREDENTIAL_REFERENCE", "缺少凭据环境变量引用：" + key);
    connections.binding(
        ref, p.has("jdbcUrl") ? p.path("jdbcUrl").asText() : p.path("baseUrl").asText(), tenant);
    String v = System.getenv(ref);
    Problem.require(v != null && !v.isBlank(), 503, "CREDENTIAL_MISSING", "凭据引用尚未配置：" + ref);
    return v;
  }

  public Connection connection(JsonNode sys) throws SQLException {
    try {
      Problem.require(
          readSlots.tryAcquire(10, java.util.concurrent.TimeUnit.SECONDS),
          503,
          "SOURCE_READ_BUSY",
          "来源读取繁忙，请稍后重试");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SQLException("Interrupted");
    }
    try {
      var p = sys.path("connectionProfile");
      connections.destination(p.path("jdbcUrl").asText());
      var con =
          DriverManager.getConnection(
              p.path("jdbcUrl").asText(),
              env(p, "userEnv", sys.path("tenantId").asText()),
              env(p, "passwordEnv", sys.path("tenantId").asText()));
      health.put(
          sys.path("id").asText(),
          Json.object("status", "UP", "checkedAt", java.time.Instant.now().toString()));
      con.setReadOnly(true);
      con.setAutoCommit(false);
      var closed = new java.util.concurrent.atomic.AtomicBoolean();
      return (Connection)
          java.lang.reflect.Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                try {
                  return method.invoke(con, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                  throw e.getCause();
                } finally {
                  if (method.getName().equals("close") && closed.compareAndSet(false, true))
                    readSlots.release();
                }
              });
    } catch (SQLException | RuntimeException e) {
      health.put(
          sys.path("id").asText(),
          Json.object("status", "FAILED", "checkedAt", java.time.Instant.now().toString()));
      readSlots.release();
      throw e;
    }
  }

  public JsonNode test(Ctx c, String sid) {
    c.check("SOURCE_EDIT", null);
    var sys = system(c, sid);
    try {
      if (sys.path("type").asText().equals("DATABASE")) {
        try (var con = connection(sys);
            var stmt = con.createStatement()) {
          stmt.setQueryTimeout(5);
          stmt.executeQuery("select 1").close();
          Problem.require(con.isReadOnly(), 422, "READ_ONLY_REQUIRED", "来源读取必须只读");
          return Json.object(
              "success",
              true,
              "readOnly",
              true,
              "databaseProduct",
              con.getMetaData().getDatabaseProductName());
        }
      } else {
        var client =
            java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
        String base = sys.path("connectionProfile").path("baseUrl").asText();
        connections.destination(base);
        var req =
            java.net.http.HttpRequest.newBuilder(java.net.URI.create(base + "/health"))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();
        var res = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
        Problem.require(res.statusCode() == 200, 503, "SOURCE_UNAVAILABLE", "来源健康检查未通过");
        return Json.object("success", true, "readOnly", true);
      }
    } catch (Problem p) {
      throw p;
    } catch (Exception e) {
      throw new Problem(503, "SOURCE_UNAVAILABLE", "来源连接失败，请检查端点、凭据引用与读取权限");
    }
  }

  static String ident(String value) {
    String s = value.trim();
    if ((s.startsWith("\"") && s.endsWith("\""))
        || (s.startsWith("`") && s.endsWith("`"))
        || (s.startsWith("[") && s.endsWith("]"))) s = s.substring(1, s.length() - 1);
    Problem.require(
        s.matches("[A-Za-z_][A-Za-z0-9_$]{0,127}"), 422, "SOURCE_IDENTIFIER", "对象或字段标识符无效");
    return s;
  }

  static List<String> split(String s, char delimiter) {
    List<String> out = new ArrayList<>();
    int depth = 0, start = 0;
    char quote = 0;
    for (int i = 0; i < s.length(); i++) {
      char k = s.charAt(i);
      if (quote != 0) {
        if (k == quote) {
          if (i + 1 < s.length() && s.charAt(i + 1) == quote) i++;
          else quote = 0;
        }
        continue;
      }
      if (k == '\'' || k == '\"' || k == '`') {
        quote = k;
        continue;
      }
      if (k == '(') depth++;
      if (k == ')') depth--;
      if (k == delimiter && depth == 0) {
        out.add(s.substring(start, i).trim());
        start = i + 1;
      }
    }
    Problem.require(depth == 0 && quote == 0, 422, "DDL_SYNTAX", "DDL括号或引号未闭合");
    if (start < s.length()) out.add(s.substring(start).trim());
    return out;
  }

  static ArrayNode names(String s) {
    ArrayNode a = Json.arr();
    for (String x : split(s, ',')) a.add(ident(x));
    return a;
  }

  public ArrayNode parseDdl(String text) {
    Problem.require(text.length() <= 2_000_000, 422, "DDL_SIZE", "DDL最大2MB");
    text = text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)--[^\\r\\n]*", "");
    ArrayNode out = Json.arr();
    Pattern
        table =
            Pattern.compile(
                "(?is)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([^\\s(]+)\\s*\\((.*)\\)\\s*(?:ENGINE[^;]*)?$"),
        fk =
            Pattern.compile(
                "(?is)(?:CONSTRAINT\\s+[^\\s]+\\s+)?FOREIGN\\s+KEY\\s*\\(([^)]+)\\)\\s+REFERENCES\\s+([^\\s(]+)\\s*\\(([^)]+)\\).*");
    for (String statement : split(text, ';')) {
      if (statement.isBlank()) continue;
      if (statement.toUpperCase().startsWith("COMMENT ON ")) continue;
      var m = table.matcher(statement);
      Problem.require(m.matches(), 422, "DDL_UNSUPPORTED", "仅解析CREATE TABLE和注释；不执行任何DDL");
      String[] qualified = m.group(1).split("\\.");
      String name = ident(qualified[qualified.length - 1]),
          schema = qualified.length > 1 ? ident(qualified[0]) : "public";
      var fields = Json.arr();
      var primary = Json.arr();
      var unique = Json.arr();
      var foreign = Json.arr();
      for (String part : split(m.group(2), ',')) {
        String upper = part.toUpperCase(Locale.ROOT);
        Matcher fm = fk.matcher(part);
        if (fm.matches()) {
          foreign.add(
              Json.object(
                  "columns",
                  names(fm.group(1)),
                  "targetObject",
                  ident(fm.group(2).replaceFirst("^.*\\.", "")),
                  "targetColumns",
                  names(fm.group(3))));
          continue;
        }
        var pm =
            Pattern.compile("(?is)(?:CONSTRAINT\\s+\\S+\\s+)?PRIMARY\\s+KEY\\s*\\(([^)]+)\\).*")
                .matcher(part);
        if (pm.matches()) {
          primary = names(pm.group(1));
          continue;
        }
        var um =
            Pattern.compile(
                    "(?is)(?:CONSTRAINT\\s+\\S+\\s+)?UNIQUE(?:\\s+KEY\\s+\\S+)?\\s*\\(([^)]+)\\).*")
                .matcher(part);
        if (um.matches()) {
          unique.add(names(um.group(1)));
          continue;
        }
        Problem.require(
            !upper.startsWith("CONSTRAINT ") && !upper.startsWith("CHECK "),
            422,
            "DDL_UNSUPPORTED",
            "请通过JDBC或手工元数据处理未支持的表约束");
        var cm =
            Pattern.compile(
                    "(?is)^([^\\s]+)\\s+([A-Z][A-Z0-9_"
                        + " ]*?)(?:\\s*\\(([^)]+)\\))?(?=\\s+(?:NOT|NULL|DEFAULT|PRIMARY|UNIQUE|REFERENCES|COMMENT|CHECK)\\b|$)(.*)$")
                .matcher(part);
        Problem.require(cm.matches(), 422, "DDL_COLUMN", "列定义无法解析：" + part.split("\\s+")[0]);
        String column = ident(cm.group(1)), type = cm.group(2).trim().toUpperCase();
        String rest = cm.group(4);
        var f =
            Json.object(
                "name",
                column,
                "dataType",
                type,
                "nullable",
                !rest.toUpperCase().contains("NOT NULL")
                    && !rest.toUpperCase().contains("PRIMARY KEY"));
        if (cm.group(3) != null) {
          var sizes = cm.group(3).split(",");
          if (type.matches(".*(CHAR|BINARY).*")) f.put("length", Integer.parseInt(sizes[0].trim()));
          else {
            f.put("precision", Integer.parseInt(sizes[0].trim()));
            if (sizes.length > 1) f.put("scale", Integer.parseInt(sizes[1].trim()));
          }
        }
        var dm =
            Pattern.compile(
                    "(?is)DEFAULT\\s+(.+?)(?=\\s+(?:NOT NULL|PRIMARY|UNIQUE|COMMENT|CHECK)\\b|$)")
                .matcher(rest);
        if (dm.find()) f.put("default", dm.group(1).trim());
        var comment = Pattern.compile("(?is)COMMENT\\s+'([^']*)'").matcher(rest);
        if (comment.find()) f.put("comment", comment.group(1));
        if (rest.toUpperCase().contains("PRIMARY KEY")) primary.add(column);
        if (rest.toUpperCase().contains("UNIQUE")) unique.add(Json.arr().add(column));
        fields.add(f);
      }
      Problem.require(!fields.isEmpty(), 422, "DDL_COLUMN", "表必须包含字段");
      out.add(
          Json.object(
              "objectName",
              name,
              "schemaName",
              schema,
              "objectType",
              "TABLE",
              "fields",
              fields,
              "primaryKey",
              primary,
              "uniqueKeys",
              unique,
              "foreignKeys",
              foreign));
    }
    Problem.require(!out.isEmpty(), 422, "DDL_EMPTY", "DDL未包含表");
    return out;
  }

  public JsonNode importDdl(Ctx c, JsonNode b) {
    c.check("SOURCE_IMPORT", null);
    String sid = b.path("sourceSystemId").asText();
    system(c, sid);
    var parsed = parseDdl(b.path("ddl").asText());
    ArrayNode result = Json.arr();
    for (var definition : parsed)
      result.add(createObject(c, sid, definition, "DDL", b.path("ddl").asText()));
    db.log(
        c,
        "SOURCE_SYSTEM",
        sid,
        1,
        "IMPORT_DDL",
        "仅解析文本",
        Json.object("objectCount", result.size()),
        null);
    return result;
  }

  public ObjectNode createObject(
      Ctx c, String sid, JsonNode definition, String method, String ddl) {
    system(c, sid);
    String name = ident(definition.path("objectName").asText()),
        schema = ident(definition.path("schemaName").asText("public"));
    db.jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        c.tenant() + sid + schema + name);
    int version =
        (int)
            db.count(
                "select coalesce(max(version_no),0)+1 from source_object_version where tenant_id=?"
                    + " and source_system_id=? and schema_name=? and object_name=?",
                c.tid(),
                id(sid),
                schema,
                name);
    var key = UUID.randomUUID();
    db.run(
        "insert into"
            + " source_object_version(id,tenant_id,source_system_id,object_name,schema_name,object_type,version_no,structure_hash,definition,import_method,ddl_text)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb,?,?)",
        key,
        c.tid(),
        id(sid),
        name,
        schema,
        definition.path("objectType").asText("TABLE"),
        version,
        Json.hash(Json.canonical(definition)),
        Json.str(definition),
        method,
        ddl);
    var result = object(c, key.toString());
    for (var previous :
        db.list(
            "select id from source_object_version where tenant_id=? and source_system_id=? and"
                + " schema_name=? and object_name=? and ever_published and id<>?",
            c.tid(),
            id(sid),
            schema,
            name,
            key)) {
      var change = diff(c, previous.path("id").asText(), key.toString());
      if (!change.path("changes").isEmpty())
        db.run(
            "insert into"
                + " source_structure_alert(id,tenant_id,source_system_id,old_object_id,new_object_id,incompatible,details)"
                + " values(?,?,?,?,?,?,?::jsonb) on conflict do nothing",
            UUID.randomUUID(),
            c.tid(),
            id(sid),
            id(previous.path("id").asText()),
            key,
            change.path("incompatible").asBoolean(),
            Json.str(change));
    }
    return result;
  }

  public ObjectNode object(Ctx c, String oid) {
    var o =
        db.one("select * from source_object_version where tenant_id=? and id=?", c.tid(), id(oid));
    scope(c, Json.text(o, "sourceSystemId"));
    return o;
  }

  public JsonNode objects(Ctx c, String sid) {
    c.check("SOURCE_READ", null);
    system(c, sid);
    return Json.M.valueToTree(
        db.list(
            "select * from source_object_version where tenant_id=? and source_system_id=? order by"
                + " object_name,version_no desc",
            c.tid(),
            id(sid)));
  }

  public JsonNode discover(Ctx c, String sid, JsonNode b) {
    c.check("SOURCE_IMPORT", null);
    var sys = system(c, sid);
    Problem.require(
        sys.path("type").asText().equals("DATABASE"), 422, "SOURCE_TYPE", "元数据扫描仅用于数据库来源");
    ArrayNode out = Json.arr();
    try (var con = connection(sys)) {
      var md = con.getMetaData();
      for (var requested : b.path("objects")) {
        String schema = requested.path("schemaName").asText("public"),
            name = requested.path("objectName").asText();
        ident(schema);
        ident(name);
        var fields = Json.arr();
        var primary = new TreeMap<Integer, String>();
        var foreign = new LinkedHashMap<String, ObjectNode>();
        var unique = Json.arr();
        String objectType = "TABLE";
        try (var rs = md.getTables(null, schema, name, new String[] {"TABLE", "VIEW"})) {
          Problem.require(rs.next(), 422, "SOURCE_OBJECT_CHANGED", "来源对象已删除或不可读取");
          objectType = rs.getString("TABLE_TYPE");
        }
        try (var rs = md.getColumns(null, schema, name, null)) {
          while (rs.next()) {
            fields.add(
                Json.object(
                    "name",
                    rs.getString("COLUMN_NAME"),
                    "dataType",
                    rs.getString("TYPE_NAME").toUpperCase(),
                    "precision",
                    rs.getInt("COLUMN_SIZE"),
                    "scale",
                    rs.getInt("DECIMAL_DIGITS"),
                    "nullable",
                    rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                    "default",
                    rs.getString("COLUMN_DEF"),
                    "comment",
                    rs.getString("REMARKS")));
          }
        }
        try (var rs = md.getPrimaryKeys(null, schema, name)) {
          while (rs.next()) primary.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        try (var rs = md.getImportedKeys(null, schema, name)) {
          while (rs.next()) {
            String fkName = rs.getString("FK_NAME");
            var fkDef =
                foreign.computeIfAbsent(
                    fkName,
                    k ->
                        Json.object(
                            "columns",
                            Json.arr(),
                            "targetObject",
                            get(rs, "PKTABLE_NAME"),
                            "targetColumns",
                            Json.arr()));
            ((ArrayNode) fkDef.path("columns")).add(rs.getString("FKCOLUMN_NAME"));
            ((ArrayNode) fkDef.path("targetColumns")).add(rs.getString("PKCOLUMN_NAME"));
          }
        }
        try (var rs = md.getIndexInfo(null, schema, name, true, false)) {
          var indexes = new LinkedHashMap<String, ArrayNode>();
          while (rs.next()) {
            String col = rs.getString("COLUMN_NAME"), index = rs.getString("INDEX_NAME");
            if (col != null && index != null)
              indexes.computeIfAbsent(index, k -> Json.arr()).add(col);
          }
          indexes.values().forEach(unique::add);
        }
        var pk = Json.arr();
        primary.values().forEach(pk::add);
        var fks = Json.arr();
        foreign.values().forEach(fks::add);
        out.add(
            createObject(
                c,
                sid,
                Json.object(
                    "objectName",
                    name,
                    "schemaName",
                    schema,
                    "objectType",
                    objectType,
                    "fields",
                    fields,
                    "primaryKey",
                    pk,
                    "uniqueKeys",
                    unique,
                    "foreignKeys",
                    fks),
                "JDBC",
                null));
      }
      return out;
    } catch (Problem p) {
      throw p;
    } catch (SQLException e) {
      throw new Problem(503, "SOURCE_METADATA_ERROR", "来源元数据不可读取，请检查只读账号和对象授权");
    }
  }

  static String get(ResultSet r, String key) {
    try {
      return r.getString(key);
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  public JsonNode suggestions(Ctx c, String oid) {
    c.check("SOURCE_READ", null);
    var object = object(c, oid);
    ArrayNode out = Json.arr();
    for (var fk : object.path("definition").path("foreignKeys")) {
      var target =
          db.maybe(
              "select * from source_object_version where tenant_id=? and source_system_id=? and"
                  + " object_name=? order by version_no desc limit 1",
              c.tid(),
              id(object.path("sourceSystemId").asText()),
              fk.path("targetObject").asText());
      if (target != null)
        out.add(
            Json.object(
                "leftObjectVersionId",
                oid,
                "rightObjectVersionId",
                target.path("id"),
                "leftFields",
                fk.path("columns"),
                "rightFields",
                fk.path("targetColumns"),
                "requiresConfirmation",
                true,
                "proposedCardinality",
                "N:1"));
    }
    return out;
  }

  public JsonNode alerts(Ctx c, String oid) {
    object(c, oid);
    c.check("SOURCE_READ", null);
    return Json.M.valueToTree(
        db.list(
            "select * from source_structure_alert where tenant_id=? and old_object_id=? order by"
                + " created_at desc",
            c.tid(),
            id(oid)));
  }

  public JsonNode diff(Ctx c, String oid, String against) {
    var old = object(c, oid);
    var next = object(c, against);
    Problem.require(
        old.path("sourceSystemId").equals(next.path("sourceSystemId"))
            && old.path("objectName").equals(next.path("objectName")),
        422,
        "OBJECT_DIFF",
        "只能比较同来源同对象版本");
    var changes = Json.arr();
    var a = new LinkedHashMap<String, JsonNode>();
    var b = new LinkedHashMap<String, JsonNode>();
    old.path("definition").path("fields").forEach(f -> a.put(f.path("name").asText(), f));
    next.path("definition").path("fields").forEach(f -> b.put(f.path("name").asText(), f));
    var keys = new LinkedHashSet<>(a.keySet());
    keys.addAll(b.keySet());
    for (String key : keys)
      if (!Objects.equals(a.get(key), b.get(key)))
        changes.add(Json.object("field", key, "before", a.get(key), "after", b.get(key)));
    for (String key : List.of("primaryKey", "foreignKeys", "uniqueKeys"))
      if (!old.path("definition").path(key).equals(next.path("definition").path(key)))
        changes.add(
            Json.object(
                "constraint",
                key,
                "before",
                old.path("definition").path(key),
                "after",
                next.path("definition").path(key)));
    return Json.object(
        "changes",
        changes,
        "incompatible",
        changes.findValues("constraint").size() > 0
            || keys.stream()
                .anyMatch(
                    k ->
                        a.containsKey(k)
                            && (!b.containsKey(k)
                                || !a.get(k).path("dataType").equals(b.get(k).path("dataType"))
                                || a.get(k).path("precision").asInt()
                                    > b.get(k).path("precision").asInt()
                                || a.get(k).path("nullable").asBoolean()
                                    && !b.get(k).path("nullable").asBoolean())),
        "affectedReleases",
        db.list(
            "select id,code,status,dataset_id,schema_id,mapping_id,identity_id,code_rule_id from"
                + " release_package where tenant_id=? and dependency_snapshot::text like ?",
            c.tid(),
            "%" + oid + "%"),
        "lastSuccessfulSync",
        db.list(
            "select dataset_code,max(last_read_at) as last_observed_at from"
                + " material_source_snapshot where tenant_id=? and source_system_id=? group by"
                + " dataset_code",
            c.tid(),
            id(old.path("sourceSystemId").asText())),
        "affectedSchemaAttributes",
        db.list(
            "select id,code,dependency_snapshot->'schema'->'attributes' as attributes from"
                + " release_package where tenant_id=? and dependency_snapshot::text like ?",
            c.tid(),
            "%" + oid + "%"),
        "pendingUnissued",
        db.count(
            "select count(*) from processing_task where tenant_id=? and assignment_id is null",
            c.tid()));
  }
}
