package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("dev")
@RequestMapping("/api/dev")
public class Dev {
  final Db db;
  final Models models;
  final Tenants tenants;
  final Auth auth;
  final Integrations integrations;

  public Dev(Db d, Models m, Tenants t, Auth a, Integrations i) {
    db = d;
    models = m;
    tenants = t;
    auth = a;
    integrations = i;
  }

  static UUID id(String key) {
    return UUID.nameUUIDFromBytes(("MDM:DEMO:" + key).getBytes(StandardCharsets.UTF_8));
  }

  @GetMapping("/users")
  public JsonNode users() {
    return Json.M.valueToTree(
        db.list("select id,code,name,platform_admin from business_user order by code"));
  }

  @PostMapping("/login")
  public JsonNode login(HttpServletRequest r, @RequestBody JsonNode b) {
    var u =
        db.one(
            "select id,code,name,platform_admin from business_user where code=?",
            Json.text(b, "userCode"));
    r.getSession(true).invalidate();
    r.getSession(true).setAttribute("user", Json.text(u, "id"));
    return u;
  }

  @PostMapping("/logout")
  public JsonNode logout(HttpServletRequest r) {
    if (r.getSession(false) != null) r.getSession(false).invalidate();
    return Json.object("loggedOut", true);
  }

  @PostMapping("/seed")
  public JsonNode seed() {
    return db.tx(
        () -> {
          db.jdbc.queryForObject(
              "select pg_advisory_xact_lock(hashtextextended('MDM_DEMO_SEED',0))", Object.class);
          for (String[] u :
              new String[][] {
                {"admin", "平台管理员"},
                {"editor", "林晓 · 模型设计/物料编辑"},
                {"reviewer", "陈宁 · 模型发布/物料审批"},
                {"reader", "只读成员"}
              })
            db.run(
                "insert into business_user(id,code,name,platform_admin) values(?,?,?,?) on"
                    + " conflict(code) do nothing",
                id("user:" + u[0]),
                u[0],
                u[1],
                u[0].equals("admin"));
          String admin = Json.text(db.one("select id from business_user where code='admin'"), "id"),
              editor = Json.text(db.one("select id from business_user where code='editor'"), "id"),
              reviewer =
                  Json.text(db.one("select id from business_user where code='reviewer'"), "id"),
              reader = Json.text(db.one("select id from business_user where code='reader'"), "id");
          int created = 0;
          for (String[] t : new String[][] {{"TENANT-A", "华东新材"}, {"TENANT-B", "精工制造"}}) {
            var existing = db.maybe("select * from tenant where code=?", t[0]);
            if (existing == null) {
              var tenant =
                  tenants.create(
                      admin, Json.object("code", t[0], "name", t[1], "adminUserId", admin));
              existing = (ObjectNode) tenant;
              db.run(
                  "update tenant set status='ACTIVE' where id=?",
                  UUID.fromString(Json.text(existing, "id")));
              created++;
            }
            UUID tid = UUID.fromString(Json.text(existing, "id"));
            tenants.defaults(tid);
            for (String[] m :
                new String[][] {
                  {editor, "EDITOR,DESIGNER,INTEGRATOR"},
                  {reviewer, "APPROVER,PUBLISHER,INTEGRATOR"},
                  {reader, "READER"}
                }) {
              ArrayNode roles = Json.arr();
              Arrays.stream(m[1].split(",")).forEach(roles::add);
              if (t[0].equals("TENANT-B") && m[0].equals(reader)) roles = Json.arr().add("READER");
              db.run(
                  "insert into tenant_member(id,tenant_id,user_id,roles,category_scope)"
                      + " values(?,?,?,?::jsonb,?::jsonb) on conflict(tenant_id,user_id) do"
                      + " nothing",
                  UUID.randomUUID(),
                  tid,
                  UUID.fromString(m[0]),
                  Json.str(roles),
                  Json.str(
                      m[0].equals(reader)
                          ? Json.arr().add(t[0].equals("TENANT-A") ? "GLASS_CLOTH" : "BEARING")
                          : Json.arr().add("*")));
            }
            var ce = auth.worker(tid.toString(), editor);
            var cr = auth.worker(tid.toString(), reviewer);
            var ca = auth.worker(tid.toString(), admin);
            for (String[] u :
                new String[][] {
                  {"mm", "length", "1"},
                  {"m", "length", "1000"},
                  {"cm", "length", "10"},
                  {"mm2", "area", "1"},
                  {"m2", "area", "1000000"},
                  {"g/m2", "massPerArea", "1"},
                  {"kg/m2", "massPerArea", "1000"},
                  {"kg", "mass", "1000"},
                  {"g", "mass", "1"}
                })
              if (db.count(
                      "select count(*) from metadata where tenant_id=? and kind='UNIT' and code=?",
                      tid,
                      u[0])
                  == 0)
                models.createMeta(
                    ce,
                    Json.object(
                        "kind",
                        "UNIT",
                        "code",
                        u[0],
                        "data",
                        Json.object("dimension", u[1], "factor", u[2])));
            for (String dict : List.of("PRECISION", "SEAL"))
              if (db.count(
                      "select count(*) from metadata where tenant_id=? and kind='DICTIONARY' and"
                          + " code=?",
                      tid,
                      dict)
                  == 0) {
                ArrayNode items = Json.arr();
                for (String k :
                    dict.equals("PRECISION")
                        ? List.of("P0", "P6", "P5")
                        : List.of("OPEN", "2RS", "ZZ"))
                  items.add(Json.object("code", k, "label", k, "active", true));
                models.createMeta(
                    ce,
                    Json.object(
                        "kind", "DICTIONARY", "code", dict, "data", Json.object("items", items)));
              }
            if (db.count(
                    "select count(*) from reference_entity where tenant_id=? and id='SUP-001'", tid)
                == 0)
              models.reference(
                  ca,
                  Json.object(
                      "type",
                      "SUPPLIER",
                      "id",
                      "SUP-001",
                      "name",
                      "宏和",
                      "data",
                      Json.object("numberCode", "HONGHE")),
                  null);
            if (db.count(
                    "select count(*) from reference_entity where tenant_id=? and id='SUP-002'", tid)
                == 0)
              models.reference(
                  ca,
                  Json.object(
                      "type",
                      "SUPPLIER",
                      "id",
                      "SUP-002",
                      "name",
                      "泰山",
                      "data",
                      Json.object("numberCode", "TAISHAN")),
                  null);
            for (String[] sample :
                new String[][] {{"GLASS_CLOTH", "玻璃布", "glass"}, {"BEARING", "轴承", "bearing"}}) {
              var category =
                  db.maybe("select * from category where tenant_id=? and code=?", tid, sample[0]);
              if (category == null)
                category =
                    (ObjectNode)
                        models.createCategory(
                            ce, Json.object("code", sample[0], "name", sample[1]));
              if (db.count(
                      "select count(*) from schema_version where tenant_id=? and category_id=?",
                      tid,
                      UUID.fromString(Json.text(category, "id")))
                  == 0) {
                try (var in = getClass().getResourceAsStream("/demo/" + sample[2] + ".json")) {
                  var b = Json.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                  var schema = models.createSchema(ce, sample[0], Json.object("bundle", b));
                  schema =
                      models.schemaAction(
                          ce,
                          Json.text(schema, "id"),
                          "submit",
                          Json.obj(),
                          schema.path("rowVersion").asText());
                  models.schemaAction(
                      cr,
                      Json.text(schema, "id"),
                      "approve",
                      Json.obj(),
                      schema.path("rowVersion").asText());
                } catch (java.io.IOException e) {
                  throw new IllegalStateException(e);
                }
              }
            }
            for (String target : List.of("erp-a", "erp-b")) {
              var sys =
                  db.maybe(
                      "select * from integration_system where tenant_id=? and code=?",
                      tid,
                      target.toUpperCase());
              if (sys == null) {
                var cfg =
                    Json.object(
                        "url",
                        "http://localhost:9090/targets/" + target + "/materials",
                        "healthUrl",
                        "http://localhost:9090/health",
                        "queryUrl",
                        "http://localhost:9090/targets/" + target + "/events/{eventId}",
                        "identityQueryUrl",
                        "http://localhost:9090/targets/" + target + "/materials/{externalId}",
                        "contractTestUrl",
                        "http://localhost:9090/targets/" + target + "/validate",
                        "idempotent",
                        true,
                        "confirmationPollSeconds",
                        1,
                        "confirmationTimeoutSeconds",
                        60,
                        "timeoutMs",
                        3000,
                        "inboundUserId",
                        editor,
                        "contractVerified",
                        false);
                sys =
                    (ObjectNode)
                        integrations.saveSystem(
                            ce,
                            null,
                            Json.object(
                                "code",
                                target.toUpperCase(),
                                "name",
                                target.equals("erp-a") ? "ERP 模拟 · 物料接口" : "ERP 模拟 · 备选映射",
                                "config",
                                cfg,
                                "clientKey",
                                "dev-" + t[0] + "-" + target),
                            null);
                ArrayNode fs = Json.arr();
                String prefix = target.equals("erp-a") ? "ITEM_" : "MATERIAL_";
                fs.add(
                    Json.object(
                        "source", "materialNo", "target", prefix + "CODE", "required", true));
                fs.add(
                    Json.object(
                        "source", "materialName", "target", prefix + "NAME", "required", true));
                fs.add(
                    Json.object(
                        "source",
                        "status",
                        "target",
                        "ENABLED",
                        "dictionary",
                        Json.object("ACTIVE", true, "INACTIVE", false),
                        "required",
                        true));
                fs.add(Json.object("source", "attributes.width", "target", "WIDTH", "unit", "mm"));
                var mapping =
                    integrations.createMapping(
                        ce,
                        Json.text(sys, "id"),
                        Json.object(
                            "config",
                            Json.object(
                                "fields",
                                fs,
                                "units",
                                models
                                    .currentSchema(ce, "GLASS_CLOTH", null)
                                    .path("bundle")
                                    .path("units"),
                                "policies",
                                Json.object(
                                    "id",
                                    "REJECT",
                                    "materialNo",
                                    "REJECT",
                                    "schemaVersionId",
                                    "REJECT",
                                    "materialName",
                                    "REVIEW",
                                    "attributes.description",
                                    "ACCEPT",
                                    "status",
                                    "REVIEW"),
                                "samples",
                                Json.arr()
                                    .add(
                                        Json.object(
                                            "input",
                                            Json.object(
                                                "materialNo",
                                                "TEST",
                                                "materialName",
                                                "映射回归",
                                                "status",
                                                "ACTIVE"))))));
                integrations.mappingAction(
                    ce,
                    Json.text(mapping, "id"),
                    "publish",
                    Json.obj(),
                    mapping.path("rowVersion").asText());
              }
            }
          }
          return Json.object(
              "status",
              "INITIALIZED",
              "createdTenants",
              created,
              "note",
              "显式且可重复初始化，不清空或覆盖已有业务数据；集成需测试后手动启用");
        });
  }
}
