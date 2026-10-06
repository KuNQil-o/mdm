package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class Exports {
  final Db db;
  final Files files;
  final Search search;
  final Auth auth;
  final Models models;

  public Exports(Db d, Files f, Search s, Auth a, Models m) {
    db = d;
    files = f;
    search = s;
    auth = a;
    models = m;
  }

  public JsonNode create(Ctx c, JsonNode b) {
    String code = b.path("query").path("categoryCode").asText("");
    c.check("EXPORT", code.isBlank() ? null : code);
    ObjectNode cfg = (ObjectNode) b.deepCopy();
    ObjectNode query = cfg.path("query").isObject() ? (ObjectNode) cfg.path("query") : Json.obj();
    query.put("cutoff", java.time.Instant.now().toString());
    query.set("page", Json.object("size", 200));
    cfg.set("query", query);
    if (!search.find(c, query).path("hasMore").asBoolean()) return files.export(c, cfg);
    UUID id = UUID.randomUUID();
    db.run(
        "insert into export_job(id,tenant_id,category_code,actor,config) values(?,?,?,?,?::jsonb)",
        id,
        c.tid(),
        code,
        c.uid(),
        Json.str(cfg));
    db.log(
        c,
        "EXPORT",
        id.toString(),
        1,
        "QUEUED",
        "大于200行转后台",
        cfg,
        code.isBlank() ? null : Json.text(models.category(c, code), "id"));
    return get(c, id.toString());
  }

  public ObjectNode get(Ctx c, String id) {
    var j = db.one("select * from export_job where tenant_id=? and id=?", c.tid(), models.uuid(id));
    String code = j.path("categoryCode").asText("");
    c.check("EXPORT", code.isBlank() ? null : code);
    return j;
  }

  public JsonNode list(Ctx c) {
    c.check("EXPORT", null);
    return Json.M.valueToTree(
        db
            .list("select * from export_job where tenant_id=? order by created_at desc", c.tid())
            .stream()
            .filter(
                j ->
                    j.path("categoryCode").asText().isBlank()
                        || c.allows(j.path("categoryCode").asText()))
            .toList());
  }

  @Scheduled(fixedDelayString = "${mdm.worker-delay:1000}")
  public void work() {
    try {
      db.tx(
          () -> {
            var j =
                db.maybe(
                    "select j.* from export_job j join tenant t on t.id=j.tenant_id where"
                        + " t.status='ACTIVE' and j.status='PENDING' order by j.created_at for"
                        + " update of j skip locked limit 1");
            if (j == null) return null;
            try {
              db.nested(
                  () -> {
                    var c = auth.worker(Json.text(j, "tenantId"), Json.text(j, "actor"));
                    db.run(
                        "update export_job set status='PROCESSING' where id=?",
                        models.uuid(Json.text(j, "id")));
                    var f = files.export(c, j.path("config"));
                    db.run(
                        "update export_job set"
                            + " status='COMPLETED',file_id=?,row_version=row_version+1,completed_at=now()"
                            + " where id=?",
                        models.uuid(Json.text(f, "id")),
                        models.uuid(Json.text(j, "id")));
                    db.log(c, "EXPORT", Json.text(j, "id"), 2, "COMPLETED", "", f, null);
                    return null;
                  });
            } catch (Exception e) {
              db.run(
                  "update export_job set status='FAILED',error=?,row_version=row_version+1 where"
                      + " id=?",
                  e.getMessage(),
                  models.uuid(Json.text(j, "id")));
            }
            return null;
          });
    } catch (Exception e) {
      org.slf4j.LoggerFactory.getLogger(Exports.class)
          .warn("Export worker: {}", e.getClass().getSimpleName());
    }
  }
}
