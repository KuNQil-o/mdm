package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Tenants {
  final Db db;

  public Tenants(Db d) {
    db = d;
  }

  public JsonNode overview(String id) {
    UUID t = UUID.fromString(id);
    return Json.object(
        "materials",
        db.count("select count(*) from material where tenant_id=?", t),
        "members",
        db.count("select count(*) from tenant_member where tenant_id=? and active", t),
        "drafts",
        db.count("select count(*) from material where tenant_id=? and status='DRAFT'", t),
        "pendingRequests",
        db.count("select count(*) from material_request where tenant_id=? and state='REVIEW'", t),
        "imports",
        db.count(
            "select count(*) from import_job where tenant_id=? and status not in"
                + " ('COMPLETED','CANCELLED')",
            t),
        "pendingDeliveries",
        db.count("select count(*) from delivery where tenant_id=? and state<>'SUCCEEDED'", t),
        "failedDeliveries",
        db.count(
            "select count(*) from delivery where tenant_id=? and state in"
                + " ('FAILED','RESULT_UNKNOWN')",
            t),
        "waitingConfirmation",
        db.count(
            "select count(*) from delivery where tenant_id=? and state='WAIT_CONFIRMATION'", t),
        "lastActivity",
        db.maybe("select max(created_at) as time from operation_log where tenant_id=?", t));
  }

  public JsonNode platformList() {
    return Json.M.valueToTree(
        db.list("select * from tenant order by code").stream()
            .peek(t -> t.set("overview", overview(Json.text(t, "id"))))
            .toList());
  }

  public JsonNode create(String actor, JsonNode b) {
    String code = Json.text(b, "code");
    Problem.require(
        code.matches("[A-Za-z][A-Za-z0-9_-]{0,79}") && !Json.text(b, "name").isBlank(),
        422,
        "TENANT_REQUIRED",
        "租户code和名称必填");
    UUID id = UUID.randomUUID();
    db.run(
        "insert into tenant(id,code,name,status,settings) values(?,?,?,'DRAFT',?::jsonb)",
        id,
        code,
        Json.text(b, "name"),
        Json.str(
            b.path("settings").isObject()
                ? b.get("settings")
                : Json.object(
                    "timezone",
                    "Asia/Shanghai",
                    "pageSize",
                    50,
                    "importLimit",
                    50000,
                    "exportLimit",
                    50000)));
    defaults(id);
    if (b.hasNonNull("adminUserId"))
      member(
          new Ctx(id.toString(), actor, Set.of("ADMIN"), Set.of("*"), UUID.randomUUID().toString()),
          Json.object(
              "userId",
              b.path("adminUserId"),
              "roles",
              Json.arr().add("ADMIN"),
              "categoryScope",
              Json.arr().add("*")),
          null,
          null);
    return db.one("select * from tenant where id=?", id);
  }

  public void defaults(UUID id) {
    Map<String, List<String>> rs = new LinkedHashMap<>();
    rs.put(
        "ADMIN",
        List.of(
            "READ",
            "ADMIN",
            "EXPORT",
            "INTEGRATE",
            "SOURCE_EDIT",
            "SOURCE_IMPORT",
            "DATASET_DESIGN",
            "MAPPING_DESIGN",
            "IDENTITY_DESIGN",
            "RULE_DESIGN"));
    rs.put(
        "DESIGNER",
        List.of(
            "READ",
            "DESIGN",
            "EXPORT",
            "SOURCE_EDIT",
            "SOURCE_IMPORT",
            "DATASET_DESIGN",
            "MAPPING_DESIGN",
            "IDENTITY_DESIGN",
            "RULE_DESIGN"));
    rs.put("PUBLISHER", List.of("READ", "PUBLISH", "EXPORT"));
    rs.put("EDITOR", List.of("READ", "CREATE", "EDIT", "SUBMIT", "IMPORT", "EXPORT", "LEGACY"));
    rs.put("APPROVER", List.of("READ", "APPROVE", "EXPORT"));
    rs.put(
        "INTEGRATOR",
        List.of("READ", "INTEGRATE", "EXPORT", "SCAN", "ASSIGN", "WRITEBACK", "RECONCILE"));
    rs.put("READER", List.of("READ"));
    rs.forEach(
        (k, v) ->
            db.run(
                "insert into role(tenant_id,code,name,actions) values(?,?,?,?::jsonb) on conflict"
                    + " do nothing",
                id,
                k,
                k,
                Json.str(
                    java.util.stream.Stream.concat(
                            v.stream(), java.util.stream.Stream.of("SOURCE_READ"))
                        .toList())));
  }

  public JsonNode patch(String actor, String id, JsonNode b, String v) {
    var t = db.one("select * from tenant where id=? for update", UUID.fromString(id));
    Db.version(t, v);
    if (b.has("code"))
      Problem.require(
          b.path("code").equals(t.path("code")) || Json.text(t, "status").equals("DRAFT"),
          422,
          "CODE_IMMUTABLE",
          "启用后租户code不可修改");
    var c = new Ctx(id, actor, Set.of(), Set.of(), UUID.randomUUID().toString());
    db.run(
        "update tenant set"
            + " code=?,name=?,settings=?::jsonb,row_version=row_version+1,updated_at=now() where"
            + " id=?",
        b.path("code").asText(Json.text(t, "code")),
        b.path("name").asText(Json.text(t, "name")),
        Json.str(
            b.has("settings")
                ? settingsMerged(t.path("settings"), b.get("settings"))
                : t.get("settings")),
        UUID.fromString(id));
    db.log(
        c,
        "TENANT",
        id,
        t.path("rowVersion").asLong() + 1,
        "UPDATE",
        Json.text(b, "reason"),
        Json.object("before", t, "after", b),
        null);
    return db.one("select * from tenant where id=?", UUID.fromString(id));
  }

  public JsonNode action(String actor, String id, String action, JsonNode b, String v) {
    var t = db.one("select * from tenant where id=? for update", UUID.fromString(id));
    Db.version(t, v);
    String state = Json.text(t, "status");
    String next =
        switch (action) {
          case "activate" -> {
            Problem.require(
                state.equals("DRAFT")
                    && db.count(
                            "select count(*) from tenant_member where tenant_id=? and active and"
                                + " roles @> '[\"ADMIN\"]'::jsonb",
                            UUID.fromString(id))
                        > 0,
                422,
                "TENANT_NOT_CONFIGURED",
                "启用须有初始管理员");
            yield "ACTIVE";
          }
          case "suspend" -> {
            Problem.require(state.equals("ACTIVE"), 409, "STATE_CONFLICT", "仅运行中租户可暂停");
            yield "SUSPENDED";
          }
          case "resume" -> {
            Problem.require(state.equals("SUSPENDED"), 409, "STATE_CONFLICT", "仅暂停租户可恢复");
            yield "ACTIVE";
          }
          case "archive" -> {
            Problem.require(
                db.count(
                            "select count(*) from writeback_task where tenant_id=? and state not in"
                                + " ('SUCCEEDED','FAILED')",
                            UUID.fromString(id))
                        == 0
                    && db.count(
                            "select count(*) from processing_task where tenant_id=? and status in"
                                + " ('DISCOVERED','PROCESSING','RETRY_WAIT')",
                            UUID.fromString(id))
                        == 0
                    && db.count(
                            "select count(*) from scan_job where tenant_id=? and status in"
                                + " ('PENDING','SCANNING','PROCESSING')",
                            UUID.fromString(id))
                        == 0,
                409,
                "UNFINISHED_TASKS",
                "归档前必须处理V3扫描、发号与未确认回写任务");
            Problem.require(
                state.equals("ACTIVE") || state.equals("SUSPENDED"),
                409,
                "STATE_CONFLICT",
                "当前状态不可归档");
            Problem.require(
                db.count(
                            "select count(*) from material_request where tenant_id=? and state in"
                                + " ('DRAFT','REVIEW')",
                            UUID.fromString(id))
                        == 0
                    && db.count(
                            "select count(*) from import_job where tenant_id=? and status not in"
                                + " ('COMPLETED','CANCELLED')",
                            UUID.fromString(id))
                        == 0
                    && db.count(
                            "select count(*) from delivery where tenant_id=? and state not in"
                                + " ('SUCCEEDED','FAILED')",
                            UUID.fromString(id))
                        == 0
                    && db.count(
                            "select count(*) from export_job where tenant_id=? and status in"
                                + " ('PENDING','PROCESSING')",
                            UUID.fromString(id))
                        == 0,
                409,
                "UNFINISHED_TASKS",
                "归档前需处理申请、导入及未确认投递");
            yield "ARCHIVED";
          }
          case "reopen" -> {
            Problem.require(state.equals("ARCHIVED"), 409, "STATE_CONFLICT", "仅归档租户可重开");
            yield "SUSPENDED";
          }
          default -> throw new Problem(400, "BAD_ACTION", "租户动作无效");
        };
    Problem.require(!Json.text(b, "reason").isBlank(), 422, "REASON_REQUIRED", "租户状态变更须填写原因");
    db.run(
        "update tenant set status=?,row_version=row_version+1,updated_at=now() where id=?",
        next,
        UUID.fromString(id));
    if (next.equals("SUSPENDED"))
      db.run(
          "update import_job set"
              + " previous_stage=status,status='PAUSED',reason='租户暂停',row_version=row_version+1"
              + " where tenant_id=? and status in ('VALIDATING','COMMITTING')",
          UUID.fromString(id));
    if (next.equals("ACTIVE"))
      db.run(
          "update import_job set"
              + " status=previous_stage,previous_stage=null,row_version=row_version+1 where"
              + " tenant_id=? and status='PAUSED' and reason='租户暂停'",
          UUID.fromString(id));
    db.log(
        new Ctx(id, actor, Set.of(), Set.of(), UUID.randomUUID().toString()),
        "TENANT",
        id,
        t.path("rowVersion").asLong() + 1,
        action,
        Json.text(b, "reason"),
        Json.object("before", state, "after", next),
        null);
    return db.one("select * from tenant where id=?", UUID.fromString(id));
  }

  JsonNode settingsMerged(JsonNode old, JsonNode b) {
    var out = (com.fasterxml.jackson.databind.node.ObjectNode) old.deepCopy();
    Set<String> keys =
        Set.of(
            "timezone",
            "displayUnits",
            "pageSize",
            "importLimit",
            "exportLimit",
            "retentionDays",
            "contact",
            "integrationContact");
    b.fields()
        .forEachRemaining(
            e -> {
              Problem.require(keys.contains(e.getKey()), 422, "SETTINGS_IMMUTABLE", "未知设置或涉及历史语义");
              out.set(e.getKey(), e.getValue());
            });
    Problem.require(out.path("retentionDays").asInt(30) > 0, 422, "RETENTION_DAYS", "保留天数必须为正整数");
    if (out.has("timezone"))
      try {
        java.time.ZoneId.of(out.path("timezone").asText());
      } catch (Exception e) {
        throw new Problem(422, "TIMEZONE", "时区无效");
      }
    for (String key : List.of("importLimit", "exportLimit"))
      Problem.require(
          out.path(key).asInt(50000) > 0 && out.path(key).asInt(50000) <= 50000,
          422,
          "LIMIT",
          "导入导出上限1—50000");
    Problem.require(
        out.path("pageSize").asInt(50) > 0 && out.path("pageSize").asInt(50) <= 200,
        422,
        "PAGE_SIZE",
        "查询页数1—200");
    return out;
  }

  public JsonNode settings(Ctx c, JsonNode b, String v) {
    c.check("ADMIN", null);
    return patch(c.user(), c.tenant(), Json.object("settings", b), v);
  }

  public JsonNode member(Ctx c, JsonNode b, String id, String v) {
    c.check("ADMIN", null);
    JsonNode roles = b.path("roles"),
        scope = b.path("categoryScope"),
        sourceScope = b.has("sourceScope") ? b.path("sourceScope") : Json.arr().add("*");
    if (id != null) {
      var old =
          db.one(
              "select * from tenant_member where tenant_id=? and id=? for update",
              c.tid(),
              UUID.fromString(id));
      Db.version(old, v);
      if (!b.has("roles")) roles = old.path("roles");
      if (!b.has("categoryScope")) scope = old.path("categoryScope");
      if (!b.has("sourceScope")) sourceScope = old.path("sourceScope");
      Problem.require(
          !b.has("userId") || b.path("userId").equals(old.path("userId")),
          422,
          "MEMBER_IDENTITY",
          "成员关联用户不可覆盖");
    }
    Problem.require(roles.isArray() && scope.isArray(), 422, "MEMBER_FIELDS", "角色与类别范围应为数组");
    for (var role : roles)
      db.one("select code from role where tenant_id=? and code=?", c.tid(), role.asText());
    for (var code : scope)
      if (!code.asText().equals("*"))
        db.one("select code from category where tenant_id=? and code=?", c.tid(), code.asText());
    Problem.require(sourceScope.isArray(), 422, "SOURCE_SCOPE", "来源范围须为数组");
    for (var source : sourceScope)
      if (!source.asText().equals("*"))
        Problem.require(
            db.count(
                    "select count(*) from source_system where tenant_id=? and (id::text=? or"
                        + " code=?)",
                    c.tid(),
                    source.asText(),
                    source.asText())
                > 0,
            422,
            "SOURCE_SCOPE",
            "来源不属于当前租户");
    boolean active = b.path("active").asBoolean(true);
    if (id == null) {
      UUID uid = UUID.fromString(Json.text(b, "userId"));
      db.one("select id from business_user where id=?", uid);
      id = UUID.randomUUID().toString();
      db.run(
          "insert into tenant_member(id,tenant_id,user_id,active,roles,category_scope)"
              + " values(?,?,?,?,?::jsonb,?::jsonb)",
          UUID.fromString(id),
          c.tid(),
          uid,
          active,
          Json.str(roles),
          Json.str(scope));
    } else
      db.run(
          "update tenant_member set"
              + " active=?,roles=?::jsonb,category_scope=?::jsonb,row_version=row_version+1 where"
              + " tenant_id=? and id=?",
          active,
          Json.str(roles),
          Json.str(scope),
          c.tid(),
          UUID.fromString(id));
    db.run(
        "update tenant_member set source_scope=?::jsonb where tenant_id=? and id=?",
        Json.str(sourceScope),
        c.tid(),
        UUID.fromString(id));
    var res =
        db.one(
            "select * from tenant_member where tenant_id=? and id=?", c.tid(), UUID.fromString(id));
    db.log(
        c,
        "MEMBER",
        id,
        res.path("rowVersion").asLong(),
        "MEMBER_ASSIGNMENT",
        Json.text(b, "reason"),
        res,
        null);
    return res;
  }

  public JsonNode role(Ctx c, JsonNode b, String code, String v) {
    c.check("ADMIN", null);
    String k = code == null ? Json.text(b, "code") : code;
    Problem.require(
        !k.isBlank() && b.path("actions").isArray(), 422, "ROLE_FIELDS", "角色code及actions必填");
    Set<String> allowed =
        Set.of(
            "READ",
            "ADMIN",
            "DESIGN",
            "PUBLISH",
            "CREATE",
            "EDIT",
            "SUBMIT",
            "APPROVE",
            "IMPORT",
            "EXPORT",
            "INTEGRATE",
            "LEGACY",
            "SOURCE_READ",
            "SOURCE_EDIT",
            "SOURCE_IMPORT",
            "DATASET_DESIGN",
            "MAPPING_DESIGN",
            "IDENTITY_DESIGN",
            "RULE_DESIGN",
            "SCAN",
            "ASSIGN",
            "WRITEBACK",
            "RECONCILE");
    b.path("actions")
        .forEach(
            x -> Problem.require(allowed.contains(x.asText()), 422, "ROLE_ACTION", "未支持的角色动作"));
    var old = db.maybe("select * from role where tenant_id=? and code=? for update", c.tid(), k);
    if (old != null) {
      Db.version(old, v);
      db.run(
          "update role set name=?,actions=?::jsonb,row_version=row_version+1 where tenant_id=? and"
              + " code=?",
          b.path("name").asText(k),
          Json.str(b.path("actions")),
          c.tid(),
          k);
    } else
      db.run(
          "insert into role(tenant_id,code,name,actions) values(?,?,?,?::jsonb)",
          c.tid(),
          k,
          b.path("name").asText(k),
          Json.str(b.path("actions")));
    var res = db.one("select * from role where tenant_id=? and code=?", c.tid(), k);
    db.log(c, "ROLE", k, res.path("rowVersion").asLong(), "ROLE_UPDATE", "", res, null);
    return res;
  }
}
