package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Integrations {
  final Db db;
  final Models models;
  final Materials materials;
  final Rules rules;
  final Auth auth;
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

  public Integrations(Db d, Models m, Materials a, Rules r, Auth u) {
    db = d;
    models = m;
    materials = a;
    rules = r;
    auth = u;
  }

  public ObjectNode system(Ctx c, String id) {
    c.check("INTEGRATE", null);
    return db.one(
        "select"
            + " id,tenant_id,code,name,direction,config,active,paused,default_mapping_id,row_version"
            + " from integration_system where tenant_id=? and id=?",
        c.tid(),
        models.uuid(id));
  }

  public List<ObjectNode> systems(Ctx c) {
    c.check("INTEGRATE", null);
    return db.list(
        "select"
            + " id,tenant_id,code,name,direction,config,active,paused,default_mapping_id,row_version"
            + " from integration_system where tenant_id=? order by code",
        c.tid());
  }

  void config(JsonNode b) {
    Problem.require(
        b.path("minimumIntervalMs").asInt(0) >= 0
            && b.path("minimumIntervalMs").asInt(0) <= 3600000,
        422,
        "INTEGRATION_FREQUENCY",
        "发送最小间隔须0—3600000毫秒");
    String url = b.path("url").asText("");
    if (!url.isEmpty()) {
      URI u;
      try {
        u = URI.create(url);
      } catch (Exception e) {
        throw new Problem(422, "INTEGRATION_URL", "接口地址格式错误");
      }
      Problem.require(
          Set.of("http", "https").contains(u.getScheme())
              && u.getHost() != null
              && u.getUserInfo() == null,
          422,
          "INTEGRATION_URL",
          "接口须HTTP(S)，凭证不要写入URL");
    }
    Problem.require(
        b.path("timeoutMs").asInt(3000) > 0 && b.path("timeoutMs").asInt(3000) <= 60000,
        422,
        "INTEGRATION_TIMEOUT",
        "超时须1—60000ms");
  }

  public JsonNode saveSystem(Ctx c, String id, JsonNode b, String v) {
    c.check("INTEGRATE", null);
    config(b.path("config"));
    if (id == null) {
      UUID sid = UUID.randomUUID();
      Problem.require(!Json.text(b, "code").isBlank(), 422, "SYSTEM_CODE", "系统code必填");
      db.run(
          "insert into integration_system(id,tenant_id,code,name,direction,config,credential_hash)"
              + " values(?,?,?,?,?,?::jsonb,?)",
          sid,
          c.tid(),
          Json.text(b, "code"),
          b.path("name").asText(Json.text(b, "code")),
          b.path("direction").asText("BOTH"),
          Json.str(b.path("config")),
          b.hasNonNull("clientKey") ? Json.hash(Json.text(b, "clientKey")) : null);
      id = sid.toString();
    } else {
      var old = system(c, id);
      Db.version(old, v);
      JsonNode cfg = b.has("config") ? b.get("config") : old.path("config");
      if (b.path("active").asBoolean() && !old.path("active").asBoolean()) {
        Problem.require(old.hasNonNull("defaultMappingId"), 422, "MAPPING_REQUIRED", "启用前请发布映射");
        Problem.require(
            old.path("config").path("contractVerified").asBoolean(),
            422,
            "CONTRACT_REQUIRED",
            "请先测试目标业务错误与映射回归并确认接口契约");
      }
      Problem.require(
          db.run(
                  "update integration_system set"
                      + " name=?,config=?::jsonb,active=?,paused=?,row_version=row_version+1 where"
                      + " tenant_id=? and id=? and row_version=?",
                  b.path("name").asText(Json.text(old, "name")),
                  Json.str(cfg),
                  b.path("active").asBoolean(old.path("active").asBoolean()),
                  b.path("paused").asBoolean(old.path("paused").asBoolean()),
                  c.tid(),
                  models.uuid(id),
                  old.path("rowVersion").asLong())
              == 1,
          409,
          "VERSION_CONFLICT",
          "集成系统版本已变化");
    }
    var res = system(c, id);
    db.log(c, "SYSTEM", id, res.path("rowVersion").asLong(), "SYSTEM_CONFIG", "", res, null);
    return res;
  }

  public List<ObjectNode> mappings(Ctx c, String sid) {
    system(c, sid);
    return db.list(
        "select * from mapping_version where tenant_id=? and system_id=? order by version desc",
        c.tid(),
        models.uuid(sid));
  }

  public JsonNode createMapping(Ctx c, String sid, JsonNode b) {
    system(c, sid);
    checkMapping(b.path("config"));
    db.jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        c.tenant() + ":mapping:" + sid);
    long ver =
        db.count(
            "select coalesce(max(version),0)+1 from mapping_version where tenant_id=? and"
                + " system_id=?",
            c.tid(),
            models.uuid(sid));
    UUID id = UUID.randomUUID();
    db.run(
        "insert into mapping_version(id,tenant_id,system_id,version,config)"
            + " values(?,?,?,?,?::jsonb)",
        id,
        c.tid(),
        models.uuid(sid),
        ver,
        Json.str(b.path("config")));
    return mapping(c, id.toString());
  }

  public ObjectNode mapping(Ctx c, String id) {
    c.check("INTEGRATE", null);
    return db.one(
        "select * from mapping_version where tenant_id=? and id=?", c.tid(), models.uuid(id));
  }

  public JsonNode mappingAction(Ctx c, String id, String action, JsonNode b, String v) {
    var m = mapping(c, id);
    Db.version(m, v);
    if (action.equals("edit")) {
      Problem.require(
          Json.text(m, "state").equals("DRAFT"), 409, "IMMUTABLE_MAPPING", "发布映射不可修改，请创建新版本");
      checkMapping(b.path("config"));
      db.run(
          "update mapping_version set config=?::jsonb,row_version=row_version+1 where tenant_id=?"
              + " and id=? and row_version=?",
          Json.str(b.path("config")),
          c.tid(),
          models.uuid(id),
          m.path("rowVersion").asLong());
    } else if (action.equals("publish")) {
      Problem.require(Json.text(m, "state").equals("DRAFT"), 409, "MAPPING_STATE", "仅草稿可发布");
      checkMapping(m.path("config"));
      for (var sample : m.path("config").path("samples")) {
        var output = map(m.path("config"), sample.path("input"));
        if (sample.has("expected"))
          Problem.require(
              Json.canonical(output).equals(Json.canonical(sample.get("expected"))),
              422,
              "MAPPING_SAMPLE_FAILED",
              "映射回归样本不通过");
      }
      db.run(
          "update mapping_version set state='PUBLISHED',row_version=row_version+1 where tenant_id=?"
              + " and id=?",
          c.tid(),
          models.uuid(id));
      db.run(
          "update integration_system set default_mapping_id=?,row_version=row_version+1 where"
              + " tenant_id=? and id=?",
          models.uuid(id),
          c.tid(),
          models.uuid(Json.text(m, "systemId")));
    } else throw new Problem(400, "MAPPING_ACTION", "映射动作无效");
    db.log(c, "MAPPING", id, m.path("rowVersion").asLong() + 1, action, "", m.path("config"), null);
    return mapping(c, id);
  }

  void checkMapping(JsonNode cfg) {
    Problem.require(
        cfg.path("fields").isArray() && !cfg.path("fields").isEmpty(),
        422,
        "MAPPING_FIELDS",
        "至少配置一个出站映射字段");
    Set<String> targets = new HashSet<>();
    for (var f : cfg.path("fields")) {
      Problem.require(
          !Json.text(f, "target").isBlank()
              && targets.add(Json.text(f, "target"))
              && (f.has("source") || f.has("constant")),
          422,
          "MAPPING_FIELDS",
          "目标字段不可重复且必须有来源或常量");
      if (f.has("format"))
        Problem.require(
            Set.of("UPPER", "LOWER", "DECIMAL", "BOOLEAN").contains(Json.text(f, "format")),
            422,
            "MAPPING_FORMAT",
            "不支持的格式转换");
    }
    for (var policies : cfg.path("policiesByCategory")) checkPolicies(policies);
    for (String field : List.of("id", "materialNo", "schemaVersionId"))
      if (cfg.path("policies").has(field))
        Problem.require(
            cfg.path("policies").path(field).asText().equals("REJECT"),
            422,
            "OWNERSHIP_POLICY",
            "身份、料号、Schema只能REJECT");
    for (var x : cfg.path("policies"))
      Problem.require(
          Set.of("ACCEPT", "IGNORE", "REJECT", "REVIEW").contains(x.asText()),
          422,
          "OWNERSHIP_POLICY",
          "字段归属策略无效");
  }

  void checkPolicies(JsonNode policies) {
    for (String key : List.of("id", "materialNo", "schemaVersionId"))
      if (policies.has(key))
        Problem.require(
            policies.path(key).asText().equals("REJECT"),
            422,
            "OWNERSHIP_POLICY",
            "核心身份字段只能REJECT");
    for (var p : policies)
      Problem.require(
          Set.of("ACCEPT", "IGNORE", "REJECT", "REVIEW").contains(p.asText()),
          422,
          "OWNERSHIP_POLICY",
          "字段归属策略无效");
  }

  public ObjectNode map(JsonNode cfg, JsonNode data) {
    ObjectNode out = Json.obj();
    for (var f : cfg.path("fields")) {
      JsonNode val =
          f.has("constant") ? f.get("constant") : Json.path(data, Json.text(f, "source"));
      Problem.require(
          !f.path("required").asBoolean() || (!val.isNull() && !val.isMissingNode()),
          422,
          "MAPPING_MISSING",
          "映射必填字段缺失：" + Json.text(f, "target"));
      if (val.isNull() || val.isMissingNode()) continue;
      if (f.has("dictionary")) {
        val = f.path("dictionary").path(val.asText());
        Problem.require(!val.isMissingNode(), 422, "MAPPING_DICTIONARY", "字典转换缺项");
      }
      if (f.has("factor"))
        val =
            DecimalNode.valueOf(
                Json.decimal(val)
                    .multiply(Json.decimal(f.get("factor")))
                    .add(new BigDecimal(f.path("offset").asText("0"))));
      if (f.has("unit")) {
        Problem.require(val.isObject() && val.has("value"), 422, "MAPPING_UNIT", "单位转换需要数值对象");
        JsonNode units = cfg.path("units");
        val =
            DecimalNode.valueOf(
                rules.convert(
                    Json.object("units", units),
                    Json.decimal(val),
                    val.path("unit").asText(),
                    f.path("unit").asText()));
      }
      if (f.has("format")) {
        switch (Json.text(f, "format")) {
          case "UPPER":
            val = TextNode.valueOf(val.asText().toUpperCase(Locale.ROOT));
            break;
          case "LOWER":
            val = TextNode.valueOf(val.asText().toLowerCase(Locale.ROOT));
            break;
          case "DECIMAL":
            try {
              val =
                  TextNode.valueOf(
                      Json.decimal(val)
                          .setScale(f.path("scale").asInt(2), RoundingMode.UNNECESSARY)
                          .toPlainString());
            } catch (ArithmeticException e) {
              throw new Problem(422, "MAPPING_PRECISION", "目标格式精度不足");
            }
            break;
          case "BOOLEAN":
            Problem.require(val.isBoolean(), 422, "MAPPING_BOOLEAN", "目标布尔格式应为布尔值");
            break;
        }
      }
      if (f.has("maxLength"))
        Problem.require(
            val.asText().length() <= f.path("maxLength").asInt(),
            422,
            "MAPPING_LENGTH",
            "映射字段过长，禁止截断");
      put(out, Json.text(f, "target"), val);
    }
    return out;
  }

  void put(ObjectNode out, String path, JsonNode value) {
    String[] parts = path.split("\\.");
    ObjectNode cur = out;
    for (int i = 0; i < parts.length - 1; i++) {
      if (!cur.has(parts[i])) cur.set(parts[i], Json.obj());
      Problem.require(cur.path(parts[i]).isObject(), 422, "MAPPING_PATH", "目标字段路径冲突");
      cur = (ObjectNode) cur.path(parts[i]);
    }
    cur.set(parts[parts.length - 1], value);
  }

  public JsonNode preview(Ctx c, String mapping, JsonNode b) {
    var m = mapping(c, mapping);
    return map(m.path("config"), b.path("input"));
  }

  public JsonNode testConnection(Ctx c, String sid) {
    var s = system(c, sid);
    String url = s.path("config").path("healthUrl").asText(s.path("config").path("url").asText());
    try {
      var res =
          http.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      Problem.require(
          res.statusCode() >= 200 && res.statusCode() < 300,
          422,
          "CONNECTION_FAILED",
          "连接响应HTTP " + res.statusCode());
      boolean errorTest = false;
      String contractUrl = s.path("config").path("contractTestUrl").asText("");
      if (!contractUrl.isEmpty()) {
        var invalid =
            http.send(
                HttpRequest.newBuilder(URI.create(contractUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        var errorBody = Json.read(invalid.body());
        Problem.require(
            !errorBody.path("success").asBoolean()
                && (invalid.statusCode() >= 400 || errorBody.has("code")),
            422,
            "CONTRACT_ERROR_SAMPLE",
            "目标错误样本未返回可识别业务失败");
        errorTest = true;
      }
      if (errorTest)
        db.run(
            "update integration_system set"
                + " config=jsonb_set(config,'{contractVerified}','true'::jsonb),row_version=row_version+1"
                + " where tenant_id=? and id=?",
            c.tid(),
            models.uuid(sid));
      return Json.object(
          "httpStatus",
          res.statusCode(),
          "reachable",
          true,
          "businessErrorSampleVerified",
          errorTest);
    } catch (Problem p) {
      throw p;
    } catch (Exception e) {
      throw new Problem(503, "CONNECTION_FAILED", "目标连接失败：" + e.getClass().getSimpleName());
    }
  }

  public Ctx client(String key) {
    Problem.require(key != null && !key.isBlank(), 401, "CLIENT_REQUIRED", "入站需要已登记集成客户端");
    var s =
        db.one(
            "select s.*,t.status from integration_system s join tenant t on t.id=s.tenant_id where"
                + " s.credential_hash=?",
            Json.hash(key));
    Problem.require(
        s.path("active").asBoolean() && s.path("status").asText().equals("ACTIVE"),
        409,
        "TENANT_PAUSED",
        "租户或集成客户端未运行");
    String actor = s.path("config").path("inboundUserId").asText();
    var c = auth.worker(Json.text(s, "tenantId"), actor);
    c.check("IMPORT", null);
    return c;
  }

  public JsonNode inbound(Ctx c, String sid, JsonNode b) {
    var s =
        db.one(
            "select * from integration_system where tenant_id=? and id=?",
            c.tid(),
            models.uuid(sid));
    auth.active(c.tenant());
    Problem.require(
        s.path("active").asBoolean() && !s.path("direction").asText().equals("OUTBOUND"),
        403,
        "INBOUND_DISABLED",
        "系统未启用入站");
    String message = Json.text(b, "sourceMessageId"), external = Json.text(b, "externalId");
    Problem.require(
        !message.isBlank() && !external.isBlank(),
        422,
        "INBOUND_IDENTITY",
        "sourceMessageId和externalId必填");
    db.jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        c.tenant() + ":" + sid + ":" + message);
    String hash = Json.hash(Json.canonical(b));
    var old =
        db.maybe(
            "select * from inbox_message where tenant_id=? and system_id=? and message_id=?",
            c.tid(),
            models.uuid(sid),
            message);
    if (old != null) {
      Problem.require(
          old.path("bodyHash").asText().equals(hash), 409, "INBOX_CONFLICT", "相同消息ID对应不同请求体");
      if (!old.path("result").path("status").asText().equals("WAIT_PREDECESSOR"))
        return old.get("result");
    }
    JsonNode cfg =
        db.one(
                "select config from mapping_version where tenant_id=? and id=? and"
                    + " state='PUBLISHED'",
                c.tid(),
                models.uuid(Json.text(s, "defaultMappingId")))
            .path("config");
    var identity =
        db.maybe(
            "select * from external_identity where tenant_id=? and system_id=? and external_id=?"
                + " for update",
            c.tid(),
            models.uuid(sid),
            external);
    String category = b.path("categoryCode").asText("");
    if (identity != null) {
      var bound = materials.get(c, Json.text(identity, "materialId"), false);
      String actual = Json.text(models.categoryById(c, Json.text(bound, "categoryId")), "code");
      Problem.require(
          category.isEmpty() || category.equals(actual),
          422,
          "CATEGORY_IDENTITY_CONFLICT",
          "外部身份已归属其他类别");
      category = actual;
    }
    JsonNode categoryPolicies = cfg.path("policiesByCategory").path(category);
    JsonNode raw = b.path("data");
    ObjectNode accepted = Json.obj();
    ArrayNode ignored = Json.arr(), review = Json.arr();
    List<String> paths = new ArrayList<>();
    raw.fieldNames()
        .forEachRemaining(
            k -> {
              if (k.equals("attributes"))
                raw.path(k).fieldNames().forEachRemaining(f -> paths.add("attributes." + f));
              else paths.add(k);
            });
    for (String field : paths) {
      String policy =
          (categoryPolicies.has(field) ? categoryPolicies : cfg.path("policies"))
              .path(field)
              .asText(
                  Set.of("id", "materialNo", "schemaVersionId").contains(field)
                      ? "REJECT"
                      : "REVIEW");
      Problem.require(!policy.equals("REJECT"), 422, "OWNERSHIP_REJECTED", "外部来源不能修改字段：" + field);
      if (policy.equals("IGNORE")) {
        ignored.add(field);
        continue;
      }
      if (policy.equals("REVIEW")) review.add(field);
      put(accepted, field, Json.path(raw, field));
    }
    long sourceVersion = b.path("sourceVersion").asLong(0),
        previous = b.path("previousPublishedVersion").asLong(0);
    long confirmed =
        db.count(
            "select coalesce(max(source_version),0) from inbox_message where tenant_id=? and"
                + " system_id=? and result->>'externalId'=? and"
                + " result->>'status'<>'WAIT_PREDECESSOR'",
            c.tid(),
            models.uuid(sid),
            external);
    ObjectNode result;
    if (sourceVersion > 0 && sourceVersion <= confirmed)
      result =
          Json.object(
              "status", "IGNORED_OLD_VERSION", "externalId", external, "ignoredFields", ignored);
    else if (previous > confirmed)
      result =
          Json.object(
              "status",
              "WAIT_PREDECESSOR",
              "externalId",
              external,
              "expectedPrevious",
              previous,
              "lastAcceptedVersion",
              confirmed);
    else {
      String mid, rid = null;
      if (identity == null) {
        String catCode = b.path("categoryCode").asText();
        var schema =
            models.currentSchema(
                c,
                catCode,
                b.hasNonNull("schemaVersionId") ? Json.text(b, "schemaVersionId") : null);
        accepted.put("categoryCode", catCode);
        accepted.set("schemaVersionId", schema.path("id"));
        if (!accepted.has("attributes")) accepted.set("attributes", Json.obj());
        var material = materials.create(c, accepted);
        mid = Json.text(material, "id");
        db.run(
            "insert into external_identity(tenant_id,system_id,external_id,material_id)"
                + " values(?,?,?,?)",
            c.tid(),
            models.uuid(sid),
            external,
            models.uuid(mid));
        var req =
            materials.newRequest(
                c,
                mid,
                Json.object("kind", "NEW", "reason", "集成入站待人工审核"),
                material.path("rowVersion").asText());
        rid = Json.text(req, "id");
      } else {
        mid = Json.text(identity, "materialId");
        var material = materials.get(c, mid, false);
        Problem.require(
            material.hasNonNull("materialNo"), 409, "EXTERNAL_DRAFT_PENDING", "外部物料已有待审草稿，请先处理");
        Problem.require(b.has("baseVersion"), 428, "VERSION_REQUIRED", "入站变更须携带平台baseVersion");
        var req =
            materials.newRequest(
                c,
                mid,
                Json.object("kind", "CHANGE", "reason", "集成入站审核", "candidate", accepted),
                b.path("baseVersion").asText());
        rid = Json.text(req, "id");
      }
      db.run(
          "update material_request set source_system_id=?,source_message_id=? where tenant_id=? and"
              + " id=?",
          models.uuid(sid),
          message,
          c.tid(),
          models.uuid(rid));
      result =
          Json.object(
              "status",
              review.isEmpty() ? "DRAFT_CREATED" : "REVIEW_REQUIRED",
              "materialId",
              mid,
              "requestId",
              rid,
              "externalId",
              external,
              "ignoredFields",
              ignored,
              "reviewFields",
              review);
      db.log(c, "INBOX", message, sourceVersion, "INBOUND", "ACCEPT仍需审批", result, null);
    }
    db.run(
        "insert into inbox_message(tenant_id,system_id,message_id,body_hash,result,source_version)"
            + " values(?,?,?,?,?::jsonb,?) on conflict(tenant_id,system_id,message_id) do update"
            + " set result=excluded.result,source_version=excluded.source_version",
        c.tid(),
        models.uuid(sid),
        message,
        hash,
        Json.str(result),
        sourceVersion);
    return result;
  }

  public JsonNode identities(Ctx c, String sid) {
    system(c, sid);
    return Json.M.valueToTree(
        db
            .list(
                "select i.*,c.code as category_code from external_identity i join material m on"
                    + " m.id=i.material_id join category c on c.id=m.category_id where"
                    + " i.tenant_id=? and i.system_id=?",
                c.tid(),
                models.uuid(sid))
            .stream()
            .filter(x -> c.allows(Json.text(x, "categoryCode")))
            .toList());
  }

  public JsonNode bind(Ctx c, String sid, JsonNode b) {
    system(c, sid);
    var material = materials.get(c, Json.text(b, "materialId"), false);
    Problem.require(
        !Json.text(b, "reason").isBlank() && !Json.text(b, "externalId").isBlank(),
        422,
        "REASON_REQUIRED",
        "外部身份绑定需身份及原因");
    JsonNode before = Json.obj();
    if (b.hasNonNull("previousExternalId")) {
      before =
          db.one(
              "select * from external_identity where tenant_id=? and system_id=? and material_id=?"
                  + " and external_id=? for update",
              c.tid(),
              models.uuid(sid),
              models.uuid(Json.text(b, "materialId")),
              Json.text(b, "previousExternalId"));
      Db.version(before, b.has("baseVersion") ? b.path("baseVersion").asText() : null);
      db.run(
          "update external_identity set"
              + " external_id=?,confirmed_version=null,digest=null,row_version=row_version+1 where"
              + " tenant_id=? and system_id=? and material_id=?",
          Json.text(b, "externalId"),
          c.tid(),
          models.uuid(sid),
          models.uuid(Json.text(b, "materialId")));
    } else
      db.run(
          "insert into external_identity(tenant_id,system_id,external_id,material_id)"
              + " values(?,?,?,?)",
          c.tid(),
          models.uuid(sid),
          Json.text(b, "externalId"),
          models.uuid(Json.text(b, "materialId")));
    var result =
        db.one(
            "select * from external_identity where tenant_id=? and system_id=? and material_id=?",
            c.tid(),
            models.uuid(sid),
            models.uuid(Json.text(b, "materialId")));
    db.log(
        c,
        "EXTERNAL_IDENTITY",
        Json.text(b, "externalId"),
        result.path("rowVersion").asLong(),
        b.hasNonNull("previousExternalId") ? "REBIND" : "BIND",
        Json.text(b, "reason"),
        Json.object("before", before, "after", result),
        Json.text(material, "categoryId"));
    return result;
  }
}
