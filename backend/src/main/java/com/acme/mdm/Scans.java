package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class Scans {
  final Db db;
  final Auth auth;
  final ReleasePackages releases;
  final Assignments assignments;
  final DatasetEngine datasets;

  @Value("${mdm.scan-workers:true}")
  boolean enabled;

  public Scans(Db d, Auth a, ReleasePackages r, Assignments n, DatasetEngine e) {
    db = d;
    auth = a;
    releases = r;
    assignments = n;
    datasets = e;
  }

  public JsonNode request(Ctx c, String dataset, JsonNode b) {
    var rel = releases.current(c, dataset);
    assignments.allowed(c, rel, "SCAN");
    UUID id = UUID.randomUUID();
    var runtime =
        db.one(
            "select * from dataset_runtime where tenant_id=? and dataset_code=?", c.tid(), dataset);
    db.run(
        "insert into scan_job(id,tenant_id,dataset_id,release_id,actor,trigger,cursor_before)"
            + " values(?,?,?,?,?,?,?::jsonb)",
        id,
        c.tid(),
        SourceMetadata.id(rel.path("datasetId").asText()),
        SourceMetadata.id(rel.path("id").asText()),
        c.uid(),
        b.path("trigger").asText("MANUAL_RESCAN"),
        Json.str(runtime.path("cursor")));
    db.log(
        c, "SCAN_JOB", id.toString(), 1, "REQUEST", "来源候选扫描", b, rel.path("categoryId").asText());
    return job(c, id.toString());
  }

  public ObjectNode job(Ctx c, String id) {
    var j =
        db.one("select * from scan_job where tenant_id=? and id=?", c.tid(), SourceMetadata.id(id));
    releases.release(c, j.path("releaseId").asText());
    return j;
  }

  public List<ObjectNode> jobs(Ctx c) {
    c.check("READ", null);
    return db
        .list(
            "select * from scan_job where tenant_id=? order by created_at desc limit 1000", c.tid())
        .stream()
        .filter(
            j -> {
              try {
                job(c, j.path("id").asText());
                return true;
              } catch (Problem p) {
                return false;
              }
            })
        .toList();
  }

  public List<ObjectNode> errors(Ctx c) {
    c.check("READ", null);
    return db
        .list(
            "select * from processing_task where tenant_id=? order by created_at desc limit 1000",
            c.tid())
        .stream()
        .filter(
            t -> {
              try {
                releases.release(c, t.path("releaseId").asText());
                return true;
              } catch (Problem p) {
                return false;
              }
            })
        .toList();
  }

  public JsonNode run(Ctx c, String id) {
    var claimed =
        db.tx(
            () -> {
              var j =
                  db.one(
                      "select * from scan_job where tenant_id=? and id=? for update",
                      c.tid(),
                      SourceMetadata.id(id));
              assignments.allowed(c, releases.release(c, j.path("releaseId").asText()), "SCAN");
              if (!j.path("status").asText().equals("PENDING")) return false;
              db.run(
                  "update scan_job set status='SCANNING',started_at=now() where tenant_id=? and"
                      + " id=?",
                  c.tid(),
                  SourceMetadata.id(id));
              return true;
            });
    if (!claimed) return job(c, id);
    var j = job(c, id);
    var r = releases.release(c, j.path("releaseId").asText());
    var ds = releases.config(c, r.path("datasetId").asText(), "DATASET");
    ((ObjectNode) ds).set("definition", r.path("dependencySnapshot").path("dataset"));
    try {
      JsonNode rows =
          datasets.scan(
              c, ds, j.path("cursorBefore"), j.path("trigger").asText().equals("MANUAL_RESCAN"));
      ObjectNode cursor = (ObjectNode) j.path("cursorBefore").deepCopy();
      Set<String> seen = new HashSet<>();
      for (var row : rows) {
        String key = row.path("key").asText();
        Problem.require(seen.add(key), 409, "GRAIN_CONFLICT", "扫描源键重复，禁止任意选行");
        if (!row.path("version").isNull()
            && !row.path("version").isMissingNode()
            && (cursor.path("value").isMissingNode()
                || DatasetEngine.compare(row.path("version"), cursor.path("value")) > 0))
          cursor.set("value", row.path("version"));
      }
      db.tx(
          () -> {
            for (var row : rows)
              db.run(
                  "insert into"
                      + " processing_task(id,tenant_id,release_id,scan_job_id,source_record_key,source_version,actor,trace_id)"
                      + " values(?,?,?,?,?,?,?,?) on"
                      + " conflict(tenant_id,scan_job_id,source_record_key) do nothing",
                  UUID.randomUUID(),
                  c.tid(),
                  SourceMetadata.id(r.path("id").asText()),
                  SourceMetadata.id(id),
                  row.path("key").asText(),
                  row.path("version").asText(null),
                  c.uid(),
                  c.trace());
            db.run(
                "update scan_job set"
                    + " status='PROCESSING',scanned_count=?,discovered_count=?,cursor_after=?::jsonb"
                    + " where tenant_id=? and id=?",
                rows.size(),
                rows.size(),
                Json.str(cursor),
                c.tid(),
                SourceMetadata.id(id));
            var currentRuntime =
                db.one(
                    "select * from dataset_runtime where tenant_id=? and dataset_code=? for update",
                    c.tid(),
                    ds.path("code").asText());
            if (currentRuntime.path("cursor").has("value")
                && (!cursor.has("value")
                    || DatasetEngine.compare(
                            currentRuntime.path("cursor").path("value"), cursor.path("value"))
                        > 0)) cursor.set("value", currentRuntime.path("cursor").path("value"));
            db.run(
                "update dataset_runtime set cursor=?::jsonb where"
                    + " tenant_id=? and dataset_code=?",
                Json.str(cursor),
                c.tid(),
                ds.path("code").asText());
            return null;
          });
      if (rows.isEmpty()) completeJob(c, id);
    } catch (Problem p) {
      db.tx(
          () -> {
            db.run(
                "update scan_job set status='FAILED',results=?::jsonb,finished_at=now() where"
                    + " tenant_id=? and id=?",
                Json.str(
                    Json.arr()
                        .add(
                            Json.object(
                                "code", p.code, "message", p.getMessage(), "errors", p.errors))),
                c.tid(),
                SourceMetadata.id(id));
            return null;
          });
    }
    return job(c, id);
  }

  void completeJob(Ctx c, String id) {
    db.run(
        "update scan_job set status=case when failed_count>0 then 'PARTIAL_FAILED' else 'COMPLETED'"
            + " end,finished_at=now() where tenant_id=? and id=? and not exists(select 1 from"
            + " processing_task where tenant_id=? and scan_job_id=? and status in"
            + " ('DISCOVERED','PROCESSING','RETRY_WAIT'))",
        c.tid(),
        SourceMetadata.id(id),
        c.tid(),
        SourceMetadata.id(id));
  }

  public JsonNode process(Ctx c, String id) {
    var t =
        db.tx(
            () -> {
              var task =
                  db.one(
                      "select * from processing_task where tenant_id=? and id=? for update",
                      c.tid(),
                      SourceMetadata.id(id));
              var r = releases.release(c, task.path("releaseId").asText());
              assignments.allowed(c, r, "ASSIGN");
              if (!Set.of("DISCOVERED", "RETRY_WAIT", "PROCESSING")
                  .contains(task.path("status").asText())) return null;
              if (task.hasNonNull("leaseUntil")
                  && Instant.parse(task.path("leaseUntil").asText()).isAfter(Instant.now()))
                return null;
              db.run(
                  "update processing_task set status='PROCESSING',lease_until=now()+interval '30"
                      + " seconds',attempts=attempts+1,updated_at=now() where tenant_id=? and id=?",
                  c.tid(),
                  SourceMetadata.id(id));
              return db.one(
                  "select * from processing_task where tenant_id=? and id=?",
                  c.tid(),
                  SourceMetadata.id(id));
            });
    if (t == null)
      return db.one(
          "select * from processing_task where tenant_id=? and id=?",
          c.tid(),
          SourceMetadata.id(id));
    String state = "COMPLETED", error = null;
    JsonNode details = Json.arr(), result = null;
    try {
      result =
          assignments.issue(
              c,
              Json.object(
                  "releaseId", t.path("releaseId"), "sourceRecordKey", t.path("sourceRecordKey")),
              "processing-" + id);
    } catch (Problem p) {
      error = p.code;
      details = p.errors;
      state = p.status >= 500 ? "RETRY_WAIT" : "ERROR";
    }
    final String status = state, code = error;
    final JsonNode errors = details, res = result;
    db.tx(
        () -> {
          int count =
              db.run(
                  "update processing_task set"
                      + " status=?,error_code=?,error_details=?::jsonb,assignment_id=?,lease_until=null,next_attempt_at=now()+interval"
                      + " '60 seconds',updated_at=now() where tenant_id=? and id=? and"
                      + " status='PROCESSING' and attempts=?",
                  status,
                  code,
                  Json.str(errors),
                  res != null && res.hasNonNull("assignmentId")
                      ? SourceMetadata.id(res.path("assignmentId").asText())
                      : null,
                  c.tid(),
                  SourceMetadata.id(id),
                  t.path("attempts").asInt());
          if (count == 1 && t.hasNonNull("scanJobId")) {
            if (status.equals("ERROR"))
              db.run(
                  "update scan_job set failed_count=failed_count+1 where tenant_id=? and id=?",
                  c.tid(),
                  SourceMetadata.id(t.path("scanJobId").asText()));
            if (status.equals("COMPLETED") && res != null && res.path("replayed").asBoolean())
              db.run(
                  "update scan_job set skipped_count=skipped_count+1 where tenant_id=? and id=?",
                  c.tid(),
                  SourceMetadata.id(t.path("scanJobId").asText()));
            completeJob(c, t.path("scanJobId").asText());
          }
          return null;
        });
    return db.one(
        "select * from processing_task where tenant_id=? and id=?", c.tid(), SourceMetadata.id(id));
  }

  public JsonNode retry(Ctx c, String id, JsonNode b) {
    var t =
        db.one(
            "select * from processing_task where tenant_id=? and id=?",
            c.tid(),
            SourceMetadata.id(id));
    assignments.allowed(c, releases.release(c, t.path("releaseId").asText()), "ASSIGN");
    Problem.require(
        t.path("status").asText().equals("ERROR") && !b.path("reason").asText().isBlank(),
        422,
        "TASK_RETRY",
        "仅异常任务可带原因重试，请先在ERP修正");
    db.run(
        "update processing_task set status='DISCOVERED',lease_until=null,next_attempt_at=now()"
            + " where tenant_id=? and id=?",
        c.tid(),
        SourceMetadata.id(id));
    db.log(
        c,
        "PROCESSING_TASK",
        id,
        t.path("attempts").asInt(),
        "RETRY",
        b.path("reason").asText(),
        Json.obj(),
        null);
    return db.one(
        "select * from processing_task where tenant_id=? and id=?", c.tid(), SourceMetadata.id(id));
  }

  @Scheduled(fixedDelayString = "${mdm.worker-delay:1000}")
  public void work() {
    if (!enabled) return;
    for (var rt :
        db.list(
            "select d.* from dataset_runtime d join tenant t on t.id=d.tenant_id join"
                + " release_package r on r.tenant_id=d.tenant_id and r.id=d.release_id join"
                + " source_system s on s.tenant_id=r.tenant_id and s.id=r.source_system_id where"
                + " d.enabled and t.status='ACTIVE' and s.status='ACTIVE' and d.next_scan_at<=now()"
                + " limit 10"))
      try {
        db.tx(
            () -> {
              if (db.run(
                      "update dataset_runtime set"
                          + " next_scan_at=now()+make_interval(secs=>interval_seconds) where"
                          + " tenant_id=? and dataset_code=? and next_scan_at<=now()",
                      SourceMetadata.id(rt.path("tenantId").asText()),
                      rt.path("datasetCode").asText())
                  == 1)
                request(
                    auth.worker(rt.path("tenantId").asText(), rt.path("actor").asText()),
                    rt.path("datasetCode").asText(),
                    Json.object("trigger", "SCHEDULED"));
              return null;
            });
      } catch (Problem ignored) {
      }
    // A scan interrupted before task persistence is safely replayed; issued assignments remain
    // idempotent.
    db.run(
        "update scan_job set status='PENDING' where status='SCANNING' and started_at<now()-interval"
            + " '2 minutes'");
    for (var j :
        db.list(
            "select j.* from scan_job j join tenant t on t.id=j.tenant_id where j.status='PENDING'"
                + " and t.status='ACTIVE' limit 10"))
      try {
        run(
            auth.worker(j.path("tenantId").asText(), j.path("actor").asText()),
            j.path("id").asText());
      } catch (Problem ignored) {
      }
    for (var t :
        db.list(
            "select p.* from processing_task p join tenant t on t.id=p.tenant_id where"
                + " t.status='ACTIVE' and p.status in ('DISCOVERED','RETRY_WAIT','PROCESSING') and"
                + " p.next_attempt_at<=now() and (p.lease_until is null or p.lease_until<=now())"
                + " order by p.created_at limit 20"))
      try {
        process(
            auth.worker(t.path("tenantId").asText(), t.path("actor").asText()),
            t.path("id").asText());
      } catch (Problem ignored) {
      }
  }
}
