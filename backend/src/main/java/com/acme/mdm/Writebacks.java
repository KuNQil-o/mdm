package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** HTTP side effects happen only after the numbering/claim transaction has committed. */
@Service
public class Writebacks {
  final Db db;
  final Auth auth;
  final Assignments assignments;
  final SourceMetadata sources;
  final ReleasePackages releases;
  final DatasetEngine datasets;
  final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @Value("${mdm.retry-delays:60,300,1800,7200}")
  String retryDelays;

  @Value("${mdm.writeback-workers:true}")
  boolean workers;

  public Writebacks(
      Db d, Auth a, Assignments n, SourceMetadata s, ReleasePackages r, DatasetEngine e) {
    db = d;
    auth = a;
    assignments = n;
    sources = s;
    releases = r;
    datasets = e;
  }

  public ObjectNode task(Ctx c, String id) {
    var t =
        db.one(
            "select * from writeback_task where tenant_id=? and id=?",
            c.tid(),
            SourceMetadata.id(id));
    assignments.ledger(c, t.path("assignmentId").asText());
    return t;
  }

  public List<ObjectNode> list(Ctx c) {
    c.check("READ", null);
    return db
        .list(
            "select * from writeback_task where tenant_id=? order by created_at desc limit 1000",
            c.tid())
        .stream()
        .filter(
            t -> {
              try {
                task(c, t.path("id").asText());
                return true;
              } catch (Problem p) {
                return false;
              }
            })
        .toList();
  }

  record Response(int status, JsonNode body, String retryAfter) {}

