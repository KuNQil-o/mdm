package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class Api {
  final Db db;
  final Auth auth;
  final Tenants tenants;
  final Models models;
  final Materials materials;
  final Search search;
  final Files files;
  final Imports imports;
  final Integrations integrations;
  final Deliveries deliveries;
  final Exports exports;

  public Api(
      Db d,
      Auth a,
      Tenants t,
      Models m,
      Materials mat,
      Search s,
      Files f,
      Imports imp,
      Integrations i,
      Deliveries del,
      Exports exp) {
    db = d;
    auth = a;
    tenants = t;
    models = m;
    materials = mat;
    search = s;
    files = f;
    imports = imp;
    integrations = i;
    deliveries = del;
    exports = exp;
  }

  @RequestMapping(
      value = "/**",
      method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
  public ResponseEntity<JsonNode> api(
      HttpServletRequest r, @RequestBody(required = false) JsonNode incoming) {
    String p = r.getRequestURI().substring("/api/v1".length()), method = r.getMethod();
    JsonNode body = incoming == null ? Json.obj() : incoming;
    validateMethod(p, method);
    boolean read =
        method.equals("GET")
            || Set.of(
                    "/materials/search",
                    "/materials:decisions",
                    "/materials:validate",
                    "/material-numbers:preview",
                    "/material-numbers:parse")
                .contains(p)
            || p.endsWith("/preview")
            || p.endsWith("/test");
    JsonNode result;
    if (p.equals("/me/tenants")) {
      var u = auth.user(r);
      result =
          Json.M.valueToTree(
              db.list(
                  "select t.*,m.roles,m.category_scope from tenant t join tenant_member m on"
                      + " t.id=m.tenant_id where m.user_id=? and m.active order by t.code",
                  models.uuid(Json.text(u, "id"))));
    } else if (p.startsWith("/platform/")) {
      auth.platform(r);
      var user = auth.user(r);
      if (read) result = platform(r, p, method, body, Json.text(user, "id"));
      else {
        var c =
            new Ctx(
                "00000000-0000-0000-0000-000000000000",
                Json.text(user, "id"),
                Set.of(),
                Set.of(),
                UUID.randomUUID().toString());
        result =
            db.idem(
                c,
                method + ":" + p,
                r.getHeader("Idempotency-Key"),
                Json.object("body", body, "ifMatch", r.getHeader("If-Match")),
                () -> platform(r, p, method, body, c.user()));
      }
    } else {
      Ctx c = auth.ctx(r, !read);
      if (read) result = dispatch(c, r, p, method, body);
      else
        result =
            db.idem(
                c,
                method + ":" + p,
                r.getHeader("Idempotency-Key"),
                Json.object("body", body, "ifMatch", r.getHeader("If-Match")),
                () -> dispatch(c, r, p, method, body));
    }
    var builder =
        ResponseEntity.status(
            method.equals("POST") && p.equals("/materials")
                ? 201
                : method.equals("POST") && p.equals("/import-jobs") ? 202 : 200);
    builder.cacheControl(CacheControl.noStore()).varyBy("X-Tenant-Code");
    if (result.isObject() && result.has("rowVersion"))
      builder.eTag(result.path("rowVersion").asText());
    return builder.body(result);
  }

  void validateMethod(String p, String method) {
    String[] s = p.substring(1).split("/");
    Set<String> allowed = Set.of("POST");
    if (Set.of("/materials/search", "/materials/export").contains(p)) allowed = Set.of("POST");
    else if (Set.of(
            "/me/tenants",
            "/tenants/current",
            "/tenant/users",
            "/dashboard",
            "/operation-logs",
            "/materials/by-no",
            "/requests",
            "/deliveries",
            "/export-jobs")
        .contains(p)) allowed = Set.of("GET");
    else if (Set.of(
            "/categories",
            "/metadata",
            "/references",
            "/tenant/members",
            "/tenant/roles",
            "/platform/tenants",
            "/import-jobs",
            "/integration-systems")
        .contains(p)) allowed = Set.of("GET", "POST");
    else if (p.equals("/tenant/settings")) allowed = Set.of("GET", "PATCH");
    else if (s.length == 2
        && Set.of("schemas", "mappings", "requests", "integration-systems").contains(s[0]))
      allowed = s[0].equals("schemas") ? Set.of("GET", "PATCH", "DELETE") : Set.of("GET", "PATCH");
    else if (s.length == 2 && s[0].equals("materials")) allowed = Set.of("GET", "PATCH", "DELETE");
    else if (s.length == 2 && s[0].equals("categories")) allowed = Set.of("GET", "PATCH", "DELETE");
    else if (s.length == 2 && s[0].equals("metadata")) allowed = Set.of("PATCH");
    else if (s.length == 2 && Set.of("import-jobs", "deliveries", "export-jobs").contains(s[0]))
      allowed = Set.of("GET");
    else if (s.length == 3 && s[0].equals("platform") && s[1].equals("tenants"))
      allowed = Set.of("GET", "PATCH");
    else if (s.length == 4 && s[0].equals("platform") && s[3].equals("overview"))
      allowed = Set.of("GET");
    else if (s.length == 3 && s[0].equals("tenant")) allowed = Set.of("PATCH");
    else if (s.length == 3 && s[0].equals("categories") && s[2].equals("schema"))
      allowed = Set.of("GET");
    else if (s.length == 3 && s[0].equals("categories") && s[2].equals("schemas"))
      allowed = Set.of("GET", "POST");
    else if (s.length == 3
        && ((s[0].equals("materials") && s[2].equals("history"))
            || (s[0].equals("schemas") && s[2].equals("impact")))) allowed = Set.of("GET");
    else if (s.length == 3
        && s[0].equals("integration-systems")
        && Set.of("mappings", "identities").contains(s[2])) allowed = Set.of("GET", "POST");
    else if (s.length == 3 && s[0].equals("integration-systems") && s[2].equals("reconciliations"))
      allowed = Set.of("GET");
    else if (s.length == 4 && s[0].equals("import-jobs") && s[2].equals("rows"))
      allowed = Set.of("PATCH");
    Problem.require(allowed.contains(method), 405, "METHOD_NOT_ALLOWED", "此接口不支持该HTTP方法");
  }

  JsonNode platform(HttpServletRequest r, String p, String method, JsonNode b, String actor) {
    String[] s = p.substring(1).split("/");
    if (p.equals("/platform/tenants"))
      return method.equals("GET") ? tenants.platformList() : tenants.create(actor, b);
    if (s.length >= 3 && s[1].equals("tenants")) {
      String id = s[2];
      if (s.length == 3) {
        if (method.equals("PATCH")) return tenants.patch(actor, id, b, r.getHeader("If-Match"));
        return db.one("select * from tenant where id=?", models.uuid(id));
      }
      if (s.length == 4 && s[3].equals("overview")) return tenants.overview(id);
      if (s.length == 4) return tenants.action(actor, id, s[3], b, r.getHeader("If-Match"));
    }
    throw new Problem(404, "API_NOT_FOUND", "平台接口不存在");
  }

  JsonNode dispatch(Ctx c, HttpServletRequest r, String p, String method, JsonNode b) {
    String[] s = p.substring(1).split("/");
    String v = r.getHeader("If-Match");
    if (p.equals("/tenants/current")) {
      var u = auth.user(r);
      var t = db.one("select * from tenant where id=?", c.tid());
      return Json.object(
          "tenant",
          t,
          "user",
          u,
          "member",
          db.one("select * from tenant_member where tenant_id=? and user_id=?", c.tid(), c.uid()),
          "actions",
          c.actions(),
          "categoryScope",
          c.scope());
    }
    if (p.equals("/tenant/settings")) {
      if (method.equals("GET")) {
        c.check("READ", null);
        return db.one("select id,settings,row_version from tenant where id=?", c.tid());
      }
      return tenants.settings(c, b, v);
    }
    if (p.equals("/tenant/members")) {
      c.check("ADMIN", null);
      if (method.equals("GET"))
        return Json.M.valueToTree(
            db.list(
                "select m.*,u.name as user_name,u.code as user_code from tenant_member m join"
                    + " business_user u on u.id=m.user_id where m.tenant_id=? order by u.name",
                c.tid()));
      return tenants.member(c, b, null, null);
    }
    if (s.length == 3 && s[0].equals("tenant") && s[1].equals("members") && method.equals("PATCH"))
      return tenants.member(c, b, s[2], v);
    if (p.equals("/tenant/users")) {
      c.check("ADMIN", null);
      return Json.M.valueToTree(db.list("select id,code,name from business_user order by code"));
    }
    if (p.equals("/tenant/roles")) {
      c.check("ADMIN", null);
      if (method.equals("GET"))
        return Json.M.valueToTree(
            db.list("select * from role where tenant_id=? order by code", c.tid()));
      return tenants.role(c, b, null, v);
    }
    if (s.length == 3 && s[0].equals("tenant") && s[1].equals("roles") && method.equals("PATCH"))
      return tenants.role(c, b, s[2], v);
    if (p.equals("/categories"))
      return method.equals("GET")
          ? Json.M.valueToTree(models.categories(c))
          : models.createCategory(c, b);
    if (s.length == 2 && s[0].equals("categories")) {
      if (method.equals("PATCH")) return models.patchCategory(c, s[1], b, v);
      if (method.equals("DELETE")) return models.deleteCategory(c, s[1], v);
      return models.category(c, s[1]);
    }
    if (s.length == 3 && s[0].equals("categories") && s[2].equals("schema"))
      return models.currentSchema(c, s[1], r.getParameter("versionId"));
    if (s.length == 3 && s[0].equals("categories") && s[2].equals("schemas"))
      return method.equals("GET")
          ? Json.M.valueToTree(models.schemas(c, s[1]))
          : models.createSchema(c, s[1], b);
    if (s.length >= 2 && s[0].equals("schemas")) {
      String id = s[1];
      if (s.length == 2) {
        if (method.equals("DELETE")) return models.deleteSchema(c, id, v);
        return method.equals("PATCH")
            ? models.patchSchema(c, id, b, v)
            : models.schema(c, id, false);
      }
      if (s[2].equals("impact")) return models.impact(c, id);
      if (s[2].equals("test")) return models.regress(c, models.schema(c, id, false).path("bundle"));
      return models.schemaAction(c, id, s[2], b, v);
    }
    if (p.equals("/metadata"))
      return method.equals("GET")
          ? Json.M.valueToTree(models.metadata(c))
          : models.createMeta(c, b);
    if (s.length == 2 && s[0].equals("metadata") && method.equals("PATCH"))
      return models.patchMeta(c, s[1], b, v);
    if (p.equals("/references")) {
      if (method.equals("GET")) {
        c.check("READ", null);
        return Json.M.valueToTree(
            db.list(
                "select * from reference_entity where tenant_id=? order by type,name", c.tid()));
      }
      return models.reference(c, b, v);
    }
    if (p.equals("/materials:decisions")) return materials.decisions(c, b);
    if (p.equals("/materials:validate")) return materials.validate(c, b);
    if (p.equals("/material-numbers:preview")) return materials.preview(c, b);
    if (p.equals("/material-numbers:parse")) {
      Problem.require(
          b.hasNonNull("parseRuleVersionId"), 422, "PARSE_VERSION_REQUIRED", "请明确选择解析规则版本");
      return rules()
          .parse(
              c,
              models.schema(c, Json.text(b, "parseRuleVersionId"), false),
              Json.text(b, "materialNo"));
    }
    if (p.equals("/materials/search")) return search.find(c, b);
    if (p.equals("/materials/export")) return exports.create(c, b);
    if (p.equals("/export-jobs")) return exports.list(c);
    if (s.length == 2 && s[0].equals("export-jobs")) return exports.get(c, s[1]);
    if (p.equals("/materials/by-no")) {
      c.check("READ", null);
      var m =
          db.one(
              "select id from material where tenant_id=? and material_no=?",
              c.tid(),
              r.getParameter("no"));
      return materials.get(c, Json.text(m, "id"), true);
    }
    if (p.equals("/materials") && method.equals("POST")) return materials.create(c, b);
    if (s.length >= 2 && s[0].equals("materials")) {
      String id = s[1];
      if (s.length == 2) {
        if (method.equals("PATCH")) return materials.patch(c, id, b, v);
        if (method.equals("DELETE")) return materials.deleteDraft(c, id, v);
        return materials.get(c, id, true);
      }
      if (s[2].equals("history")) return materials.history(c, id);
      if (s[2].equals("copy")) return materials.copy(c, id, b);
      if (s[2].equals("change-requests")) return materials.newRequest(c, id, b, v);
      if (s[2].equals("replacements")) return materials.relation(c, id, b);
      if (s[2].equals("restore")) {
        materials.get(c, id, false);
        var rev =
            db.one(
                "select snapshot from material_revision where tenant_id=? and material_id=? and"
                    + " row_version=?",
                c.tid(),
                models.uuid(id),
                b.path("version").asLong());
        return materials.newRequest(
            c,
            id,
            Json.object(
                "kind",
                "CHANGE",
                "reason",
                b.path("reason"),
                "candidate",
                materials.draftBody(c, rev.path("snapshot"))),
            v);
      }
    }
    if (p.equals("/requests")) return Json.M.valueToTree(materials.requests(c));
    if (s.length >= 2 && s[0].equals("requests")) {
      if (s.length == 2) {
        if (method.equals("PATCH")) return patchRequest(c, s[1], b, v);
        return materials.request(c, s[1]);
      }
      return materials.action(c, s[1], s[2], b, v);
    }
    if (s.length == 3 && s[0].equals("import-files") && s[2].equals("preview")) {
      c.check("IMPORT", null);
      var file = files.file(c, s[1]);
      var rows =
          files.read(
              files.content(c, s[1]), Json.text(file, "name"), b.path("sheet").asText("CSV"));
      return Json.object(
          "columns",
          rows.isEmpty()
              ? List.of()
              : new ArrayList<>(
                  rows.getFirst().properties().stream().map(Map.Entry::getKey).toList()),
          "sample",
          rows.stream().limit(5).toList(),
          "rowCount",
          rows.size());
    }
    if (p.equals("/import-jobs"))
      return method.equals("GET") ? Json.M.valueToTree(imports.list(c)) : imports.create(c, b);
    if (s.length >= 2 && s[0].equals("import-jobs")) {
      if (s.length == 2) return imports.job(c, s[1]);
      if (s[2].equals("error-report")) return imports.report(c, s[1]);
      if (s[2].equals("rows") && s.length == 4)
        return imports.correct(c, s[1], Integer.parseInt(s[3]), b, v);
      return imports.action(c, s[1], s[2], b, v);
    }
    if (p.equals("/integration-systems"))
      return method.equals("GET")
          ? Json.M.valueToTree(integrations.systems(c))
          : integrations.saveSystem(c, null, b, null);
    if (s.length >= 2 && s[0].equals("integration-systems")) {
      String id = s[1];
      if (s.length == 2)
        return method.equals("PATCH")
            ? integrations.saveSystem(c, id, b, v)
            : integrations.system(c, id);
      if (s[2].equals("mappings"))
        return method.equals("GET")
            ? Json.M.valueToTree(integrations.mappings(c, id))
            : integrations.createMapping(c, id, b);
      if (s[2].equals("test")) return integrations.testConnection(c, id);
      if (s[2].equals("inbound")) return integrations.inbound(c, id, b);
      if (s[2].equals("identities"))
        return method.equals("GET") ? integrations.identities(c, id) : integrations.bind(c, id, b);
      if (s[2].equals("reconcile")) return deliveries.reconcile(c, id);
      if (s[2].equals("file-inbound")) {
        Problem.require(b.path("messages").isArray(), 422, "FILE_MESSAGES", "文件入站须messages数组");
        ArrayNode results = Json.arr();
        for (var message : b.path("messages")) results.add(integrations.inbound(c, id, message));
        return Json.object("results", results);
      }
      if (s[2].equals("reconciliations")) {
        integrations.system(c, id);
        return Json.M.valueToTree(
            db.list(
                "select * from reconciliation_job where tenant_id=? and system_id=? order by"
                    + " created_at desc",
                c.tid(),
                models.uuid(id)));
      }
    }
    if (s.length >= 2 && s[0].equals("mappings")) {
      if (s.length == 2)
        return method.equals("PATCH")
            ? integrations.mappingAction(c, s[1], "edit", b, v)
            : integrations.mapping(c, s[1]);
      if (s[2].equals("preview")) return integrations.preview(c, s[1], b);
      return integrations.mappingAction(c, s[1], s[2], b, v);
    }
    if (p.equals("/deliveries")) return Json.M.valueToTree(deliveries.list(c));
    if (s.length >= 2 && s[0].equals("deliveries")) {
      if (s.length == 2) return deliveries.get(c, s[1]);
      if (s[2].equals("resend")) return deliveries.resend(c, s[1], b);
      if (s[2].equals("confirm")) return deliveries.confirm(c, s[1], b);
    }
    if (p.equals("/files/clean")) return files.clean(c);
    if (p.equals("/projections/rebuild")) {
      c.check("ADMIN", null);
      long count = 0;
      for (var row : db.list("select id,category_id from material where tenant_id=?", c.tid())) {
        var cat =
            db.one(
                "select code from category where tenant_id=? and id=?",
                c.tid(),
                models.uuid(Json.text(row, "categoryId")));
        if (!c.allows(Json.text(cat, "code"))) continue;
        var m = materials.getLocked(c, Json.text(row, "id"));
        materials.projection(
            c, m, models.schema(c, Json.text(m, "schemaVersionId"), false).path("bundle"));
        count++;
      }
      db.log(
          c,
          "PROJECTIONS",
          c.tenant(),
          0,
          "REBUILD",
          "基于当前权威版本并逐对象加锁",
          Json.object("count", count),
          null);
      return Json.object("rebuilt", count);
    }
    if (p.equals("/operation-logs")) {
      c.check("READ", null);
      String object = r.getParameter("objectId"), trace = r.getParameter("traceId");
      if (object != null && object.isBlank()) object = null;
      if (trace != null && trace.isBlank()) trace = null;
      return Json.M.valueToTree(
          db
              .list(
                  "select l.*,c.code as category_code from operation_log l left join category c on"
                      + " c.id=l.category_id where l.tenant_id=? and (?::text is null or"
                      + " l.object_id=?) and (?::text is null or l.trace_id=?) order by"
                      + " l.created_at desc limit 500",
                  c.tid(),
                  object,
                  object,
                  trace,
                  trace)
              .stream()
              .filter(
                  x ->
                      x.hasNonNull("categoryCode")
                          ? c.allows(Json.text(x, "categoryCode"))
                          : (c.scope().contains("*")
                              && (c.actions().contains("ADMIN")
                                  || c.actions().contains("INTEGRATE"))))
              .toList());
    }
    if (p.equals("/dashboard")) return dashboard(c);
    throw new Problem(404, "API_NOT_FOUND", "接口不存在：" + p);
  }

  Rules rules() {
    return models.rules;
  }

  JsonNode patchRequest(Ctx c, String id, JsonNode b, String v) {
    var req =
        db.one(
            "select * from material_request where tenant_id=? and id=? for update",
            c.tid(),
            models.uuid(id));
    var mat = materials.get(c, Json.text(req, "materialId"), false);
    var cat = models.categoryById(c, Json.text(mat, "categoryId"));
    c.check("EDIT", Json.text(cat, "code"));
    Db.version(req, v);
    Problem.require(
        Json.text(req, "state").equals("DRAFT") && c.user().equals(Json.text(req, "submittedBy")),
        403,
        "FROZEN_REQUEST",
        "只有创建人可编辑草稿申请");
    JsonNode candidate = b.has("candidate") ? b.get("candidate") : req.path("candidate");
    var n = materials.body(c, candidate, false);
    if (!Json.text(req, "kind").equals("NEW")) materials.protect(c, mat, n);
    db.run(
        "update material_request set"
            + " candidate=?::jsonb,reason=?,base_version=?,row_version=row_version+1 where"
            + " tenant_id=? and id=?",
        Json.str(candidate),
        b.path("reason").asText(Json.text(req, "reason")),
        mat.path("rowVersion").asLong(),
        c.tid(),
        models.uuid(id));
    return materials.request(c, id);
  }

  JsonNode dashboard(Ctx c) {
    c.check("READ", null);
    var cats = models.categories(c);
    Set<String> ids = new HashSet<>();
    cats.forEach(x -> ids.add(Json.text(x, "id")));
    var mats =
        db.list("select id,category_id,status from material where tenant_id=?", c.tid()).stream()
            .filter(x -> ids.contains(Json.text(x, "categoryId")))
            .toList();
    long drafts = mats.stream().filter(x -> Json.text(x, "status").equals("DRAFT")).count();
    long requests =
        materials.requests(c).stream().filter(x -> Json.text(x, "state").equals("REVIEW")).count();
    var out =
        Json.object(
            "materials",
            mats.size(),
            "drafts",
            drafts,
            "pendingRequests",
            requests,
            "imports",
            c.actions().contains("IMPORT") ? imports.list(c) : List.of(),
            "deliveries",
            c.actions().contains("INTEGRATE") ? deliveries.list(c) : List.of(),
            "databaseLockWaits",
            db.count(
                "select count(*) from pg_stat_activity where datname=current_database() and"
                    + " wait_event_type='Lock'"),
            "apiLatencyP95",
            "未采集",
            "apiErrorRate",
            "未采集",
            "ruleDuration",
            "未采集",
            "capacity",
            "未进行生产容量验证");
    out.setAll(models.rules.stats.metrics(c.tenant()));
    if (c.actions().contains("INTEGRATE")) {
      out.set(
          "oldestPendingEvent",
          db.maybe(
              "select min(e.created_at) as time from outbox_event e join delivery d on"
                  + " d.event_id=e.id where e.tenant_id=? and d.state<>'SUCCEEDED'",
              c.tid()));
      out.set(
          "reconciliationDifferences",
          db.maybe(
              "select count(*) as count from reconciliation_job j,jsonb_array_elements(j.results) r"
                  + " where j.tenant_id=? and r->>'status'<>'MATCH'",
              c.tid()));
    }
    return out;
  }

  @PostMapping(value = "/import-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public JsonNode upload(
      HttpServletRequest r, @RequestParam String categoryCode, @RequestParam MultipartFile file)
      throws java.io.IOException {
    var c = auth.ctx(r, true);
    byte[] bytes = file.getBytes();
    var b =
        Json.object(
            "name",
            file.getOriginalFilename(),
            "categoryCode",
            categoryCode,
            "checksum",
            Json.hash(Base64.getEncoder().encodeToString(bytes)));
    return db.idem(
        c,
        "UPLOAD",
        r.getHeader("Idempotency-Key"),
        b,
        () -> imports.upload(c, categoryCode, file.getOriginalFilename(), bytes));
  }

  @GetMapping("/files/{id}")
  public ResponseEntity<byte[]> download(HttpServletRequest r, @PathVariable String id) {
    var c = auth.ctx(r, false);
    var f = files.file(c, id);
    String name = Json.text(f, "name");
    return ResponseEntity.ok()
        .header(
            "Content-Disposition",
            "attachment; filename*=UTF-8''"
                + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                    .replace("+", "%20"))
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(files.content(c, id));
  }

  @PostMapping(value = "/integration-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public JsonNode integrationFile(
      HttpServletRequest r, @RequestParam String systemId, @RequestParam MultipartFile file)
      throws java.io.IOException {
    var c = auth.ctx(r, true);
    integrations.system(c, systemId);
    byte[] bytes = file.getBytes();
    Problem.require(bytes.length <= 20 * 1024 * 1024, 422, "FILE_SIZE", "文件最多20MB");
    String checksum = Json.hash(Base64.getEncoder().encodeToString(bytes));
    return db.idem(
        c,
        "INTEGRATION_FILE",
        r.getHeader("Idempotency-Key"),
        Json.object("systemId", systemId, "checksum", checksum, "name", file.getOriginalFilename()),
        () -> {
          String[] lines = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\\R");
          ArrayNode results = Json.arr();
          int row = 0;
          for (String line : lines) {
            if (line.isBlank()) continue;
            final int rowNo = ++row;
            Problem.require(rowNo <= 50000, 422, "FILE_ROWS", "最多50000行");
            try {
              results.add(
                  Json.object(
                      "rowNo",
                      rowNo,
                      "result",
                      db.nested(() -> integrations.inbound(c, systemId, Json.read(line)))));
            } catch (Problem p) {
              results.add(Json.object("rowNo", rowNo, "code", p.code, "message", p.getMessage()));
            }
          }
          UUID id = UUID.randomUUID();
          db.run(
              "insert into file_record(id,tenant_id,name,kind,content,checksum,row_count,query)"
                  + " values(?,?,?,'INTEGRATION',?,?,?,?::jsonb)",
              id,
              c.tid(),
              file.getOriginalFilename(),
              bytes,
              checksum,
              row,
              Json.str(
                  Json.object(
                      "systemId",
                      systemId,
                      "direction",
                      "INBOUND",
                      "uploader",
                      c.user(),
                      "results",
                      results)));
          db.log(
              c, "INTEGRATION_FILE", id.toString(), 0, "INBOUND_BATCH", "成功行仍需业务审批", results, null);
          return Json.object("id", id, "rowCount", row, "results", results);
        });
  }

  @PostMapping("/inbound/{systemId}")
  public JsonNode inboundClient(
      HttpServletRequest r, @PathVariable String systemId, @RequestBody JsonNode b) {
    String key = r.getHeader("X-Integration-Key");
    Ctx c = integrations.client(key);
    var system =
        db.one(
            "select id from integration_system where tenant_id=? and id=? and credential_hash=?",
            c.tid(),
            models.uuid(systemId),
            Json.hash(key));
    return db.tx(() -> integrations.inbound(c, Json.text(system, "id"), b));
  }
}
