package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class V3Api {
  final Monitoring monitoring;
  final Db db;
  final Auth auth;
  final Models models;
  final SourceMetadata sources;
  final DatasetEngine datasets;
  final ReleasePackages releases;
  final Assignments assignments;
  final Writebacks writebacks;
  final Scans scans;

  public V3Api(
      Db d,
      Auth a,
      Models m,
      SourceMetadata s,
      DatasetEngine e,
      ReleasePackages r,
      Assignments n,
      Writebacks w,
      Scans j,
      Monitoring monitoring) {
    this.monitoring = monitoring;
    db = d;
    auth = a;
    models = m;
    sources = s;
    datasets = e;
    releases = r;
    assignments = n;
    writebacks = w;
    scans = j;
  }

  @RequestMapping(
      value = {
        "/source-systems",
        "/source-systems/**",
        "/source-objects",
        "/source-objects/**",
        "/source-objects:import-ddl",
        "/source-datasets",
        "/source-datasets/**",
        "/field-mappings",
        "/field-mappings/**",
        "/identity-definitions",
        "/identity-definitions/**",
        "/code-rules",
        "/code-rules/**",
        "/releases",
        "/releases/**",
        "/material-numbers:preview",
        "/material-numbers:assign",
        "/material-numbers:parse",
        "/assignment-ledger",
        "/assignment-ledger/**",
        "/scan-jobs",
        "/scan-jobs/**",
        "/processing-tasks",
        "/processing-tasks/**",
        "/writeback-tasks",
        "/writeback-tasks/**",
        "/reconciliations",
        "/v3/overview",
        "/v3/monitoring"
      },
      method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH})
  public ResponseEntity<JsonNode> handle(
      HttpServletRequest req, @RequestBody(required = false) JsonNode incoming) {
    String path = req.getRequestURI().substring("/api/v1".length());
    String method = req.getMethod();
    JsonNode b = incoming == null ? Json.obj() : incoming;
    boolean read =
        method.equals("GET")
            || path.endsWith(":preview")
            || path.endsWith(":parse")
            || path.endsWith("/preview")
            || path.endsWith("/test")
            || path.equals("/assignment-ledger/search");
    Ctx c = context(req, !read);
    JsonNode result;
    if (Set.of("/material-numbers:assign", "/material-numbers:preview").contains(path)) {
      b.fieldNames()
          .forEachRemaining(
              field ->
                  Problem.require(
                      Set.of(
                              "releaseId",
                              "datasetCode",
                              "sourceRecordKey",
                              "generationIntent",
                              "payload")
                          .contains(field),
                      422,
                      "UNKNOWN_FIELD",
                      "请求含未知字段：" + field));
    }
    if (req.getAttribute("sourceClientId") != null) {
      Problem.require(
          Set.of("/material-numbers:assign", "/material-numbers:preview").contains(path),
          403,
          "SOURCE_CLIENT_SCOPE",
          "来源客户端只能调用自身Dataset发号/预览");
      Problem.require(
          assignments
              .resolve(c, b)
              .path("sourceSystemId")
              .asText()
              .equals(req.getAttribute("sourceClientId")),
          403,
          "SOURCE_CLIENT_SCOPE",
          "客户端与Dataset来源不一致");
    }
    if (path.equals("/material-numbers:assign")) {
      Problem.require(method.equals("POST"), 405, "METHOD_NOT_ALLOWED", "发号只接受POST");
      var rel = assignments.resolve(c, b);
      if (req.getHeader("X-Source-Key") == null)
        Problem.require(
            sources
                .system(c, rel.path("sourceSystemId").asText())
                .path("connectionProfile")
                .path("allowManualAssign")
                .asBoolean(false),
            403,
            "MANUAL_ASSIGN_DISABLED",
            "此来源未授权人工发号，请使用ERP接口或扫描");
      if (b.has("payload"))
        Problem.require(
            req.getHeader("X-Source-Key") != null,
            403,
            "ERP_SOURCE_PAYLOAD_REQUIRED",
            "物料输入推送只接受对应ERP来源身份；人工发号必须读取真实来源记录");
      result = assignments.issue(c, b, req.getHeader("Idempotency-Key"));
    } else if (path.matches("/writeback-tasks/[^/]+/process")) {
      Problem.require(method.equals("POST"), 405, "METHOD_NOT_ALLOWED", "处理任务只接受POST");
      result = writebacks.process(c, path.split("/")[2]);
    } else if (path.matches("/writeback-tasks/[^/]+/retry")) {
      Problem.require(method.equals("POST"), 405, "METHOD_NOT_ALLOWED", "重试只接受POST");
      result = writebacks.retryManual(c, path.split("/")[2], b);
    } else if (path.matches("/writeback-tasks/[^/]+/rebuild")) {
      requirePost(method);
      result = writebacks.rebuild(c, path.split("/")[2], b, req.getHeader("Idempotency-Key"));
    } else if (path.equals("/reconciliations")) {
      Problem.require(method.equals("POST"), 405, "METHOD_NOT_ALLOWED", "对账只接受POST");
      result = writebacks.reconcile(c, b.path("releaseId").asText());
    } else if (read) result = dispatch(c, req, path, method, b);
    else
      result =
          db.idem(
              c,
              method + ":" + path,
              req.getHeader("Idempotency-Key"),
              Json.object("body", b, "ifMatch", req.getHeader("If-Match")),
              () -> dispatch(c, req, path, method, b));
    return ResponseEntity.ok().header("X-Request-ID", c.trace()).body(result);
  }

  Ctx context(HttpServletRequest req, boolean write) {
    String key = req.getHeader("X-Source-Key");
    if (key == null) return auth.ctx(req, write);
    var sys =
        db.maybe(
            "select s.* from source_system s join tenant t on t.id=s.tenant_id where"
                + " s.credential_hash=? and t.code=?",
            Json.hash(key),
            req.getHeader("X-Tenant-Code"));
    Problem.require(sys != null, 401, "SOURCE_AUTH_FAILED", "来源凭据或租户不匹配");
    Problem.require(sys.path("status").asText().equals("ACTIVE"), 409, "SOURCE_PAUSED", "来源已暂停");
    if (write) auth.active(sys.path("tenantId").asText());
    var c = auth.worker(sys.path("tenantId").asText(), sys.path("ownerUserId").asText());
    sources.scope(c, sys.path("id").asText());
    req.setAttribute("sourceClientId", sys.path("id").asText());
    req.setAttribute("metricsTenantId", c.tenant());
    return c;
  }

  JsonNode dispatch(Ctx c, HttpServletRequest req, String path, String method, JsonNode b) {
    String[] p = path.substring(1).split("/");
    String root = p[0], id = p.length > 1 ? p[1] : null, action = p.length > 2 ? p[2] : "";
    String version = req.getHeader("If-Match");
    boolean get = method.equals("GET");
    if (root.equals("source-systems")) {
      if (id == null) {
        if (get) return Json.M.valueToTree(sources.systems(c));
        requirePost(method);
        return sources.save(c, b, null, null);
      }
      if (action.equals("test")) {
        requirePost(method);
        return sources.test(c, id);
      }
      if (action.equals("objects")) {
        Problem.require(get, 405, "METHOD_NOT_ALLOWED", "读取使用GET");
        return sources.objects(c, id);
      }
      if (action.equals("discover")) {
        requirePost(method);
        return sources.discover(c, id, b);
      }
      if (get) {
        c.check("SOURCE_READ", null);
        var s = sources.system(c, id);
        s.remove("credentialHash");
        return s;
      }
      Problem.require(method.equals("PATCH"), 405, "METHOD_NOT_ALLOWED", "更新使用PATCH");
      return sources.save(c, b, id, version);
    }
    if (root.equals("source-objects:import-ddl")) {
      requirePost(method);
      return sources.importDdl(c, b);
    }
    if (root.equals("source-objects")) {
      if (id == null) {
        requirePost(method);
        c.check("SOURCE_IMPORT", null);
        return sources.createObject(
            c, b.path("sourceSystemId").asText(), b.path("definition"), "MANUAL", null);
      }
      c.check("SOURCE_READ", null);
      if (action.equals("alerts")) return sources.alerts(c, id);
      if (action.equals("join-suggestions")) return sources.suggestions(c, id);
      if (action.equals("diff")) return sources.diff(c, id, req.getParameter("against"));
      return sources.object(c, id);
    }
    String kind =
        switch (root) {
          case "source-datasets" -> "DATASET";
          case "field-mappings" -> "MAPPING";
          case "identity-definitions" -> "IDENTITY";
          case "code-rules" -> "CODE_RULE";
          default -> null;
        };
    if (kind != null) {
      if (id == null) {
        if (get) return Json.M.valueToTree(releases.configs(c, kind));
        requirePost(method);
        return releases.saveConfig(c, kind, b, null, null);
      }
      if (kind.equals("DATASET") && action.equals("preview")) {
        requirePost(method);
        return datasets.preview(
            c, releases.config(c, id, kind), b.path("sourceRecordKey").asText(), b.get("payload"));
      }
      if (kind.equals("DATASET") && action.equals("scan")) {
        requirePost(method);
        return scans.request(c, releases.config(c, id, kind).path("code").asText(), b);
      }
      if (kind.equals("DATASET") && action.equals("runtime") && get) {
        var ds = releases.config(c, id, kind);
        c.check("READ", null);
        return db.one(
            "select * from dataset_runtime where tenant_id=? and dataset_code=?",
            c.tid(),
            ds.path("code").asText());
      }
      if (kind.equals("DATASET") && action.equals("runtime")) {
        Problem.require(method.equals("PATCH"), 405, "METHOD_NOT_ALLOWED", "运行参数使用PATCH");
        var ds = releases.config(c, id, kind);
        assignments.allowed(c, releases.current(c, ds.path("code").asText()), "SCAN");
        var old =
            db.one(
                "select * from dataset_runtime where tenant_id=? and dataset_code=?",
                c.tid(),
                ds.path("code").asText());
        Db.version(old, version);
        int interval = b.path("intervalSeconds").asInt(old.path("intervalSeconds").asInt());
        Problem.require(
            interval >= 1 && interval <= 86400, 422, "RUNTIME_INTERVAL", "扫描间隔须为1至86400秒");
        db.run(
            "update dataset_runtime set"
                + " enabled=?,interval_seconds=?,actor=?,row_version=row_version+1 where"
                + " tenant_id=? and dataset_code=? and row_version=?",
            b.path("enabled").asBoolean(old.path("enabled").asBoolean()),
            interval,
            c.uid(),
            c.tid(),
            ds.path("code").asText(),
            old.path("rowVersion").asLong());
        return db.one(
            "select * from dataset_runtime where tenant_id=? and dataset_code=?",
            c.tid(),
            ds.path("code").asText());
      }
      if (get) return releases.config(c, id, kind);
      Problem.require(method.equals("PATCH"), 405, "METHOD_NOT_ALLOWED", "编辑草稿使用PATCH");
      return releases.saveConfig(c, kind, b, id, version);
    }
    if (root.equals("releases")) {
      if (id == null) {
        if (get) return Json.M.valueToTree(releases.list(c));
        requirePost(method);
        return releases.create(c, b);
      }
      if (action.equals("impact")) {
        Problem.require(get, 405, "METHOD_NOT_ALLOWED", "影响分析使用GET");
        return releases.impact(c, id);
      }
      if (action.equals("test")) {
        requirePost(method);
        return releases.test(c, id);
      }
      if (get && action.isEmpty()) return releases.release(c, id);
      requirePost(method);
      return releases.action(c, id, action, b, version);
    }
    if (root.equals("material-numbers:preview")) {
      requirePost(method);
      return assignments.preview(c, b);
    }
    if (root.equals("material-numbers:parse")) {
      requirePost(method);
      return assignments.parse(c, b);
    }
    if (root.equals("assignment-ledger") && "search".equals(id)) {
      requirePost(method);
      return assignments.search(c, b);
    }
    if (root.equals("assignment-ledger")) {
      Problem.require(get, 405, "METHOD_NOT_ALLOWED", "不可修改发号台账");
      return id == null
          ? Json.M.valueToTree(assignments.list(c))
          : action.equals("explain") ? assignments.explain(c, id) : assignments.ledger(c, id);
    }
    if (root.equals("scan-jobs")) {
      if (id == null) {
        Problem.require(get, 405, "METHOD_NOT_ALLOWED", "扫描通过Dataset发起");
        return Json.M.valueToTree(scans.jobs(c));
      }
      if (action.equals("run")) {
        requirePost(method);
        return scans.run(c, id);
      }
      return scans.job(c, id);
    }
    if (root.equals("processing-tasks")) {
      if (id == null) return Json.M.valueToTree(scans.errors(c));
      if (action.equals("retry")) {
        requirePost(method);
        return scans.retry(c, id, b);
      }
      if (action.equals("process")) {
        requirePost(method);
        return scans.process(c, id);
      }
      throw new Problem(404, "API_NOT_FOUND", "处理任务路径不存在");
    }
    if (root.equals("writeback-tasks")) {
      Problem.require(get, 405, "METHOD_NOT_ALLOWED", "未知回写操作");
      return id == null ? Json.M.valueToTree(writebacks.list(c)) : writebacks.task(c, id);
    }
    if (root.equals("v3") && "monitoring".equals(id)) return monitoring.read(c);
    if (root.equals("v3")) {
      c.check("READ", null);
      return Json.object(
          "sources",
          sources.systems(c).size(),
          "releases",
          releases.list(c).size(),
          "assignments",
          assignments.list(c).size(),
          "tasks",
          writebacks.list(c),
          "processing",
          scans.errors(c),
          "sourceOfRecord",
          "ERP");
    }
    throw new Problem(404, "API_NOT_FOUND", "V3接口不存在");
  }

  void requirePost(String method) {
    Problem.require(method.equals("POST"), 405, "METHOD_NOT_ALLOWED", "此操作只接受POST");
  }
}
