package com.acme.mdm;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/mdm_test}",
      "mdm.worker-delay=60000",
      "mdm.scan-workers=false",
      "mdm.writeback-workers=false",
      "mdm.retry-delays=1,1,1,1"
    })
class WritebackTest extends NumberingTest {
  @Autowired Writebacks writebacks;
  @Autowired Scans scans;

  JsonNode call(String path, String method, JsonNode body) throws Exception {
    return Json.read(
        HttpClient.newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create("http://localhost:9092" + path))
                    .header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(Json.str(body)))
                    .build(),
                HttpResponse.BodyHandlers.ofString())
            .body());
  }

  String issued(String suffix) throws Exception {
    String key = tag + suffix;
    call(
        "/documents",
        "POST",
        Json.object(
            "id", key, "category", tag, "data", Json.object("name", suffix, "note", "fixture")));
    var a =
        assignments.issue(
            c,
            Json.object("releaseId", rel.path("id"), "sourceRecordKey", key),
            UUID.randomUUID().toString());
    return db.one(
            "select id from writeback_task where tenant_id=? and assignment_id=?",
            c.tid(),
            SourceMetadata.id(a.path("assignmentId").asText()))
        .path("id")
        .asText();
  }

  void scenario(String mode, int count) throws Exception {
    call(
        "/scenarios/flat_material",
        "POST",
        Json.object("mode", mode, "remaining", count, "category", tag));
  }

  void due(String task) {
    db.run(
        "update writeback_task set next_attempt_at=now() where tenant_id=? and id=?",
        c.tid(),
        SourceMetadata.id(task));
  }

  @Test
  void normalWriteAndReceiptReplay() throws Exception {
    scenario("SUCCESS", 0);
    String id = issued("WRITE");
    assertEquals("SUCCEEDED", writebacks.process(c, id).path("state").asText());
    assertEquals("SUCCEEDED", writebacks.process(c, id).path("state").asText());
    assertEquals(
        1,
        db.count(
            "select count(*) from writeback_attempt where tenant_id=? and task_id=? and"
                + " phase='WRITE'",
            c.tid(),
            SourceMetadata.id(id)));
  }

  @Test
  void lostResponseConfirmedByQuery() throws Exception {
    scenario("LOST_RESPONSE", 1);
    String id = issued("LOST");
    assertEquals("SUCCEEDED", writebacks.process(c, id).path("state").asText());
    assertEquals(
        1,
        db.count(
            "select count(*) from writeback_attempt where tenant_id=? and task_id=? and"
                + " error_code='ERP_TRANSPORT_ERROR'",
            c.tid(),
            SourceMetadata.id(id)));
  }

  @Test
  void businessFailureIsNotSuccess() throws Exception {
    scenario("BUSINESS_FAIL", 1);
    String id = issued("BUSINESSFAIL");
    var result = writebacks.process(c, id);
    assertEquals("FAILED", result.path("state").asText());
    assertEquals("BUSINESS_REJECTED", result.path("errorCode").asText());
    assertEquals(
        "BUSINESS_REJECTED",
        db.one(
                "select error_code from writeback_task where tenant_id=? and id=?",
                c.tid(),
                SourceMetadata.id(id))
            .path("errorCode")
            .asText());
  }

  @Test
  void retryPreservesAssignmentAndNumber() throws Exception {
    scenario("SERVER_ERROR", 1);
    String id = issued("RETRY");
    var before = writebacks.task(c, id);
    assertEquals("RETRY_WAIT", writebacks.process(c, id).path("state").asText());
    due(id);
    assertEquals("SUCCEEDED", writebacks.process(c, id).path("state").asText());
    var after = writebacks.task(c, id);
    assertEquals(before.path("assignmentId"), after.path("assignmentId"));
    assertEquals(before.path("idempotencyKey"), after.path("idempotencyKey"));
    assertEquals(2, after.path("attempts").asInt());
  }

  @Test
  void currentERPConflictNeverOverwritten() throws Exception {
    scenario("SUCCESS", 0);
    String id = issued("CONFLICT");
    call(
        "/records/flat_material/" + tag + "CONFLICT",
        "PATCH",
        Json.object("material_no", "ERP-OTHER"));
    var result = writebacks.process(c, id);
    assertEquals("FAILED", result.path("state").asText());
    assertEquals("ERP_VALUE_CONFLICT", result.path("errorCode").asText());
    assertEquals(
        0,
        db.count(
            "select count(*) from writeback_attempt where tenant_id=? and task_id=? and"
                + " phase='WRITE'",
            c.tid(),
            SourceMetadata.id(id)));
  }

  @Test
  void acceptedIsConfirmedByERPValue() throws Exception {
    scenario("ACCEPTED", 1);
    String id = issued("ACCEPTED");
    assertEquals("SUCCEEDED", writebacks.process(c, id).path("state").asText());
  }

  @Test
  void unknownWithoutIdempotencyCannotBlindRetry() throws Exception {
    var original = rel;
    var draft = createRelease(true);
    db.run(
        "update release_package set"
            + " definition=jsonb_set(definition,'{writeback,supportsIdempotency}','false') where"
            + " tenant_id=? and id=?",
        c.tid(),
        SourceMetadata.id(draft.path("id").asText()));
    rel = publish((ObjectNode) release.release(c, draft.path("id").asText()));
    try {
      scenario("UNKNOWN", 1);
      String id = issued("UNKNOWN");
      assertEquals("RETRY_WAIT", writebacks.process(c, id).path("state").asText());
      scenario("DELAY_QUERY", -1);
      due(id);
      assertEquals("RESULT_UNKNOWN", writebacks.process(c, id).path("state").asText());
      assertEquals(
          "RETRY_UNCONFIRMED",
          assertThrows(
                  Problem.class,
                  () -> writebacks.retryManual(c, id, Json.object("reason", "verify")))
              .code);
      scenario("SUCCESS", 0);
      assertEquals(
          "PENDING",
          writebacks
              .retryManual(c, id, Json.object("reason", "ERP confirms empty"))
              .path("state")
              .asText());
      assertEquals("SUCCEEDED", writebacks.process(c, id).path("state").asText());
    } finally {
      rel = original;
      scenario("SUCCESS", 0);
    }
  }

  @Test
  void scanPersistsRowErrorsAndCursor() throws Exception {
    scenario("SUCCESS", 0);
    call(
        "/documents",
        "POST",
        Json.object(
            "id",
            tag + "BAD",
            "category",
            tag,
            "data",
            Json.object("name", "", "note", "invalid")));
    var j = db.tx(() -> scans.request(c, tag, Json.obj()));
    scans.run(c, j.path("id").asText());
    for (var t :
        db.list(
            "select * from processing_task where tenant_id=? and scan_job_id=?",
            c.tid(),
            SourceMetadata.id(j.path("id").asText()))) scans.process(c, t.path("id").asText());
    var finished = scans.job(c, j.path("id").asText());
    assertEquals("PARTIAL_FAILED", finished.path("status").asText());
    assertTrue(finished.path("failedCount").asInt() > 0);
    assertTrue(finished.hasNonNull("cursorAfter"));
    assertTrue(
        scans.errors(c).stream()
            .anyMatch(
                x ->
                    x.path("sourceRecordKey").asText().equals(tag + "BAD")
                        && x.path("errorCode").asText().equals("VALIDATION_ERROR")
                        && x.path("errorDetails").toString().contains("/attributes/name")));
  }

  @Test
  void rebuildUsesImmutableNewRevisionAndOriginalNumber() throws Exception {
    scenario("BUSINESS_FAIL", 1);
    String id = issued("REBUILD");
    var original = writebacks.task(c, id);
    assertEquals("FAILED", writebacks.process(c, id).path("state").asText());
    scenario("SUCCESS", 0);
    var replacement = publish(createRelease(true));
    var rebuilt =
        writebacks.rebuild(
            c,
            id,
            Json.object(
                "releaseId", replacement.path("id"), "reason", "published replacement adapter"),
            UUID.randomUUID().toString());
    assertEquals(1, rebuilt.path("revision").asInt());
    assertEquals(id, rebuilt.path("parentId").asText());
    assertEquals(original.path("idempotencyKey"), rebuilt.path("idempotencyKey"));
    assertEquals(original.path("adapterSnapshot"), writebacks.task(c, id).path("adapterSnapshot"));
    assertEquals(
        "SUCCEEDED", writebacks.process(c, rebuilt.path("id").asText()).path("state").asText());
    assertEquals(original.path("assignmentId"), rebuilt.path("assignmentId"));
  }

  @Test
  void staleScanCannotRegressCursorOrInvalidateRuntimeConfigurationVersion() throws Exception {
    var initial =
        db.one("select * from dataset_runtime where tenant_id=? and dataset_code=?", c.tid(), tag);
    var j = db.tx(() -> scans.request(c, tag, Json.obj()));
    db.run(
        "update dataset_runtime set cursor='{\"value\":200}' where tenant_id=? and dataset_code=?",
        c.tid(),
        tag);
    scans.run(c, j.path("id").asText());
    var after =
        db.one("select * from dataset_runtime where tenant_id=? and dataset_code=?", c.tid(), tag);
    assertEquals(200, after.path("cursor").path("value").asInt());
    assertEquals(initial.path("rowVersion"), after.path("rowVersion"));
  }
}
