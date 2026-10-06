package com.acme.mdm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/mdm_test}",
      "mdm.worker-delay=60000"
    })
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class NumberingTest {
  @Autowired Db db;
  @Autowired Dev dev;
  @Autowired Auth auth;
  @Autowired Models models;
  @Autowired ReleasePackages release;
  @Autowired SourceMetadata sources;
  @Autowired Assignments assignments;
  Ctx c, review;
  String tag = "N" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), sid, category, oid;
  ObjectNode rel;

  @BeforeAll
  void setup() {
    dev.seed();
    String tid = db.one("select id from tenant where code='TENANT-A'").path("id").asText();
    c =
        auth.worker(
            tid, db.one("select id from business_user where code='editor'").path("id").asText());
    review =
        auth.worker(
            tid, db.one("select id from business_user where code='reviewer'").path("id").asText());
    db.tx(
        () -> {
          models.createCategory(c, Json.object("code", tag));
          return null;
        });
    category = tag;
    var source =
        db.tx(
            () ->
                sources.save(
                    c,
                    Json.object(
                        "code",
                        tag,
                        "connectionProfile",
                        Json.object(
                            "jdbcUrl",
                            "jdbc:postgresql://localhost:5432/mdm_erp_v3",
                            "userEnv",
                            "ERP_READ_USER",
                            "passwordEnv",
                            "ERP_READ_PASSWORD",
                            "writebackBaseUrl",
                            "http://127.0.0.1:9092")),
                    null,
                    null));
    sid = source.path("id").asText();
    db.tx(() -> sources.save(c, Json.object("status", "ACTIVE"), sid, "1"));
    oid =
        db.tx(
                () ->
                    sources.discover(
                        c,
                        sid,
                        Json.object(
                            "objects", Json.arr().add(Json.object("objectName", "flat_material")))))
            .path(0)
            .path("id")
            .asText();
    // JSON document API uses the same mapping/schema/identity pipeline without a category branch.
    var apiDef =
        Json.object(
            "objectName",
            "document",
            "objectType",
            "API",
            "fields",
            Json.arr()
                .add(Json.object("name", "category", "dataType", "VARCHAR"))
                .add(Json.object("name", "id", "dataType", "VARCHAR", "nullable", false))
                .add(Json.object("name", "name", "dataType", "VARCHAR"))
                .add(Json.object("name", "note", "dataType", "VARCHAR"))
                .add(Json.object("name", "materialNo", "dataType", "VARCHAR"))
                .add(Json.object("name", "sourceVersion", "dataType", "INTEGER"))
                .add(Json.object("name", "sourceUpdatedAt", "dataType", "TIMESTAMP")),
            "primaryKey",
            Json.arr().add("id"));
    var api =
        db.tx(
            () ->
                sources.save(
                    c,
                    Json.object(
                        "code",
                        tag + "API",
                        "type",
                        "REST",
                        "connectionProfile",
                        Json.object("baseUrl", "http://127.0.0.1:9092", "allowManualAssign", true)),
                    null,
                    null));
    sid = api.path("id").asText();
    db.tx(() -> sources.save(c, Json.object("status", "ACTIVE"), sid, "1"));
    oid = db.tx(() -> sources.createObject(c, sid, apiDef, "MANUAL", null)).path("id").asText();
    rel = createRelease(true);
    rel = publish(rel);
  }

  ObjectNode createRelease(boolean seq) {
    return db.tx(
        () -> {
          var code =
              Json.object(
                  "separator",
                  "-",
                  "segments",
                  Json.arr()
                      .add(Json.object("type", "CONST", "value", tag))
                      .add(Json.object("type", "ATTR", "field", "name")));
          if (seq)
            ((ArrayNode) code.path("segments")).add(Json.object("type", "SEQUENCE", "width", 6));
          var schema =
              models.createSchema(
                  c,
                  category,
                  Json.object(
                      "bundle",
                      Json.object(
                          "attributes",
                          Json.arr()
                              .add(
                                  Json.object(
                                      "code",
                                      "name",
                                      "type",
                                      "STRING",
                                      "required",
                                      true,
                                      "searchable",
                                      true))
                              .add(Json.object("code", "note", "type", "STRING")),
                          "codeRule",
                          code)));
          var ds =
              release.saveConfig(
                  c,
                  "DATASET",
                  Json.object(
                      "code",
                      tag,
                      "sourceSystemId",
                      sid,
                      "definition",
                      Json.object(
                          "root",
                          Json.object("alias", "g", "objectVersionId", oid),
                          "sourceKey",
                          Json.arr().add("g.id"),
                          "grain",
                          "ONE_ROOT_ROW_ONE_MATERIAL",
                          "resource",
                          "/documents/{key}",
                          "scanResource",
                          "/documents",
                          "candidateFilter",
                          Json.object(
                              "op",
                              "eq",
                              "args",
                              Json.arr().add(Json.object("field", "g.category")).add(tag)),
                          "output",
                          Json.object("name", "g.name", "note", "g.note"),
                          "existingNoField",
                          "g.materialNo",
                          "sourceVersionField",
                          "g.sourceVersion")),
                  null,
                  null);
          var mp =
              release.saveConfig(
                  c,
                  "MAPPING",
                  Json.object(
                      "code",
                      tag,
                      "categoryCode",
                      category,
                      "definition",
                      Json.object(
                          "fields",
                          Json.arr()
                              .add(
                                  Json.object("target", "name", "source", "name", "format", "TRIM"))
                              .add(Json.object("target", "note", "source", "note")))),
                  null,
                  null);
          var identity =
              release.saveConfig(
                  c,
                  "IDENTITY",
                  Json.object(
                      "code",
                      tag,
                      "categoryCode",
                      category,
                      "definition",
                      Json.object(
                          "fields", Json.arr().add(Json.object("field", "name", "case", "UPPER")))),
                  null,
                  null);
          var rule =
              release.saveConfig(
                  c,
                  "CODE_RULE",
                  Json.object("code", tag, "categoryCode", category, "definition", code),
                  null,
                  null);
          return (ObjectNode)
              release.create(
                  c,
                  Json.object(
                      "code",
                      tag,
                      "categoryCode",
                      category,
                      "datasetVersionId",
                      ds.path("id"),
                      "schemaVersionId",
                      schema.path("id"),
                      "mappingVersionId",
                      mp.path("id"),
                      "identityDefinitionVersionId",
                      identity.path("id"),
                      "codeRuleVersionId",
                      rule.path("id"),
                      "definition",
                      Json.object(
                          "samples",
                          Json.arr()
                              .add(
                                  Json.object(
                                      "sourceRecordKey",
                                      "sample",
                                      "payload",
                                      Json.object(
                                          "id",
                                          "sample",
                                          "category",
                                          tag,
                                          "name",
                                          "SAMPLE",
                                          "note",
                                          "fixture"),
                                      "expectedMaterialNo",
                                      tag + "-SAMPLE" + (seq ? "-{流水:6}" : ""))),
                          "writeback",
                          Json.object(
                              "baseUrl",
                              "http://127.0.0.1:9092",
                              "path",
                              "/writeback/flat_material/{key}",
                              "queryPath",
                              "/writeback/flat_material/{key}",
                              "supportsIdempotency",
                              true,
                              "contractPath",
                              "/contract",
                              "businessValidationPath",
                              "/contract/validate",
                              "conditionalEmptyWrite",
                              true))));
        });
  }

  ObjectNode publish(ObjectNode r) {
    var submit =
        db.tx(
            () ->
                release.action(
                    c, r.path("id").asText(), "submit", Json.obj(), r.path("rowVersion").asText()));
    return (ObjectNode)
        db.tx(
            () ->
                release.action(
                    review,
                    r.path("id").asText(),
                    "approve",
                    Json.obj(),
                    submit.path("rowVersion").asText()));
  }

  ObjectNode body(String key, String name) {
    return Json.object(
        "releaseId",
        rel.path("id"),
        "sourceRecordKey",
        key,
        "payload",
        Json.object("id", key, "category", tag, "name", name, "note", "first"));
  }

  @Test
  void previewNeverAllocates() {
    long seq =
        db.count(
            "select count(*) from number_sequence where tenant_id=? and rule_id=?",
            c.tid(),
            SourceMetadata.id(rel.path("codeRuleId").asText()));
    long count = db.count("select count(*) from material_assignment where tenant_id=?", c.tid());
    assignments.preview(c, body(tag + "PRE", "PREVIEW"));
    assertEquals(
        seq,
        db.count(
            "select count(*) from number_sequence where tenant_id=? and rule_id=?",
            c.tid(),
            SourceMetadata.id(rel.path("codeRuleId").asText())));
    assertEquals(
        count, db.count("select count(*) from material_assignment where tenant_id=?", c.tid()));
  }

  @Test
  void immutableLedgerAndAtomicOutbox() {
    var a = assignments.issue(c, body(tag + "ONE", "ONE"), UUID.randomUUID().toString());
    String id = a.path("assignmentId").asText();
    assertEquals(
        1,
        db.count(
            "select count(*) from numbering_outbox where tenant_id=? and assignment_id=?",
            c.tid(),
            SourceMetadata.id(id)));
    assertEquals(
        1,
        db.count(
            "select count(*) from writeback_task where tenant_id=? and assignment_id=?",
            c.tid(),
            SourceMetadata.id(id)));
    var explain = assignments.explain(c, id);
    assertEquals(3, explain.path("explanation").path("segments").size());
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.tx(
                () ->
                    db.run(
                        "update material_assignment set material_no='MUTATE' where id=?",
                        SourceMetadata.id(id))));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.tx(
                () -> db.run("delete from material_assignment where id=?", SourceMetadata.id(id))));
  }

  @Test
  void sourceIdempotencyAcrossReleaseAndMetadataChange() {
    var b = body(tag + "REPLAY", "REPLAY");
    var a = assignments.issue(c, b, UUID.randomUUID().toString());
    ((ObjectNode) b.path("payload")).put("note", "updated");
    var r = assignments.issue(c, b, UUID.randomUUID().toString());
    assertEquals(a.path("materialNo"), r.path("materialNo"));
    assertTrue(r.path("replayed").asBoolean());
    ((ObjectNode) b.path("payload")).put("name", "DIFFERENT");
    assertEquals(
        "IDENTITY_CHANGED_AFTER_ISSUE",
        assertThrows(Problem.class, () -> assignments.issue(c, b, UUID.randomUUID().toString()))
            .code);
    assertEquals(
        "updated",
        db.one(
                "select attributes from material_source_snapshot where tenant_id=? and"
                    + " source_record_key=?",
                c.tid(),
                tag + "REPLAY")
            .path("attributes")
            .path("note")
            .asText());
  }

  @Test
  void sameIdentityConflictDoesNotConsumeSequence() {
    assignments.issue(c, body(tag + "I1", "IDENTITY"), UUID.randomUUID().toString());
    long before =
        db.count(
            "select next_value from number_sequence where tenant_id=? and rule_id=?",
            c.tid(),
            SourceMetadata.id(rel.path("codeRuleId").asText()));
    assertEquals(
        "IDENTITY_CONFLICT",
        assertThrows(
                Problem.class,
                () ->
                    assignments.issue(
                        c, body(tag + "I2", "identity"), UUID.randomUUID().toString()))
            .code);
    assertEquals(
        before,
        db.count(
            "select next_value from number_sequence where tenant_id=? and rule_id=?",
            c.tid(),
            SourceMetadata.id(rel.path("codeRuleId").asText())));
  }

  @Test
  void concurrentSourceHasOneAssignment() throws Exception {
    String key = tag + "CONCURRENT";
    try (var pool = Executors.newFixedThreadPool(16)) {
      List<Future<JsonNode>> tasks = new ArrayList<>();
      for (int i = 0; i < 100; i++)
        tasks.add(
            pool.submit(
                () -> assignments.issue(c, body(key, "CONCURRENT"), UUID.randomUUID().toString())));
      Set<String> nos = new HashSet<>();
      for (var task : tasks) nos.add(task.get(30, TimeUnit.SECONDS).path("materialNo").asText());
      assertEquals(1, nos.size());
      assertEquals(
          1,
          db.count(
              "select count(*) from material_assignment where tenant_id=? and dataset_code=? and"
                  + " source_record_key=?",
              c.tid(),
              tag,
              key));
    }
  }

  @Test
  void publicationBlocksSelfAndMutation() {
    assertEquals(
        "IMMUTABLE_CONFIGURATION",
        assertThrows(
                Problem.class,
                () ->
                    db.tx(
                        () ->
                            release.saveConfig(
                                c,
                                "CODE_RULE",
                                Json.object(
                                    "definition", rel.path("dependencySnapshot").path("codeRule")),
                                rel.path("codeRuleId").asText(),
                                "1")))
            .code);
    var r = createRelease(false);
    var s = db.tx(() -> release.action(c, r.path("id").asText(), "submit", Json.obj(), "1"));
    var membership =
        db.one("select roles from tenant_member where tenant_id=? and user_id=?", c.tid(), c.uid());
    db.run(
        "update tenant_member set roles=roles||'[\"PUBLISHER\"]'::jsonb where tenant_id=? and"
            + " user_id=?",
        c.tid(),
        c.uid());
    try {
      var self = auth.worker(c.tenant(), c.user());
      assertEquals(
          "SELF_APPROVAL",
          assertThrows(
                  Problem.class,
                  () ->
                      db.tx(
                          () ->
                              release.action(
                                  self,
                                  r.path("id").asText(),
                                  "approve",
                                  Json.obj(),
                                  s.path("rowVersion").asText())))
              .code);
    } finally {
      db.run(
          "update tenant_member set roles=?::jsonb where tenant_id=? and user_id=?",
          Json.str(membership.path("roles")),
          c.tid(),
          c.uid());
    }
  }

  @Test
  void databaseFailureRollsBackSequenceLedgerTaskAndOutbox() {
    String name = "fail_outbox_" + tag.toLowerCase();
    String key = tag + "ROLLBACK";
    String ruleId = rel.path("codeRuleId").asText();
    long before =
        db.count(
            "select coalesce(sum(next_value),0) from number_sequence where tenant_id=? and"
                + " rule_id=?",
            c.tid(),
            SourceMetadata.id(ruleId));
    db.run(
        "create function "
            + name
            + "() returns trigger language plpgsql as $$ begin if"
            + " NEW.snapshot->>'sourceRecordKey'='"
            + key
            + "' then raise exception 'injected transaction failure'; end if; return NEW; end $$");
    db.run(
        "create trigger "
            + name
            + " before insert on numbering_outbox for each row execute function "
            + name
            + "()");
    try {
      assertThrows(
          org.springframework.dao.DataAccessException.class,
          () -> assignments.issue(c, body(key, "ROLLBACK"), UUID.randomUUID().toString()));
      assertEquals(
          before,
          db.count(
              "select coalesce(sum(next_value),0) from number_sequence where tenant_id=? and"
                  + " rule_id=?",
              c.tid(),
              SourceMetadata.id(ruleId)));
      assertEquals(
          0,
          db.count(
              "select count(*) from material_assignment where tenant_id=? and source_record_key=?",
              c.tid(),
              key));
      assertEquals(
          0,
          db.count(
              "select count(*) from numbering_outbox where tenant_id=? and"
                  + " snapshot->>'sourceRecordKey'=?",
              c.tid(),
              key));
    } finally {
      db.run("drop trigger " + name + " on numbering_outbox");
      db.run("drop function " + name + "()");
    }
    var good = assignments.issue(c, body(key, "ROLLBACK"), UUID.randomUUID().toString());
    assertTrue(good.hasNonNull("assignmentId"));
  }

  @Test
  void transientDatabaseConnectionLossRecoversWithoutDirtyAssignments() {
    try {
      db.tx(
          () -> {
            int pid = db.jdbc.queryForObject("select pg_backend_pid()", Integer.class);
            try (var con = db.jdbc.getDataSource().getConnection();
                var stmt = con.prepareStatement("select pg_terminate_backend(?)")) {
              stmt.setInt(1, pid);
              stmt.execute();
            } catch (java.sql.SQLException e) {
              throw new RuntimeException(e);
            }
            db.count("select 1");
            return null;
          });
      fail("Terminated DB connection must fail");
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException expected) {
    }
    var res =
        assignments.issue(c, body(tag + "DBRECOVER", "DBRECOVER"), UUID.randomUUID().toString());
    assertTrue(res.hasNonNull("assignmentId"));
    assertEquals(
        1,
        db.count(
            "select count(*) from material_assignment where tenant_id=? and source_record_key=?",
            c.tid(),
            tag + "DBRECOVER"));
  }

  @Test
  void identicalNumberIsLegalInDifferentTenants() {
    var a =
        assignments.issue(c, body(tag + "TENANT_A", "TENANTNUMBER"), UUID.randomUUID().toString());
    var other =
        auth.worker(
            db.one("select id from tenant where code='TENANT-B'").path("id").asText(), c.user());
    db.tx(
        () -> {
          models.createCategory(other, Json.object("code", tag));
          return null;
        });
    var source =
        db.tx(
            () ->
                sources.save(
                    other,
                    Json.object(
                        "code",
                        tag,
                        "type",
                        "REST",
                        "connectionProfile",
                        Json.object("baseUrl", "http://127.0.0.1:9092")),
                    null,
                    null));
    String sourceId = source.path("id").asText();
    db.tx(() -> sources.save(other, Json.object("status", "ACTIVE"), sourceId, "1"));
    var object =
        db.tx(
            () ->
                sources.createObject(
                    other, sourceId, sources.object(c, oid).path("definition"), "MANUAL", null));
    var bRelease =
        db.tx(
            () -> {
              var code =
                  Json.object(
                      "segments",
                      Json.arr().add(Json.object("type", "CONST", "value", a.path("materialNo"))));
              var schema =
                  models.createSchema(
                      other,
                      tag,
                      Json.object(
                          "bundle",
                          Json.object(
                              "attributes",
                              Json.arr()
                                  .add(
                                      Json.object(
                                          "code", "name", "type", "STRING", "required", true)),
                              "codeRule",
                              code)));
              var ds =
                  release.saveConfig(
                      other,
                      "DATASET",
                      Json.object(
                          "code",
                          tag,
                          "sourceSystemId",
                          sourceId,
                          "definition",
                          Json.object(
                              "root",
                              Json.object("objectVersionId", object.path("id"), "alias", "g"),
                              "sourceKey",
                              Json.arr().add("g.id"),
                              "grain",
                              "ONE_ROOT_ROW_ONE_MATERIAL",
                              "output",
                              Json.object("name", "g.name"))),
                      null,
                      null);
              var mp =
                  release.saveConfig(
                      other,
                      "MAPPING",
                      Json.object(
                          "code",
                          tag,
                          "categoryCode",
                          tag,
                          "definition",
                          Json.object(
                              "fields",
                              Json.arr().add(Json.object("target", "name", "source", "name")))),
                      null,
                      null);
              var identity =
                  release.saveConfig(
                      other,
                      "IDENTITY",
                      Json.object(
                          "code",
                          tag,
                          "categoryCode",
                          tag,
                          "definition",
                          Json.object("fields", Json.arr().add(Json.object("field", "name")))),
                      null,
                      null);
              var rule =
                  release.saveConfig(
                      other,
                      "CODE_RULE",
                      Json.object("code", tag, "categoryCode", tag, "definition", code),
                      null,
                      null);
              return release.create(
                  other,
                  Json.object(
                      "categoryCode",
                      tag,
                      "datasetVersionId",
                      ds.path("id"),
                      "schemaVersionId",
                      schema.path("id"),
                      "mappingVersionId",
                      mp.path("id"),
                      "identityDefinitionVersionId",
                      identity.path("id"),
                      "codeRuleVersionId",
                      rule.path("id"),
                      "definition",
                      Json.object(
                          "samples",
                          Json.arr()
                              .add(
                                  Json.object(
                                      "sourceRecordKey",
                                      "SAMPLE",
                                      "payload",
                                      Json.object("id", "SAMPLE", "name", "TENANTNUMBER"),
                                      "expectedMaterialNo",
                                      a.path("materialNo"))),
                          "writeback",
                          Json.object(
                              "baseUrl",
                              "http://127.0.0.1:9092",
                              "path",
                              "/writeback/flat_material/{key}",
                              "queryPath",
                              "/writeback/flat_material/{key}",
                              "contractPath",
                              "/contract",
                              "businessValidationPath",
                              "/contract/validate",
                              "conditionalEmptyWrite",
                              true,
                              "supportsIdempotency",
                              true))));
            });
    var submitted =
        db.tx(() -> release.action(other, bRelease.path("id").asText(), "submit", Json.obj(), "1"));
    var otherReviewer = auth.worker(other.tenant(), review.user());
    var approved =
        db.tx(
            () ->
                release.action(
                    otherReviewer,
                    bRelease.path("id").asText(),
                    "approve",
                    Json.obj(),
                    submitted.path("rowVersion").asText()));
    var b =
        assignments.issue(
            other,
            Json.object(
                "releaseId",
                approved.path("id"),
                "sourceRecordKey",
                tag + "TENANT_B",
                "payload",
                Json.object("id", tag + "TENANT_B", "name", "TENANTNUMBER")),
            UUID.randomUUID().toString());
    assertEquals(a.path("materialNo"), b.path("materialNo"));
    assertNotEquals(a.path("assignmentId"), b.path("assignmentId"));
    assertEquals(
        "NOT_FOUND",
        assertThrows(Problem.class, () -> assignments.ledger(c, b.path("assignmentId").asText()))
            .code);
  }

  @Test
  void typedLedgerSearchUsesPublishedSearchableFields() {
    var a = assignments.issue(c, body(tag + "SEARCH", "SEARCHME"), UUID.randomUUID().toString());
    var found =
        assignments.search(
            c,
            Json.object(
                "categoryCode",
                tag,
                "filters",
                Json.arr().add(Json.object("field", "name", "op", "eq", "value", "SEARCHME"))));
    assertEquals(1, found.path("total").asInt());
    assertEquals(a.path("assignmentId"), found.path("items").path(0).path("id"));
    assertEquals(
        "SEARCH_FIELD",
        assertThrows(
                Problem.class,
                () ->
                    assignments.search(
                        c,
                        Json.object(
                            "categoryCode",
                            tag,
                            "filters",
                            Json.arr()
                                .add(Json.object("field", "note", "op", "eq", "value", "x")))))
            .code);
    assertEquals(
        0,
        assignments
            .search(
                c,
                Json.object(
                    "categoryCode",
                    tag,
                    "filters",
                    Json.arr()
                        .add(Json.object("field", "name", "op", "eq", "value", "' OR 1=1 --"))))
            .path("total")
            .asInt());
  }

  @Test
  void longFullIdentityAndHashCollisionBucketStayDistinct() {
    var draft = createRelease(true);
    var idDef =
        Json.object(
            "fields",
            Json.arr().add(Json.object("field", "name")).add(Json.object("field", "note")));
    db.run(
        "update numbering_config set definition=?::jsonb where tenant_id=? and id=?",
        Json.str(idDef),
        c.tid(),
        SourceMetadata.id(draft.path("identityId").asText()));
    var code =
        Json.object(
            "segments",
            Json.arr()
                .add(Json.object("type", "CONST", "value", tag + "LONG"))
                .add(Json.object("type", "SEQUENCE", "width", 6)));
    db.run(
        "update numbering_config set definition=?::jsonb where tenant_id=? and id=?",
        Json.str(code),
        c.tid(),
        SourceMetadata.id(draft.path("codeRuleId").asText()));
    var def = (ObjectNode) draft.path("definition").deepCopy();
    ((ObjectNode) def.path("samples").path(0).path("payload")).put("note", "fixture");
    ((ObjectNode) def.path("samples").path(0)).put("expectedMaterialNo", tag + "LONG-{流水:6}");
    db.run(
        "update release_package set definition=?::jsonb where tenant_id=? and id=?",
        Json.str(def),
        c.tid(),
        SourceMetadata.id(draft.path("id").asText()));
    var published = publish(release.release(c, draft.path("id").asText()));
    String key = tag + "LONGKEY";
    var b =
        Json.object(
            "releaseId",
            published.path("id"),
            "sourceRecordKey",
            key,
            "payload",
            Json.object(
                "id", key, "category", tag, "name", "N".repeat(1000), "note", "T".repeat(1000)));
    var p = assignments.preview(c, b);
    String canonical = p.path("identity").path("canonical").asText(),
        hash = p.path("identity").path("hash").asText();
    assertTrue(canonical.length() > 2000);
    db.run(
        "insert into material_identity_registry values(?,?,?,?,?)",
        c.tid(),
        SourceMetadata.id(published.path("categoryId").asText()),
        hash,
        0,
        "different canonical in same hash bucket");
    var result = assignments.issue(c, b, UUID.randomUUID().toString());
    var ledger = assignments.ledger(c, result.path("assignmentId").asText());
    assertEquals(canonical, ledger.path("identityCanonical").asText());
    assertEquals(1, ledger.path("identitySlot").asInt());
    assertEquals(
        2,
        db.count(
            "select count(*) from material_identity_registry where tenant_id=? and category_id=?"
                + " and identity_hash=?",
            c.tid(),
            SourceMetadata.id(published.path("categoryId").asText()),
            hash));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            db.tx(
                () ->
                    db.run(
                        "update material_identity_registry set canonical='MUTATED' where"
                            + " tenant_id=? and category_id=? and identity_hash=?",
                        c.tid(),
                        SourceMetadata.id(published.path("categoryId").asText()),
                        hash)));
  }
}
