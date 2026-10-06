package com.acme.mdm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/mdm_test}",
      "mdm.worker-delay=60000",
      "mdm.retry-delays=1,1,1,1"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BusinessTest {
  @Autowired Db db;
  @Autowired Dev dev;
  @Autowired Models models;
  @Autowired Materials materials;
  @Autowired Rules rules;
  @Autowired Auth auth;
  @Autowired Search search;
  @Autowired Tenants tenants;
  Ctx edit, review, other;
  String prefix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

  @BeforeAll
  void seed() {
    dev.seed();
    var a = db.one("select id from tenant where code='TENANT-A'");
    var b = db.one("select id from tenant where code='TENANT-B'");
    String editor = Json.text(db.one("select id from business_user where code='editor'"), "id"),
        approver = Json.text(db.one("select id from business_user where code='reviewer'"), "id");
    db.tx(
        () -> {
          db.run(
              "update tenant_member set roles=roles || ?::jsonb where tenant_id=? and user_id=?",
              Json.str(Json.arr().add("APPROVER").add("PUBLISHER")),
              models.uuid(Json.text(a, "id")),
              models.uuid(editor));
          return null;
        });
    edit = auth.worker(Json.text(a, "id"), editor);
    review = auth.worker(Json.text(a, "id"), approver);
    other = auth.worker(Json.text(b, "id"), editor);
  }

  ObjectNode basic(boolean sequence) {
    var attrs =
        Json.arr()
            .add(
                Json.object(
                    "code",
                    "name",
                    "label",
                    "名称",
                    "type",
                    "STRING",
                    "required",
                    true,
                    "searchable",
                    true,
                    "sortable",
                    true))
            .add(
                Json.object(
                    "code",
                    "amount",
                    "type",
                    "DECIMAL",
                    "unit",
                    "mm",
                    "scale",
                    6,
                    "min",
                    0,
                    "searchable",
                    true))
            .add(Json.object("code", "flag", "type", "BOOLEAN", "searchable", true))
            .add(Json.object("code", "note", "type", "STRING"));
    var seg =
        Json.arr()
            .add(Json.object("type", "CONST", "value", "T" + prefix))
            .add(Json.object("type", "ATTR", "field", "name"));
    if (sequence) seg.add(Json.object("type", "SEQUENCE", "name", "MAIN", "width", 6));
    return Json.object(
        "attributes",
        attrs,
        "codeRule",
        Json.object("separator", "-", "segments", seg),
        "samples",
        Json.arr());
  }

  JsonNode publish(String code, ObjectNode b) {
    return db.tx(
        () -> {
          models.createCategory(edit, Json.object("code", code, "name", code));
          var s = models.createSchema(edit, code, Json.object("bundle", b));
          s =
              models.schemaAction(
                  edit, Json.text(s, "id"), "submit", Json.obj(), s.path("rowVersion").asText());
          return models.schemaAction(
              review, Json.text(s, "id"), "approve", Json.obj(), s.path("rowVersion").asText());
        });
  }

  ObjectNode attrs(String n) {
    return Json.object("name", n, "amount", Json.object("value", 0, "unit", "mm"), "flag", false);
  }

  JsonNode create(JsonNode schema, String n) {
    return db.tx(
        () ->
            materials.create(
                edit,
                Json.object(
                    "schemaVersionId",
                    schema.path("id"),
                    "materialName",
                    n,
                    "attributes",
                    attrs(n))));
  }

  JsonNode submit(JsonNode m) {
    return db.tx(
        () -> {
          var r =
              materials.newRequest(
                  edit,
                  Json.text(m, "id"),
                  Json.object("kind", "NEW"),
                  m.path("rowVersion").asText());
          return materials.action(
              edit, Json.text(r, "id"), "submit", Json.obj(), r.path("rowVersion").asText());
        });
  }

  JsonNode approve(JsonNode r) {
    return db.tx(
        () ->
            materials.action(
                review, Json.text(r, "id"), "approve", Json.obj(), r.path("rowVersion").asText()));
  }

  @Test
  void decimalAndPatchSemantics() {
    var s = publish("PATCH_" + prefix, basic(false));
    var m = create(s, "PATCH");
    var patched =
        db.tx(
            () ->
                materials.patch(
                    edit,
                    Json.text(m, "id"),
                    Json.object(
                        "attributes",
                        Json.object(
                            "note",
                            NullNode.instance,
                            "amount",
                            Json.object("value", new BigDecimal("1.270000"), "unit", "m"))),
                    m.path("rowVersion").asText()));
    assertFalse(patched.path("attributes").path("flag").asBoolean());
    assertTrue(patched.path("attributes").path("note").isNull());
    assertEquals(
        0,
        new BigDecimal("1270").compareTo(Json.decimal(patched.path("attributes").path("amount"))));
    assertEquals("PATCH", patched.path("attributes").path("name").asText());
    assertEquals(
        "VERSION_CONFLICT",
        assertThrows(
                Problem.class,
                () ->
                    db.tx(
                        () ->
                            materials.patch(
                                edit,
                                Json.text(m, "id"),
                                Json.object("attributes", Json.obj()),
                                m.path("rowVersion").asText())))
            .code);
  }

  @Test
  void validationDerivedAndConditionFailures() {
    var b = basic(false);
    ((ArrayNode) b.path("attributes"))
        .add(
            Json.object(
                "code",
                "conditional",
                "type",
                "STRING",
                "visibleWhen",
                false,
                "requiredWhen",
                Json.object(
                    "op", "eq", "args", Json.arr().add(Json.object("field", "flag")).add(true))));
    assertThrows(
        Problem.class,
        () ->
            rules.normalize(
                edit,
                models.enrich(edit, b, 1),
                Json.object(
                    "name", "A", "flag", true, "amount", Json.object("value", 1, "unit", "kg")),
                true,
                true));
    ((ArrayNode) b.path("attributes"))
        .add(
            Json.object(
                "code",
                "derived",
                "type",
                "DECIMAL",
                "derived",
                Json.object("op", "div", "args", Json.arr().add(1).add(0))));
    var derived = models.enrich(edit, b, 1);
    Problem p =
        assertThrows(Problem.class, () -> rules.normalize(edit, derived, attrs("A"), true, true));
    assertTrue(p.errors.toString().contains("DIVIDE_BY_ZERO"));
    Problem fake =
        assertThrows(
            Problem.class,
            () ->
                rules.normalize(
                    edit,
                    derived,
                    Json.object("name", "A", "derived", Json.object("value", 0)),
                    true,
                    true));
    assertTrue(fake.errors.toString().contains("DERIVED_READONLY"));
    var cycle = basic(false);
    ((ArrayNode) cycle.path("attributes"))
        .add(Json.object("code", "x", "type", "DECIMAL", "derived", Json.object("field", "y")))
        .add(Json.object("code", "y", "type", "DECIMAL", "derived", Json.object("field", "x")));
    assertEquals("CYCLIC_DEPENDENCY", assertThrows(Problem.class, () -> rules.compile(cycle)).code);
  }

  @Test
  void snapshotsSurviveNewRequiredVersion() {
    String code = "VERSION_" + prefix;
    var s = publish(code, basic(false));
    var m = create(s, "VERSION");
    approve(submit(m));
    var b = basic(false);
    ((ArrayNode) b.path("attributes"))
        .add(Json.object("code", "newRequired", "type", "STRING", "required", true));
    var v2 =
        db.tx(
            () -> {
              var x = models.createSchema(edit, code, Json.object("bundle", b));
              x =
                  models.schemaAction(
                      edit,
                      Json.text(x, "id"),
                      "submit",
                      Json.obj(),
                      x.path("rowVersion").asText());
              return models.schemaAction(
                  review, Json.text(x, "id"), "approve", Json.obj(), x.path("rowVersion").asText());
            });
    assertNotEquals(s.path("id"), v2.path("id"));
    var history = materials.history(edit, Json.text(m, "id"));
    assertFalse(
        history.path(0).path("snapshot").path("schemaBundle").toString().contains("newRequired"));
    assertEquals(
        s.path("id"), materials.get(edit, Json.text(m, "id"), false).path("schemaVersionId"));
    assertEquals(
        "IMMUTABLE_SCHEMA",
        assertThrows(
                Problem.class,
                () ->
                    db.tx(
                        () ->
                            models.patchSchema(
                                edit, Json.text(s, "id"), Json.object("bundle", b), "3")))
            .code);
    db.tx(
        () -> {
          models.schemaAction(
              review, Json.text(s, "id"), "retire", Json.obj(), s.path("rowVersion").asText());
          return null;
        });
    assertEquals("SCHEMA_RETIRED", assertThrows(Problem.class, () -> create(s, "RETIRED")).code);
  }

  @Test
  void noSequenceConflictAndSeparateTenants() {
    String code = "UNIQUE_" + prefix;
    var s = publish(code, basic(false));
    var first = create(s, "SAME");
    approve(submit(first));
    var second = create(s, "SAME");
    var r = submit(second);
    assertThrows(DataIntegrityViolationException.class, () -> approve(r));
    assertNull(materials.get(edit, Json.text(second, "id"), false).get("materialNo").textValue());
    assertEquals("REVIEW", materials.request(edit, Json.text(r, "id")).path("state").asText());
    var os =
        db.tx(
            () -> {
              models.createCategory(other, Json.object("code", code, "name", code));
              var x = models.createSchema(other, code, Json.object("bundle", basic(false)));
              var otherReviewer = auth.worker(other.tenant(), review.user());
              x =
                  models.schemaAction(
                      other,
                      Json.text(x, "id"),
                      "submit",
                      Json.obj(),
                      x.path("rowVersion").asText());
              return models.schemaAction(
                  otherReviewer,
                  Json.text(x, "id"),
                  "approve",
                  Json.obj(),
                  x.path("rowVersion").asText());
            });
    var om =
        db.tx(
            () ->
                materials.create(
                    other,
                    Json.object(
                        "schemaVersionId",
                        os.path("id"),
                        "materialName",
                        "SAME",
                        "attributes",
                        attrs("SAME"))));
    var or =
        db.tx(
            () -> {
              var q =
                  materials.newRequest(other, Json.text(om, "id"), Json.object("kind", "NEW"), "1");
              return materials.action(other, Json.text(q, "id"), "submit", Json.obj(), "1");
            });
    var otherReviewer = auth.worker(other.tenant(), review.user());
    db.tx(() -> materials.action(otherReviewer, Json.text(or, "id"), "approve", Json.obj(), "2"));
    assertEquals(
        materials.get(edit, Json.text(first, "id"), false).path("materialNo"),
        materials.get(other, Json.text(om, "id"), false).path("materialNo"));
    assertThrows(Problem.class, () -> materials.get(other, Json.text(first, "id"), true));
  }

  @Test
  void transactionRollbackLeavesNoOfficialEvent() {
    var s = publish("ROLLBACK_" + prefix, basic(true));
    var m = create(s, "ROLLBACK");
    var r = submit(m);
    long before =
        db.count(
            "select count(*) from outbox_event where tenant_id=? and material_id=?",
            edit.tid(),
            models.uuid(Json.text(m, "id")));
    assertThrows(
        Problem.class,
        () ->
            db.tx(
                () -> {
                  materials.action(review, Json.text(r, "id"), "approve", Json.obj(), "2");
                  throw new Problem(422, "FORCED_ROLLBACK", "测试事务回滚");
                }));
    assertEquals(
        before,
        db.count(
            "select count(*) from outbox_event where tenant_id=? and material_id=?",
            edit.tid(),
            models.uuid(Json.text(m, "id"))));
    assertEquals(
        "IN_REVIEW", materials.get(edit, Json.text(m, "id"), false).path("status").asText());
    approve(r);
  }

  @Test
  void idempotencyBodyScopeAndConcurrentDuplicate() throws Exception {
    var s = publish("IDEMPOTENCY_" + prefix, basic(true));
    ObjectNode b =
        Json.object(
            "schemaVersionId", s.path("id"), "materialName", "KEY", "attributes", attrs("KEY"));
    String key = UUID.randomUUID().toString();
    var m = db.idem(edit, "CREATE", key, b, () -> materials.create(edit, b));
    var same =
        db.idem(
            edit,
            "CREATE",
            key,
            b,
            () -> {
              fail("duplicate executed");
              return null;
            });
    assertEquals(m.path("id"), same.path("id"));
    assertEquals(
        "IDEMPOTENCY_CONFLICT",
        assertThrows(
                Problem.class,
                () ->
                    db.idem(edit, "CREATE", key, Json.object("different", true), () -> Json.obj()))
            .code);
    var r = submit(m);
    assertEquals(
        "SELF_APPROVAL",
        assertThrows(
                Problem.class,
                () ->
                    db.tx(
                        () ->
                            materials.action(edit, Json.text(r, "id"), "approve", Json.obj(), "2")))
            .code);
    String approvalKey = UUID.randomUUID().toString();
    try (var pool = Executors.newFixedThreadPool(2)) {
      var tasks = new ArrayList<Future<JsonNode>>();
      for (int i = 0; i < 2; i++)
        tasks.add(
            pool.submit(
                () ->
                    db.idem(
                        review,
                        "APPROVE",
                        approvalKey,
                        Json.object("id", r.path("id")),
                        () ->
                            materials.action(
                                review, Json.text(r, "id"), "approve", Json.obj(), "2"))));
      assertEquals(tasks.get(0).get().path("id"), tasks.get(1).get().path("id"));
    }
    assertEquals(
        1,
        db.count(
            "select count(*) from outbox_event where tenant_id=? and material_id=?",
            edit.tid(),
            models.uuid(Json.text(m, "id"))));
  }

  @Test
  void hundredConcurrentSequenceApprovals() throws Exception {
    var s = publish("SEQ_" + prefix, basic(true));
    List<JsonNode> requests = new ArrayList<>();
    for (int i = 0; i < 100; i++) requests.add(submit(create(s, "BATCH")));
    assertEquals(
        0,
        db.count(
            "select count(*) from number_sequence where tenant_id=? and rule_id=?",
            edit.tid(),
            models.uuid(Json.text(s, "id"))));
    var n = rules.number(edit, Json.text(s, "id"), s.path("bundle"), attrs("BATCH"), false);
    assertTrue(n.contains("{流水"));
    try (var pool = Executors.newFixedThreadPool(100)) {
      List<Future<JsonNode>> f = new ArrayList<>();
      for (var r : requests) f.add(pool.submit(() -> approve(r)));
      for (var future : f) future.get(60, TimeUnit.SECONDS);
    }
    var rows =
        db.list(
            "select material_no from material where tenant_id=? and schema_version_id=? and"
                + " status='ACTIVE'",
            edit.tid(),
            models.uuid(Json.text(s, "id")));
    assertEquals(100, rows.size());
    assertEquals(100, rows.stream().map(x -> Json.text(x, "materialNo")).distinct().count());
    assertEquals(
        100,
        db.count(
            "select next_value from number_sequence where tenant_id=? and rule_id=?",
            edit.tid(),
            models.uuid(Json.text(s, "id"))));
  }

  @Test
  void formalChangeProtectionLifecycleAndPredecessor() {
    var s = publish("LIFE_" + prefix, basic(false));
    var m = create(s, "LIFE");
    approve(submit(m));
    m = materials.get(edit, Json.text(m, "id"), false);
    final JsonNode current = m;
    assertEquals(
        "CODE_ATTRIBUTE_PROTECTED",
        assertThrows(
                Problem.class,
                () ->
                    db.tx(
                        () ->
                            materials.newRequest(
                                edit,
                                Json.text(current, "id"),
                                Json.object(
                                    "kind",
                                    "CHANGE",
                                    "reason",
                                    "修改规格",
                                    "candidate",
                                    Json.object("attributes", Json.object("name", "OTHER"))),
                                current.path("rowVersion").asText())))
            .code);
    var change =
        db.tx(
            () ->
                materials.newRequest(
                    edit,
                    Json.text(current, "id"),
                    Json.object(
                        "kind",
                        "CHANGE",
                        "reason",
                        "修改名称",
                        "candidate",
                        Json.object("materialName", "新名称")),
                    current.path("rowVersion").asText()));
    String changeId = Json.text(change, "id");
    change = db.tx(() -> materials.action(edit, changeId, "submit", Json.obj(), "1"));
    assertEquals(
        "LIFE", materials.get(edit, Json.text(current, "id"), false).path("materialName").asText());
    approve(change);
    var now = materials.get(edit, Json.text(current, "id"), false);
    assertEquals("新名称", now.path("materialName").asText());
    var e =
        db.one(
            "select * from outbox_event where tenant_id=? and material_id=? order by row_version"
                + " desc limit 1",
            edit.tid(),
            models.uuid(Json.text(current, "id")));
    assertEquals(3, e.path("previousPublishedVersion").asLong());
    for (String kind : List.of("DEACTIVATE", "REACTIVATE")) {
      var x = materials.get(edit, Json.text(current, "id"), false);
      var q =
          db.tx(
              () ->
                  materials.newRequest(
                      edit,
                      Json.text(current, "id"),
                      Json.object("kind", kind, "reason", "生命周期验证"),
                      x.path("rowVersion").asText()));
      String rid = Json.text(q, "id");
      q = db.tx(() -> materials.action(edit, rid, "submit", Json.obj(), "1"));
      approve(q);
    }
    assertEquals(
        "ACTIVE", materials.get(edit, Json.text(current, "id"), false).path("status").asText());
  }

  JsonNode changeRequestId(JsonNode r) {
    return Json.object("id", r.path("id"));
  }

  @Test
  void referenceOwnershipAndTypedQueries() {
    var b = basic(false);
    ((ArrayNode) b.path("attributes"))
        .add(Json.object("code", "supplier", "type", "REFERENCE", "referenceType", "SUPPLIER"));
    var enriched = models.enrich(edit, b, 1);
    assertEquals(
        "VALIDATION_ERROR",
        assertThrows(
                Problem.class,
                () ->
                    rules.normalize(
                        edit,
                        enriched,
                        Json.object(
                            "name",
                            "REF",
                            "supplier",
                            Json.object("type", "SUPPLIER", "id", "PRIVATE_B_ONLY")),
                        true,
                        true))
            .code);
    db.tx(
        () -> {
          db.run(
              "insert into reference_entity(tenant_id,type,id,name)"
                  + " values(?,'SUPPLIER','PRIVATE_B_ONLY','租户B私有') on conflict do nothing",
              other.tid());
          return null;
        });
    assertTrue(
        assertThrows(
                Problem.class,
                () ->
                    rules.normalize(
                        edit,
                        enriched,
                        Json.object(
                            "name",
                            "REF",
                            "supplier",
                            Json.object("type", "SUPPLIER", "id", "PRIVATE_B_ONLY")),
                        true,
                        true))
            .errors
            .toString()
            .contains("REFERENCE_OWNERSHIP"));
    assertEquals(
        "FILTER_FIELD",
        assertThrows(
                Problem.class,
                () ->
                    search.find(
                        edit,
                        Json.object(
                            "categoryCode",
                            "GLASS_CLOTH",
                            "filters",
                            Json.arr()
                                .add(
                                    Json.object(
                                        "field", "attributes.unknown", "op", "EQ", "value", 1)))))
            .code);
    assertEquals(
        "FILTER_OPERATOR",
        assertThrows(
                Problem.class,
                () ->
                    search.find(
                        edit,
                        Json.object(
                            "categoryCode",
                            "GLASS_CLOTH",
                            "filters",
                            Json.arr()
                                .add(
                                    Json.object(
                                        "field",
                                        "attributes.model",
                                        "op",
                                        "BETWEEN",
                                        "value",
                                        Json.arr().add(1).add(2))))))
            .code);
  }

  @Test
  void scopeAndTenantSuspendRestore() {
    var limited =
        new Ctx(
            edit.tenant(),
            edit.user(),
            Set.of("READ"),
            Set.of("BEARING"),
            UUID.randomUUID().toString());
    assertThrows(Problem.class, () -> models.currentSchema(limited, "GLASS_CLOTH", null));
    assertTrue(
        models.categories(limited).stream()
            .allMatch(c -> c.path("code").asText().equals("BEARING")));
    var t = db.one("select * from tenant where id=?", other.tid());
    db.tx(
        () ->
            tenants.action(
                edit.user(),
                other.tenant(),
                "suspend",
                Json.object("reason", "暂停恢复验证"),
                t.path("rowVersion").asText()));
    assertEquals(
        "TENANT_PAUSED", assertThrows(Problem.class, () -> auth.active(other.tenant())).code);
    var suspended = db.one("select * from tenant where id=?", other.tid());
    db.tx(
        () ->
            tenants.action(
                edit.user(),
                other.tenant(),
                "resume",
                Json.object("reason", "恢复验证"),
                suspended.path("rowVersion").asText()));
    auth.active(other.tenant());
  }
}
