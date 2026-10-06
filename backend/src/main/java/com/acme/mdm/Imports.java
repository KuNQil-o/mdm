package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class Imports {
  final Db db;
  final Models models;
  final Materials materials;
  final Files files;
  final Rules rules;
  final Auth auth;

  public Imports(Db d, Models m, Materials a, Files f, Rules r, Auth u) {
    db = d;
    models = m;
    materials = a;
    files = f;
    rules = r;
    auth = u;
  }

  public JsonNode upload(Ctx c, String code, String name, byte[] bytes) {
    c.check("IMPORT", code);
    var cat = models.category(c, code);
    Problem.require(bytes.length <= 20 * 1024 * 1024, 422, "FILE_SIZE", "文件最多20MB");
    var sheets = files.sheets(bytes, name);
    UUID fid = UUID.randomUUID();
    String hash = Json.hash(Base64.getEncoder().encodeToString(bytes));
    db.run(
        "insert into file_record(id,tenant_id,category_id,name,kind,content,checksum)"
            + " values(?,?,?,?,'IMPORT',?,?)",
        fid,
        c.tid(),
        models.uuid(Json.text(cat, "id")),
        name,
        bytes,
        hash);
    return Json.object("fileId", fid, "name", name, "sheets", sheets, "checksum", hash);
  }

  public JsonNode create(Ctx c, JsonNode b) {
    String code = Json.text(b, "categoryCode");
    c.check("IMPORT", code);
    var cat = models.category(c, code);
    var schema = models.schema(c, Json.text(b, "schemaVersionId"), true);
    Problem.require(
        schema.path("categoryId").equals(cat.path("id")), 422, "SCHEMA_CATEGORY", "类别与Schema不一致");
    var file = files.file(c, Json.text(b, "fileId"));
    Problem.require(
        file.path("categoryId").equals(cat.path("id")), 422, "FILE_CATEGORY", "文件归属类别不匹配");
    byte[] bytes = files.content(c, Json.text(file, "id"));
    var rows = files.read(bytes, Json.text(file, "name"), b.path("sheet").asText("CSV"));
    int limit =
        db.one("select settings from tenant where id=?", c.tid())
            .path("settings")
            .path("importLimit")
            .asInt(50000);
    Problem.require(!rows.isEmpty() && rows.size() <= limit, 422, "IMPORT_LIMIT", "文件为空或超过租户行数上限");
    Problem.require(b.path("mapping").isObject(), 422, "MAPPING_REQUIRED", "请选择列映射");
    UUID job = UUID.randomUUID();
    ObjectNode cfg = (ObjectNode) b.deepCopy();
    cfg.put("fileChecksum", Json.text(file, "checksum"));
    db.run(
        "insert into import_job(id,tenant_id,category_id,file_id,schema_id,status,config,actor)"
            + " values(?,?,?,?,?,'UPLOADED',?::jsonb,?)",
        job,
        c.tid(),
        models.uuid(Json.text(cat, "id")),
        models.uuid(Json.text(file, "id")),
        models.uuid(Json.text(schema, "id")),
        Json.str(cfg),
        c.uid());
    int i = 0;
    for (var row : rows)
      db.run(
          "insert into staging_row(tenant_id,job_id,row_no,source) values(?,?,?,?::jsonb)",
          c.tid(),
          job,
          ++i,
          Json.str(row));
    db.run(
        "update file_record set row_count=? where id=?",
        rows.size(),
        models.uuid(Json.text(file, "id")));
    return job(c, job.toString());
  }

  public ObjectNode job(Ctx c, String id) {
    var j = db.one("select * from import_job where tenant_id=? and id=?", c.tid(), models.uuid(id));
    var cat = models.categoryById(c, Json.text(j, "categoryId"));
    c.check("IMPORT", Json.text(cat, "code"));
    j.set(
        "counts",
        Json.M.valueToTree(
            db.list(
                "select state,count(*) as count from staging_row where tenant_id=? and job_id=?"
                    + " group by state",
                c.tid(),
                models.uuid(id))));
    j.set(
        "rows",
        Json.M.valueToTree(
            db.list(
                "select * from staging_row where tenant_id=? and job_id=? order by row_no",
                c.tid(),
                models.uuid(id))));
    return j;
  }

  public List<ObjectNode> list(Ctx c) {
    c.check("IMPORT", null);
    return db
        .list(
            "select j.*,c.code as category_code from import_job j join category c on"
                + " c.id=j.category_id where j.tenant_id=? order by j.created_at desc",
            c.tid())
        .stream()
        .filter(j -> c.allows(Json.text(j, "categoryCode")))
        .toList();
  }

  public JsonNode action(Ctx c, String id, String action, JsonNode b, String v) {
    var j = job(c, id);
    Db.version(j, v);
    String state = Json.text(j, "status"), next = state;
    switch (action) {
      case "validate":
        Problem.require(
            Set.of("UPLOADED", "READY", "PARTIAL_FAILED").contains(state),
            409,
            "IMPORT_STATE",
            "当前状态不可预检");
        next = "VALIDATING";
        break;
      case "commit":
        Problem.require(
            state.equals("READY") || state.equals("PARTIAL_FAILED"),
            409,
            "IMPORT_STATE",
            "必须先完成全量预检");
        Problem.require(
            db.count(
                    "select count(*) from staging_row where tenant_id=? and job_id=? and"
                        + " state='PENDING'",
                    c.tid(),
                    models.uuid(id))
                == 0,
            409,
            "PRECHECK_PENDING",
            "仍有未预检行");
        Problem.require(
            b.path("onlyPassed").asBoolean()
                || db.count(
                        "select count(*) from staging_row where tenant_id=? and job_id=? and state"
                            + " in ('ERROR','FAILED')",
                        c.tid(),
                        models.uuid(id))
                    == 0,
            422,
            "IMPORT_ERRORS",
            "有错误行，请选择仅提交通过行或先修正");
        next = "COMMITTING";
        break;
      case "pause":
        Problem.require(
            Set.of("VALIDATING", "COMMITTING").contains(state), 409, "IMPORT_STATE", "仅运行中任务可暂停");
        db.run(
            "update import_job set previous_stage=?,reason=? where tenant_id=? and id=?",
            state,
            b.path("reason").asText("人工暂停"),
            c.tid(),
            models.uuid(id));
        next = "PAUSED";
        break;
      case "resume":
        Problem.require(
            (state.equals("PAUSED") || state.equals("FAILED")), 409, "IMPORT_STATE", "任务没有暂停");
        next = j.path("previousStage").asText("VALIDATING");
        break;
      case "cancel":
        Problem.require(
            !Set.of("COMPLETED", "CANCELLED").contains(state), 409, "IMPORT_STATE", "任务已结束");
        db.run(
            "update staging_row set state='SKIPPED' where tenant_id=? and job_id=? and state not in"
                + " ('SUCCEEDED','FAILED','ERROR')",
            c.tid(),
            models.uuid(id));
        next = "CANCELLED";
        break;
      default:
        throw new Problem(400, "BAD_ACTION", "导入动作无效");
    }
    db.run(
        "update import_job set status=?,row_version=row_version+1 where tenant_id=? and id=? and"
            + " row_version=?",
        next,
        c.tid(),
        models.uuid(id),
        j.path("rowVersion").asLong());
    db.log(
        c,
        "IMPORT",
        id,
        j.path("rowVersion").asLong() + 1,
        action,
        Json.text(b, "reason"),
        Json.object("before", state, "after", next),
        Json.text(j, "categoryId"));
    return job(c, id);
  }

  public JsonNode correct(Ctx c, String job, int row, JsonNode b, String v) {
    var j = job(c, job);
    Problem.require(
        Set.of("READY", "PARTIAL_FAILED", "UPLOADED").contains(Json.text(j, "status")),
        409,
        "IMPORT_RUNNING",
        "运行中任务不可修正");
    var r =
        db.one(
            "select * from staging_row where tenant_id=? and job_id=? and row_no=?",
            c.tid(),
            models.uuid(job),
            row);
    Db.version(r, v);
    Problem.require(
        Set.of("ERROR", "FAILED", "PENDING").contains(Json.text(r, "state")),
        409,
        "ROW_SUCCEEDED",
        "只能修正失败行，不重放成功行");
    Problem.require(b.path("source").isObject(), 422, "SOURCE_REQUIRED", "请提供修正后的源行");
    db.run(
        "update staging_row set"
            + " source=?::jsonb,state='PENDING',errors='[]',row_version=row_version+1 where"
            + " tenant_id=? and job_id=? and row_no=?",
        Json.str(b.path("source")),
        c.tid(),
        models.uuid(job),
        row);
    db.run(
        "update import_job set status='UPLOADED',row_version=row_version+1 where tenant_id=? and"
            + " id=?",
        c.tid(),
        models.uuid(job));
    return job(c, job);
  }

  ObjectNode map(Ctx c, JsonNode j, JsonNode source) {
    var cfg = j.path("config");
    var schema = models.schema(c, Json.text(j, "schemaId"), true);
    var fs = rules.fields(schema.path("bundle"));
    var out =
        Json.object(
            "schemaVersionId",
            j.path("schemaId"),
            "categoryCode",
            cfg.path("categoryCode"),
            "attributes",
            Json.obj());
    var attrs = (ObjectNode) out.get("attributes");
    for (var it : iter(cfg.path("mapping"))) {
      String sourceCol = it.getKey();
      var conf = it.getValue();
      String target = conf.isTextual() ? conf.asText() : conf.path("target").asText();
      String raw = source.path(sourceCol).asText();
      if (raw.isEmpty()) continue;
      JsonNode v = raw.equals("null") ? NullNode.instance : Json.M.valueToTree(raw);
      if (target.startsWith("attributes.")) {
        String code = target.substring(11);
        var f = fs.get(code);
        Problem.require(f != null, 422, "MAPPING_FIELD", "列映射包含未知属性");
        String type = f.path("type").asText();
        if (!v.isNull()) {
          switch (type) {
            case "INTEGER":
            case "DECIMAL":
              try {
                v =
                    Json.object(
                        "value",
                        new BigDecimal(raw),
                        "unit",
                        conf.path("unit").asText(f.path("unit").asText()));
              } catch (NumberFormatException e) {
                throw new Problem(422, "INVALID_NUMBER", "第" + sourceCol + "列不是十进制数");
              }
              break;
            case "BOOLEAN":
              Problem.require(
                  raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false"),
                  422,
                  "TYPE_BOOLEAN",
                  "布尔列须为true或false");
              v = BooleanNode.valueOf(Boolean.parseBoolean(raw));
              break;
            case "REFERENCE":
              v = Json.object("type", f.path("referenceType"), "id", raw);
              break;
          }
        }
        attrs.set(code, v);
      } else {
        Problem.require(
            Set.of(
                    "materialName",
                    "baseUnitCode",
                    "id",
                    "externalId",
                    "systemId",
                    "baseVersion",
                    "legacyNo",
                    "numberSource",
                    "sourceEvidence")
                .contains(target),
            422,
            "MAPPING_FIELD",
            "未知目标列");
        out.set(target, v);
      }
    }
    if (cfg.hasNonNull("parseRuleVersionId") && out.hasNonNull("legacyNo")) {
      var parsed =
          rules.parse(
              c,
              models.schema(c, cfg.path("parseRuleVersionId").asText(), false),
              out.path("legacyNo").asText());
      Problem.require(
          parsed.path("matchType").asText().equals("EXACT_SYNTAX"),
          422,
          "PARSE_NO_MATCH",
          "旧料号无法解析");
      for (var e : iter(parsed.path("attributes"))) {
        if (attrs.has(e.getKey()))
          Problem.require(
              rules.equal(attrs.get(e.getKey()), e.getValue()),
              422,
              "PARSE_CONFLICT",
              "输入列与解析属性矛盾，请人工选择");
        else attrs.set(e.getKey(), e.getValue());
      }
      out.set("parseRuleVersionId", cfg.get("parseRuleVersionId"));
    }
    if (cfg.path("mode").asText("CREATE").equals("UPDATE")) {
      String mid = resolve(c, out);
      var current = materials.get(c, mid, false);
      Problem.require(
          current.path("categoryId").equals(j.path("categoryId")),
          422,
          "SCHEMA_CATEGORY",
          "更新对象与导入类别不一致，请核对UUID或外部身份");
      Problem.require(
          current.path("schemaVersionId").equals(j.path("schemaId")),
          422,
          "SCHEMA_VERSION_CONFLICT",
          "更新对象与所选Schema版本不一致，请选择对象绑定版本并重新预检");
      Problem.require(out.has("baseVersion"), 422, "VERSION_REQUIRED", "更新行须提供baseVersion");
      Problem.require(
          out.path("baseVersion").asLong() == current.path("rowVersion").asLong(),
          409,
          "VERSION_CONFLICT",
          "更新行基础版本冲突");
      ObjectNode merged = materials.draftBody(c, current);
      if (out.has("materialName")) merged.set("materialName", out.get("materialName"));
      if (out.has("baseUnitCode")) merged.set("baseUnitCode", out.get("baseUnitCode"));
      for (var e : iter(attrs))
        ((ObjectNode) merged.path("attributes")).set(e.getKey(), e.getValue());
      var n = materials.body(c, merged, true);
      materials.protect(c, current, n);
      out.set("candidate", merged);
      out.put("resolvedId", mid);
    } else {
      materials.body(c, out, true);
      if (out.hasNonNull("externalId")) {
        Problem.require(out.hasNonNull("systemId"), 422, "EXTERNAL_SYSTEM", "外部身份必须同时提供系统ID");
        db.one(
            "select id from integration_system where tenant_id=? and id=?",
            c.tid(),
            models.uuid(Json.text(out, "systemId")));
        Problem.require(
            db.count(
                    "select count(*) from external_identity where tenant_id=? and system_id=? and"
                        + " external_id=?",
                    c.tid(),
                    models.uuid(Json.text(out, "systemId")),
                    Json.text(out, "externalId"))
                == 0,
            409,
            "EXTERNAL_ID_CONFLICT",
            "外部身份已存在");
      }
      var normalized = materials.body(c, out, true);
      String no =
          out.path("numberSource").asText().equals("LEGACY")
              ? Json.text(out, "legacyNo")
              : rules.number(
                  c,
                  Json.text(schema, "id"),
                  schema.path("bundle"),
                  normalized.path("attributes"),
                  false);
      out.put("previewNo", no);
      Problem.require(
          no.contains("{流水")
              || db.count(
                      "select count(*) from material where tenant_id=? and material_no=?",
                      c.tid(),
                      no)
                  == 0,
          409,
          "CODE_CONFLICT",
          "正式料号已存在");
    }
    return out;
  }

  String resolve(Ctx c, JsonNode b) {
    String mid = b.path("id").asText("");
    if (!mid.isEmpty()) materials.get(c, mid, false);
    if (b.hasNonNull("externalId")) {
      Problem.require(b.hasNonNull("systemId"), 422, "EXTERNAL_SYSTEM", "外部ID需要系统");
      var x =
          db.one(
              "select material_id from external_identity where tenant_id=? and system_id=? and"
                  + " external_id=?",
              c.tid(),
              models.uuid(Json.text(b, "systemId")),
              Json.text(b, "externalId"));
      Problem.require(
          mid.isEmpty() || mid.equals(Json.text(x, "materialId")),
          409,
          "IDENTITY_CONFLICT",
          "多个身份指向不同物料");
      mid = Json.text(x, "materialId");
    }
    Problem.require(!mid.isEmpty(), 422, "IDENTITY_REQUIRED", "更新须UUID或系统+外部ID，不猜测料号");
    return mid;
  }

  List<Map.Entry<String, JsonNode>> iter(JsonNode n) {
    List<Map.Entry<String, JsonNode>> x = new ArrayList<>();
    n.fields().forEachRemaining(x::add);
    return x;
  }

  @Scheduled(fixedDelayString = "${mdm.worker-delay:1000}")
  public void work() {
    for (var j :
        db.list(
            "select j.* from import_job j join tenant t on t.id=j.tenant_id where t.status='ACTIVE'"
                + " and j.status in ('VALIDATING','COMMITTING') order by j.created_at limit 10")) {
      try {
        for (int batch = 0; batch < 50; batch++) {
          boolean more = process(j);
          if (!more) break;
        }
      } catch (Exception e) {
        db.tx(
            () -> {
              db.run(
                  "update import_job set"
                      + " previous_stage=status,status='FAILED',reason=?,row_version=row_version+1"
                      + " where id=? and status in ('VALIDATING','COMMITTING')",
                  e.getMessage(),
                  models.uuid(Json.text(j, "id")));
              return null;
            });
      }
    }
  }

  boolean process(JsonNode job) {
    String tenant = Json.text(job, "tenantId"), jid = Json.text(job, "id");
    return db.tx(
        () -> {
          var j =
              db.maybe(
                  "select * from import_job where tenant_id=? and id=? for update skip locked",
                  models.uuid(tenant),
                  models.uuid(jid));
          if (j == null || !Set.of("VALIDATING", "COMMITTING").contains(Json.text(j, "status")))
            return false;
          auth.active(tenant);
          var c = auth.worker(tenant, Json.text(j, "actor"));
          var cat = models.categoryById(c, Json.text(j, "categoryId"));
          c.check("IMPORT", Json.text(cat, "code"));
          String stage = Json.text(j, "status");
          if (!stage.equals("VALIDATING") && !stage.equals("COMMITTING")) return false;
          String wanted = stage.equals("VALIDATING") ? "PENDING" : "VALID";
          var row =
              db.maybe(
                  "select * from staging_row where tenant_id=? and job_id=? and state=? order by"
                      + " row_no for update skip locked limit 1",
                  c.tid(),
                  models.uuid(jid),
                  wanted);
          if (row == null) {
            long errors =
                db.count(
                    "select count(*) from staging_row where tenant_id=? and job_id=? and state in"
                        + " ('ERROR','FAILED')",
                    c.tid(),
                    models.uuid(jid));
            db.run(
                "update import_job set status=?,row_version=row_version+1 where tenant_id=? and"
                    + " id=?",
                stage.equals("VALIDATING") ? "READY" : errors == 0 ? "COMPLETED" : "PARTIAL_FAILED",
                c.tid(),
                models.uuid(jid));
            return false;
          }
          int rowNo = row.path("rowNo").asInt();
          try {
            return db.nested(
                () -> {
                  if (stage.equals("VALIDATING")) {
                    ObjectNode n = map(c, j, row.path("source"));
                    {
                      String fingerprint =
                          j.path("config").path("mode").asText("CREATE").equals("UPDATE")
                              ? n.path("resolvedId").asText()
                              : n.path("previewNo").asText().contains("{流水")
                                  ? Json.hash(Json.canonical(n.path("attributes")))
                                  : n.path("previewNo").asText();
                      n.put("fingerprint", fingerprint);
                      Problem.require(
                          db.count(
                                  "select count(*) from staging_row where tenant_id=? and job_id=?"
                                      + " and row_no<>? and state in ('VALID','SUCCEEDED') and"
                                      + " candidate->>'fingerprint'=?",
                                  c.tid(),
                                  models.uuid(jid),
                                  rowNo,
                                  fingerprint)
                              == 0,
                          409,
                          "DUPLICATE_ROW",
                          "同一文件含重复业务行");
                    }
                    db.run(
                        "update staging_row set"
                            + " candidate=?::jsonb,state='VALID',errors='[]',row_version=row_version+1"
                            + " where tenant_id=? and job_id=? and row_no=?",
                        Json.str(n),
                        c.tid(),
                        models.uuid(jid),
                        rowNo);
                  } else {
                    ObjectNode n = map(c, j, row.path("source"));
                    String mid, rid = null;
                    if (j.path("config").path("mode").asText("CREATE").equals("UPDATE")) {
                      var r =
                          materials.newRequest(
                              c,
                              Json.text(n, "resolvedId"),
                              Json.object(
                                  "kind",
                                  "CHANGE",
                                  "reason",
                                  j.path("config").path("reason").asText("批量导入更新"),
                                  "candidate",
                                  n.path("candidate")),
                              n.path("baseVersion").asText());
                      mid = Json.text(r, "materialId");
                      rid = Json.text(r, "id");
                    } else {
                      var m = materials.create(c, n);
                      mid = Json.text(m, "id");
                      if (n.hasNonNull("externalId"))
                        db.run(
                            "insert into"
                                + " external_identity(tenant_id,system_id,external_id,material_id)"
                                + " values(?,?,?,?)",
                            c.tid(),
                            models.uuid(Json.text(n, "systemId")),
                            Json.text(n, "externalId"),
                            models.uuid(mid));
                    }
                    db.run(
                        "update staging_row set"
                            + " state='SUCCEEDED',material_id=?,request_id=?,row_version=row_version+1"
                            + " where tenant_id=? and job_id=? and row_no=?",
                        models.uuid(mid),
                        rid == null ? null : models.uuid(rid),
                        c.tid(),
                        models.uuid(jid),
                        rowNo);
                  }
                  return true;
                });
          } catch (org.springframework.dao.DataAccessException e) {
            db.run(
                "update staging_row set state='FAILED',errors=?::jsonb,row_version=row_version+1"
                    + " where tenant_id=? and job_id=? and row_no=?",
                Json.str(
                    Json.arr()
                        .add(
                            Json.object(
                                "code",
                                "DATABASE_CONFLICT",
                                "message",
                                "唯一性或版本冲突，行事务已回滚",
                                "suggestion",
                                "核对身份并修正后重试"))),
                c.tid(),
                models.uuid(jid),
                rowNo);
            return true;
          } catch (Problem p) {
            db.run(
                "update staging_row set state=?,errors=?::jsonb,row_version=row_version+1 where"
                    + " tenant_id=? and job_id=? and row_no=?",
                stage.equals("VALIDATING") ? "ERROR" : "FAILED",
                Json.str(
                    Json.arr()
                        .add(
                            Json.object(
                                "code",
                                p.code,
                                "message",
                                p.getMessage(),
                                "errors",
                                p.errors,
                                "suggestion",
                                "修正源行后仅重试本失败行"))),
                c.tid(),
                models.uuid(jid),
                rowNo);
            return true;
          }
        });
  }

  public JsonNode report(Ctx c, String jid) {
    var j = job(c, jid);
    List<JsonNode> items = new ArrayList<>();
    for (var row : j.path("rows"))
      for (var envelope : row.path("errors")) {
        var details =
            envelope.path("errors").isArray() && !envelope.path("errors").isEmpty()
                ? envelope.path("errors")
                : Json.arr().add(envelope);
        for (var error : details) {
          String target = error.path("fieldPath").asText("ROW");
          String attr = target.replaceFirst("^/attributes/", "");
          String column = "";
          for (var entry : iter(j.path("config").path("mapping"))) {
            String mapped =
                entry.getValue().isTextual()
                    ? entry.getValue().asText()
                    : entry.getValue().path("target").asText();
            if (mapped.equals("attributes." + attr) || mapped.equals(attr)) {
              column = entry.getKey();
              break;
            }
          }
          items.add(
              Json.object(
                  "rowNo",
                  row.path("rowNo"),
                  "sourceColumn",
                  column,
                  "targetAttribute",
                  target,
                  "errorCode",
                  error.path("code"),
                  "originalValue",
                  column.isEmpty() ? row.path("source") : row.path("source").path(column),
                  "message",
                  error.path("message"),
                  "suggestion",
                  error
                      .path("suggestion")
                      .asText(envelope.path("suggestion").asText("核对身份与基础版本，修正本行后重新预检")),
                  "state",
                  row.path("state")));
        }
      }
    byte[] content =
        files.table(
            List.of(
                "rowNo",
                "sourceColumn",
                "targetAttribute",
                "errorCode",
                "originalValue",
                "message",
                "suggestion",
                "state"),
            items,
            "CSV");
    UUID id = UUID.randomUUID();
    db.run(
        "insert into file_record(id,tenant_id,category_id,name,kind,content,row_count)"
            + " values(?,?,?,'导入错误.csv','IMPORT',?,?)",
        id,
        c.tid(),
        models.uuid(Json.text(j, "categoryId")),
        content,
        items.size());
    return Json.object("id", id, "rowCount", items.size());
  }
}