  Response http(
      Ctx c, JsonNode adapter, String path, String key, String method, JsonNode body, String idem)
      throws Exception {
    String encoded =
        URLEncoder.encode(key, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    sources.connections.destination(adapter.path("baseUrl").asText());
    String url = adapter.path("baseUrl").asText() + path.replace("{key}", encoded);
    var r =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(adapter.path("timeoutSeconds").asInt(5)))
            .header("Accept", "application/json");
    if (adapter.has("tokenEnv"))
      r.header("Authorization", "Bearer " + sources.env(adapter, "tokenEnv", c.tenant()));
    if (idem != null) r.header("Idempotency-Key", idem);
    if (method.equals("GET")) r.GET();
    else
      r.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(Json.str(body)));
    var res = client.send(r.build(), HttpResponse.BodyHandlers.ofString());
    return new Response(
        res.statusCode(),
        res.body().isBlank() ? Json.obj() : Json.read(res.body()),
        res.headers().firstValue("Retry-After").orElse(""));
  }

  void attempt(Ctx c, JsonNode t, String phase, Response r, String error) {
    db.tx(
        () -> {
          db.run(
              "insert into"
                  + " writeback_attempt(id,tenant_id,task_id,attempt,phase,http_status,result,error_code,trace_id)"
                  + " values(?,?,?,?,?,?,?::jsonb,?,?)",
              UUID.randomUUID(),
              c.tid(),
              SourceMetadata.id(t.path("id").asText()),
              t.path("attempts").asInt(),
              phase,
              r == null ? null : r.status(),
              Json.str(r == null ? Json.obj() : safeResult(r.body())),
              error,
              c.trace());
          return null;
        });
  }

  JsonNode safeResult(JsonNode n) {
    return Json.object(
        "success",
        n.path("success"),
        "materialNo",
        n.path("materialNo"),
        "sourceRecordKey",
        n.path("sourceRecordKey"),
        "code",
        n.path("code"));
  }

  Response query(Ctx c, JsonNode t, JsonNode ledger) {
    try {
      var a = t.path("adapterSnapshot");
      var res =
          http(
              c,
              a,
              a.path("queryPath").asText(),
              ledger.path("sourceRecordKey").asText(),
              "GET",
              null,
              null);
      if (res.status() == 200
          && res.body().has("success")
          && !res.body().path("success").asBoolean())
        res = new Response(502, res.body(), res.retryAfter());
      attempt(c, t, "QUERY", res, res.status() == 200 ? null : "ERP_QUERY_UNAVAILABLE");
      return res;
    } catch (Exception e) {
      attempt(c, t, "QUERY", null, "ERP_QUERY_UNAVAILABLE");
      return null;
    }
  }

  boolean inspect(Ctx c, JsonNode t, JsonNode ledger, Response q) {
    if (q == null || q.status() != 200) return false;
    String actual = q.body().path("materialNo").asText("");
    if (actual.equals(ledger.path("materialNo").asText())) {
      finish(c, t, "SUCCEEDED", null, 0);
      return true;
    }
    if (!actual.isBlank()) {
      finish(c, t, "FAILED", "ERP_VALUE_CONFLICT", 0);
      return true;
    }
    return false;
  }

  ObjectNode claim(Ctx c, String id) {
    return db.tx(
        () -> {
          var t =
              db.one(
                  "select * from writeback_task where tenant_id=? and id=? for update",
                  c.tid(),
                  SourceMetadata.id(id));
          var ledger = assignments.ledger(c, t.path("assignmentId").asText());
          assignments.allowed(
              c, releases.release(c, ledger.path("releaseId").asText()), "WRITEBACK");
          String state = t.path("state").asText();
          if (Set.of("SUCCEEDED", "FAILED", "RESULT_UNKNOWN").contains(state)) return null;
          if (state.equals("SENDING")
              && Instant.parse(t.path("leaseUntil").asText()).isAfter(Instant.now())) return null;
          if (Instant.parse(t.path("nextAttemptAt").asText()).isAfter(Instant.now())) return null;
          db.run(
              "update writeback_task set state='SENDING',lease_until=now()+interval '30"
                  + " seconds',attempts=attempts+1 where tenant_id=? and id=?",
              c.tid(),
              SourceMetadata.id(id));
          db.run(
              "update material_assignment set writeback_status='SENDING',row_version=row_version+1"
                  + " where tenant_id=? and id=?",
              c.tid(),
              SourceMetadata.id(ledger.path("id").asText()));
          var claimed = task(c, id);
          claimed.put("previousState", state);
          return claimed;
        });
  }

  void finish(Ctx c, JsonNode t, String state, String error, int delay) {
    db.tx(
        () -> {
          int changed =
              db.run(
                  "update writeback_task set"
                      + " state=?,error_code=?,lease_until=null,next_attempt_at=now()+make_interval(secs=>?),confirmed_at=case"
                      + " when ?='SUCCEEDED' then now() else confirmed_at end where tenant_id=? and"
                      + " id=? and state='SENDING' and attempts=?",
                  state,
                  error,
                  delay,
                  state,
                  c.tid(),
                  SourceMetadata.id(t.path("id").asText()),
                  t.path("attempts").asInt());
          if (changed == 0) return null;
          db.run(
              "update material_assignment set writeback_status=?,erp_confirmed_at=case when"
                  + " ?='SUCCEEDED' then now() else erp_confirmed_at end,row_version=row_version+1"
                  + " where tenant_id=? and id=?",
              state,
              state,
              c.tid(),
              SourceMetadata.id(t.path("assignmentId").asText()));
          db.log(
              c,
              "WRITEBACK",
              t.path("id").asText(),
              t.path("attempts").asInt(),
              state,
              error == null ? "ERP当前值确认" : error,
              Json.object("assignmentId", t.path("assignmentId")),
              null);
          return null;
        });
  }

  int delay(int attempts) {
    var d = retryDelays.split(",");
    return Integer.parseInt(d[Math.min(Math.max(0, attempts - 1), d.length - 1)].trim());
  }

  void retry(Ctx c, JsonNode t, String error, int override) {
    int count = t.path("attempts").asInt();
    if (count > retryDelays.split(",").length) finish(c, t, "FAILED", "RETRY_EXHAUSTED", 0);
    else finish(c, t, "RETRY_WAIT", error, Math.max(delay(count), override));
  }

  public JsonNode process(Ctx c, String id) {
    var t = claim(c, id);
    if (t == null) return task(c, id);
    long started = System.nanoTime();
    try {
      var ledger = assignments.ledger(c, t.path("assignmentId").asText());
      var adapter = t.path("adapterSnapshot");
      var before = query(c, t, ledger);
      if (inspect(c, t, ledger, before)) return task(c, id);
      if (before == null || before.status() != 200) {
        if (before != null && before.status() == 404) {
          finish(c, t, "FAILED", "SOURCE_NOT_FOUND", 0);
        } else if (t.path("attempts").asInt() > 1
            && !adapter.path("supportsIdempotency").asBoolean()) {
          finish(c, t, "RESULT_UNKNOWN", "ERP_QUERY_UNAVAILABLE", 0);
        } else retry(c, t, "ERP_QUERY_UNAVAILABLE", 0);
        return task(c, id);
      }
      if (t.path("previousState").asText().equals("WAIT_CONFIRMATION")) {
        finish(
            c,
            t,
            t.path("attempts").asInt() > retryDelays.split(",").length
                ? "RESULT_UNKNOWN"
                : "WAIT_CONFIRMATION",
            "ERP_NOT_CONFIRMED",
            delay(t.path("attempts").asInt()));
        return task(c, id);
      }
      if (t.path("previousState").asText().equals("SENDING")
          && !adapter.path("supportsIdempotency").asBoolean()) {
        finish(c, t, "RESULT_UNKNOWN", "INTERRUPTED_NON_IDEMPOTENT_WRITE", 0);
        return task(c, id);
      }
      Problem.require(
          adapter.path("conditionalEmptyWrite").asBoolean(),
          422,
          "WRITEBACK_CONTRACT",
          "回写适配器必须防止覆盖并发ERP变更");
      Response res = null;
      try {
        res =
            http(
                c,
                adapter,
                adapter.path("path").asText(),
                ledger.path("sourceRecordKey").asText(),
                adapter.path("method").asText("PATCH"),
                Json.object(
                    "materialNo",
                    ledger.path("materialNo"),
                    "sourceRecordKey",
                    ledger.path("sourceRecordKey"),
                    "assignmentId",
                    ledger.path("id")),
                t.path("idempotencyKey").asText());
        attempt(c, t, "WRITE", res, null);
      } catch (Exception e) {
        attempt(c, t, "WRITE", null, "ERP_TRANSPORT_ERROR");
      }
      var after = query(c, t, ledger);
      if (inspect(c, t, ledger, after)) return task(c, id);
      if (res != null && res.status() == 409) {
        finish(c, t, "FAILED", "ERP_VALUE_CONFLICT", 0);
      } else if (res != null && res.status() == 200 && !res.body().path("success").asBoolean()) {
        finish(c, t, "FAILED", res.body().path("code").asText("ERP_BUSINESS_REJECTED"), 0);
      } else if (res != null && res.status() >= 400 && res.status() < 500 && res.status() != 429) {
        finish(c, t, "FAILED", "ERP_HTTP_" + res.status(), 0);
      } else if (res != null
          && (res.status() == 202
              || (res.status() == 200 && res.body().path("success").asBoolean()))) {
        finish(c, t, "WAIT_CONFIRMATION", "ERP_NOT_CONFIRMED", delay(t.path("attempts").asInt()));
      } else if (res == null
          && (after == null || after.status() != 200)
          && !adapter.path("supportsIdempotency").asBoolean()) {
        finish(c, t, "RESULT_UNKNOWN", "ERP_RESULT_UNKNOWN", 0);
      } else {
        int retryAfter = 0;
        if (res != null)
          try {
            retryAfter = Integer.parseInt(res.retryAfter());
          } catch (Exception ignored) {
          }
        retry(
            c,
            t,
            res != null && res.status() == 429 ? "ERP_RATE_LIMIT" : "ERP_TEMPORARY_FAILURE",
            retryAfter);
      }
      return task(c, id);
    } finally {
      try {
        var state = task(c, id).path("state").asText();
        releases.rules.stats.write(
            c.tenant(),
            System.nanoTime() - started,
            Set.of("FAILED", "RESULT_UNKNOWN").contains(state));
      } catch (Exception ignored) {
      }
    }
  }

  public JsonNode retryManual(Ctx c, String id, JsonNode b) {
    var t = task(c, id);
    var l = assignments.ledger(c, t.path("assignmentId").asText());
    assignments.allowed(c, releases.release(c, l.path("releaseId").asText()), "WRITEBACK");
    Problem.require(!b.path("reason").asText().isBlank(), 422, "REASON_REQUIRED", "人工处理必须记录原因");
    Problem.require(
        Set.of("FAILED", "RESULT_UNKNOWN").contains(t.path("state").asText()),
        409,
        "WRITEBACK_STATE",
        "仅失败或未知结果可人工处理");
    var q = query(c, t, l);
    if (q != null
        && q.status() == 200
        && q.body().path("materialNo").asText("").equals(l.path("materialNo").asText())) {
      db.tx(
          () -> {
            db.run(
                "update writeback_task set state='SENDING' where tenant_id=? and id=?",
                c.tid(),
                SourceMetadata.id(id));
            return null;
          });
      finish(c, t, "SUCCEEDED", null, 0);
      return task(c, id);
    }
    Problem.require(
        q != null && q.status() == 200 && q.body().path("materialNo").asText("").isBlank(),
        409,
        "RETRY_UNCONFIRMED",
        "必须先查询确认ERP尚未写入；不得盲目重发未知结果");
    return db.tx(
        () -> {
          db.run(
              "update writeback_task set"
                  + " state='PENDING',attempts=0,error_code=null,next_attempt_at=now(),lease_until=null"
                  + " where tenant_id=? and id=?",
              c.tid(),
              SourceMetadata.id(id));
          db.log(
              c,
              "WRITEBACK",
              id,
              t.path("attempts").asInt(),
              "MANUAL_RETRY",
              b.path("reason").asText(),
              Json.object("confirmedEmpty", true),
              l.path("categoryId").asText());
          return task(c, id);
        });
  }

  public JsonNode rebuild(Ctx c, String id, JsonNode b, String idem) {
    var old = task(c, id);
    var ledger = assignments.ledger(c, old.path("assignmentId").asText());
    var replacement = releases.release(c, b.path("releaseId").asText());
    assignments.allowed(c, replacement, "WRITEBACK");
    Problem.require(!b.path("reason").asText().isBlank(), 422, "REASON_REQUIRED", "重建任务须填写原因");
    Problem.require(
        Set.of("FAILED", "RESULT_UNKNOWN").contains(old.path("state").asText()),
        409,
        "WRITEBACK_STATE",
        "仅失败或未知任务可重建");
    Problem.require(
        replacement.path("status").asText().equals("PUBLISHED")
            && replacement.path("sourceSystemId").equals(ledger.path("sourceSystemId"))
            && replacement.path("categoryId").equals(ledger.path("categoryId"))
            && replacement
                .path("dependencySnapshot")
                .path("datasetCode")
                .equals(ledger.path("datasetCode")),
        422,
        "WRITEBACK_REBUILD_SCOPE",
        "新适配器须是相同来源、类别及Dataset的已发布版本");
    var q = query(c, old, ledger);
    Problem.require(
        q != null && q.status() == 200 && q.body().path("materialNo").asText("").isBlank(),
        409,
        "RETRY_UNCONFIRMED",
        "须确认ERP当前为空方可用新适配器重建；未知结果不得盲目重发");
    return db.idem(
        c,
        "WRITEBACK_REBUILD:" + id,
        idem,
        b,
        () -> {
          db.one(
              "select id from material_assignment where tenant_id=? and id=? for update",
              c.tid(),
              SourceMetadata.id(ledger.path("id").asText()));
          var latest =
              db.one(
                  "select * from writeback_task where tenant_id=? and assignment_id=? order by"
                      + " revision desc limit 1",
                  c.tid(),
                  SourceMetadata.id(ledger.path("id").asText()));
          Problem.require(
              latest.path("id").asText().equals(id)
                  && Set.of("FAILED", "RESULT_UNKNOWN").contains(latest.path("state").asText()),
              409,
              "WRITEBACK_REBUILD_CONFLICT",
              "已有新的处理任务或状态发生变化");
          UUID key = UUID.randomUUID();
          db.run(
              "insert into"
                  + " writeback_task(id,tenant_id,assignment_id,source_system_id,actor,adapter_snapshot,idempotency_key,revision,parent_id)"
                  + " values(?,?,?,?,?,?::jsonb,?,?,?)",
              key,
              c.tid(),
              SourceMetadata.id(ledger.path("id").asText()),
              SourceMetadata.id(ledger.path("sourceSystemId").asText()),
              c.uid(),
              Json.str(replacement.path("definition").path("writeback")),
              old.path("idempotencyKey").asText(),
              old.path("revision").asInt() + 1,
              SourceMetadata.id(id));
          db.run(
              "update material_assignment set writeback_status='PENDING',row_version=row_version+1"
                  + " where tenant_id=? and id=?",
              c.tid(),
              SourceMetadata.id(ledger.path("id").asText()));
          db.log(
              c,
              "WRITEBACK",
              key.toString(),
              1,
              "REBUILD",
              b.path("reason").asText(),
              Json.object(
                  "parentId",
                  id,
                  "releaseId",
                  replacement.path("id"),
                  "stableMaterialNo",
                  ledger.path("materialNo")),
              ledger.path("categoryId").asText());
          return task(c, key.toString());
        });
  }

  @Scheduled(fixedDelayString = "${mdm.worker-delay:1000}")
  public void work() {
    if (!workers) return;
    for (var t :
        db.list(
            "select w.id,w.tenant_id,w.actor from writeback_task w join tenant t on"
                + " t.id=w.tenant_id join source_system s on s.tenant_id=w.tenant_id and"
                + " s.id=w.source_system_id where t.status='ACTIVE' and s.status='ACTIVE' and"
                + " w.state in ('PENDING','RETRY_WAIT','WAIT_CONFIRMATION','SENDING') and"
                + " w.next_attempt_at<=now() and (w.lease_until is null or w.lease_until<=now())"
                + " order by w.created_at limit 20")) {
      try {
        process(
            auth.worker(t.path("tenantId").asText(), t.path("actor").asText()),
            t.path("id").asText());
      } catch (Problem ignored) {
        /* Current authorization or tenant state may have changed. */
      }
    }
  }

  public JsonNode reconcile(Ctx c, String rid) {
    var r = releases.release(c, rid);
    assignments.allowed(c, r, "RECONCILE");
    ArrayNode diff = Json.arr();
    String code = r.path("dependencySnapshot").path("datasetCode").asText();
    for (var l :
        db.list(
            "select * from material_assignment where tenant_id=? and dataset_code=?",
            c.tid(),
            code)) {
      var tasks =
          db.list(
              "select * from writeback_task where tenant_id=? and assignment_id=? order by revision"
                  + " desc limit 1",
              c.tid(),
              SourceMetadata.id(l.path("id").asText()));
      if (tasks.isEmpty()) {
        diff.add(Json.object("type", "MDM_LEDGER_NO_WRITEBACK_TASK", "assignmentId", l.path("id")));
        continue;
      }
      var q = query(c, tasks.getFirst(), l);
      String type =
          q == null || q.status() != 200
              ? "ERP_RECORD_MISSING_OR_UNAVAILABLE"
              : q.body().path("materialNo").asText("").isBlank()
                  ? "MDM_ISSUED_ERP_EMPTY"
                  : !q.body().path("materialNo").asText().equals(l.path("materialNo").asText())
                      ? "ERP_NUMBER_MISMATCH"
                      : !l.path("writebackStatus").asText().equals("SUCCEEDED")
                          ? "ERP_WRITTEN_MDM_UNCONFIRMED"
                          : null;
      if (type != null)
        diff.add(
            Json.object(
                "type",
                type,
                "assignmentId",
                l.path("id"),
                "expected",
                l.path("materialNo"),
                "actual",
                q == null ? null : q.body().path("materialNo")));
    }
    var ds = releases.config(c, r.path("datasetId").asText(), "DATASET");
    for (var row : datasets.scan(c, ds, Json.obj(), true)) {
      String key = row.path("key").asText();
      var read = datasets.preview(c, ds, key, null);
      String no = read.path("existingMaterialNo").asText(read.path("existingNo").asText(""));
      if (!no.isBlank()
          && db.count(
                  "select count(*) from material_assignment where tenant_id=? and dataset_code=?"
                      + " and source_record_key=?",
                  c.tid(),
                  code,
                  key)
              == 0)
        diff.add(
            Json.object(
                "type", "ERP_NUMBER_WITHOUT_MDM_LEDGER", "sourceRecordKey", key, "actual", no));
    }
    UUID id = UUID.randomUUID();
    db.tx(
        () -> {
          db.run(
              "insert into"
                  + " numbering_reconciliation(id,tenant_id,source_system_id,release_id,actor,status,results)"
                  + " values(?,?,?,?,?,'COMPLETED',?::jsonb)",
              id,
              c.tid(),
              SourceMetadata.id(r.path("sourceSystemId").asText()),
              SourceMetadata.id(rid),
              c.uid(),
              Json.str(diff));
          db.log(
              c,
              "RECONCILIATION",
              id.toString(),
              1,
              "COMPLETE",
              "查询差异；不改写ERP",
              Json.object("differenceCount", diff.size()),
              r.path("categoryId").asText());
          return null;
        });
    return Json.object("id", id, "differences", diff);
  }

  @Scheduled(cron = "${mdm.reconcile-cron:0 0 2 * * *}", zone = "UTC")
  public void daily() {
    if (!workers) return;
    for (var rt :
        db.list(
            "select d.* from dataset_runtime d join tenant t on t.id=d.tenant_id join"
                + " release_package r on r.id=d.release_id and r.tenant_id=d.tenant_id join"
                + " source_system s on s.id=r.source_system_id and s.tenant_id=r.tenant_id where"
                + " t.status='ACTIVE' and s.status='ACTIVE' and r.status='PUBLISHED'"))
      try {
        reconcile(
            auth.worker(rt.path("tenantId").asText(), rt.path("actor").asText()),
            rt.path("releaseId").asText());
      } catch (Problem ignored) {
      }
  }
}
