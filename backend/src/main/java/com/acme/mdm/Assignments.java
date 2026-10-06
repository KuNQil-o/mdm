package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** Immutable numbering decisions. Source snapshots are explicitly non-authoritative copies. */
@Service
public class Assignments {
  final Db db;
  final Auth auth;
  final Models models;
  final Rules rules;
  final ReleasePackages releases;
  final SourceMetadata sources;

  public Assignments(Db d, Auth a, Models m, Rules r, ReleasePackages p, SourceMetadata s) {
    db = d;
    auth = a;
    models = m;
    rules = r;
    releases = p;
    sources = s;
  }

  void allowed(Ctx c, JsonNode rel, String action) {
    auth.active(c.tenant());
    var cat = models.categoryById(c, rel.path("categoryId").asText());
    c.check(action, cat.path("code").asText());
    var sys = sources.system(c, rel.path("sourceSystemId").asText());
    Problem.require(
        sys.path("status").asText().equals("ACTIVE"), 409, "SOURCE_PAUSED", "来源未启用，保留任务与台账");
  }

  public ObjectNode ledger(Ctx c, String id) {
    var a =
        db.one(
            "select * from material_assignment where tenant_id=? and id=?",
            c.tid(),
            SourceMetadata.id(id));
    sources.scope(c, a.path("sourceSystemId").asText());
    models.categoryById(c, a.path("categoryId").asText());
    return a;
  }

  public List<ObjectNode> list(Ctx c) {
    c.check("READ", null);
    return db
        .list(
            "select * from material_assignment where tenant_id=? order by issued_at desc,id limit"
                + " 1000",
            c.tid())
        .stream()
        .filter(
            a -> {
              try {
                ledger(c, a.path("id").asText());
                return true;
              } catch (Problem p) {
                return false;
              }
            })
        .toList();
  }

  public JsonNode preview(Ctx c, JsonNode b) {
    var rel = resolve(c, b);
    c.check("READ", models.categoryById(c, rel.path("categoryId").asText()).path("code").asText());
    var prepared =
        releases.prepare(c, rel, key(b), b.get("payload"), rel.path("everPublished").asBoolean());
    prepared.set("segments", render(c, rel, prepared, false).path("segments"));
    return prepared;
  }

  ObjectNode resolve(Ctx c, JsonNode b) {
    return b.hasNonNull("releaseId")
        ? releases.release(c, b.path("releaseId").asText())
        : releases.current(c, b.path("datasetCode").asText());
  }

  String key(JsonNode b) {
    String key = b.path("sourceRecordKey").asText();
    Problem.require(
        !key.isBlank() && key.length() <= 1000, 422, "SOURCE_KEY_REQUIRED", "必须提供稳定来源记录键");
    return key;
  }

  public JsonNode issue(Ctx c, JsonNode b, String requestId) {
    Problem.require(
        requestId != null && !requestId.isBlank(),
        400,
        "IDEMPOTENCY_REQUIRED",
        "发号必须提供Idempotency-Key");
    long started = System.nanoTime();
    boolean succeeded = false;
    try {
      var result = db.idem(c, "V3_ASSIGN", requestId, b, () -> issueAtomic(c, b, requestId));
      succeeded = true;
      return result;
    } catch (Problem p) {
      if (p.status == 422 || p.status == 409) recordFailure(c, b, p);
      throw p;
    } catch (org.springframework.dao.DataIntegrityViolationException e) {
      var p = new Problem(409, "ASSIGNMENT_CONFLICT", "来源、完整Identity或料号已存在；事务已回滚，请检查台账");
      recordFailure(c, b, p);
      throw p;
    } finally {
      releases.rules.stats.issue(c.tenant(), System.nanoTime() - started, succeeded);
    }
  }

  void recordFailure(Ctx c, JsonNode b, Problem p) {
    try {
      var r = resolve(c, b);
      allowed(c, r, "ASSIGN");
      db.tx(
          () -> {
            db.run(
                "insert into"
                    + " processing_task(id,tenant_id,release_id,source_record_key,status,error_code,error_details,actor,trace_id)"
                    + " values(?,?,?,?,'ERROR',?,?::jsonb,?,?)",
                UUID.randomUUID(),
                c.tid(),
                SourceMetadata.id(r.path("id").asText()),
                b.path("sourceRecordKey").asText(),
                p.code,
                Json.str(p.errors),
                c.uid(),
                c.trace());
            return null;
          });
    } catch (Problem ignored) {
      /* Invalid tenant/scope must never create a task. */
    }
  }

  void lock(Ctx c, String value) {
    db.jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        c.tenant() + ":" + value);
  }

  JsonNode issueAtomic(Ctx c, JsonNode b, String requestId) {
    var rel = resolve(c, b);
    allowed(c, rel, "ASSIGN");
    Problem.require(
        rel.path("status").asText().equals("PUBLISHED"),
        422,
        "RELEASE_NOT_PUBLISHED",
        "必须使用已发布的组合版本");
    String sourceKey = key(b);
    String dsCode = rel.path("dependencySnapshot").path("datasetCode").asText();
    Problem.require(
        b.path("generationIntent").asText("INITIAL").equals("INITIAL"),
        422,
        "RECODE_FORBIDDEN",
        "自动发号仅支持INITIAL；已发号物料不得自动重编号");
    lock(c, "source:" + dsCode + ":" + sourceKey);
    var old =
        db.maybe(
            "select * from material_assignment where tenant_id=? and dataset_code=? and"
                + " source_record_key=?",
            c.tid(),
            dsCode,
            sourceKey);
    if (old != null) {
      sources.scope(c, old.path("sourceSystemId").asText());
      var pinned = releases.release(c, old.path("releaseId").asText());
      var p = releases.prepare(c, pinned, sourceKey, b.get("payload"), true, true);
      String erpNo = p.path("read").path("existingMaterialNo").asText("");
      Problem.require(
          erpNo.isBlank() || erpNo.equals(old.path("materialNo").asText()),
          409,
          "ERP_VALUE_CONFLICT",
          "ERP当前料号与已有台账不一致");
      assertUnchanged(old, p);
      snapshot(c, pinned, sourceKey, p, old.path("id").asText());
      return result(old, true);
    }
    var cat = models.categoryById(c, rel.path("categoryId").asText());
    Problem.require(
        cat.path("active").asBoolean()
            && db.count(
                    "select count(*) from category where tenant_id=? and parent_id=? and active",
                    c.tid(),
                    SourceMetadata.id(cat.path("id").asText()))
                == 0,
        422,
        "CATEGORY_NOT_ACTIVE_LEAF",
        "新发号必须使用启用的叶子类别");
    var p = releases.prepare(c, rel, sourceKey, b.get("payload"), true);
    Problem.require(
        p.path("read").path("candidateEligible").asBoolean(true),
        422,
        "SOURCE_NOT_CANDIDATE",
        "来源记录不满足候选条件，不允许新发号");
    String canonical = p.path("identity").path("canonical").asText();
    lock(c, "identity:" + rel.path("categoryId").asText() + ":" + canonical);
    var same =
        db.maybe(
            "select * from material_assignment where tenant_id=? and category_id=? and"
                + " identity_canonical=?",
            c.tid(),
            SourceMetadata.id(rel.path("categoryId").asText()),
            canonical);
    Problem.require(
        same == null, 409, "IDENTITY_CONFLICT", "同类别完整Identity已绑定其他来源记录；请在ERP合并或纠正身份粒度");
    String existing = p.path("read").path("existingMaterialNo").asText("");
    if (existing.isBlank()) existing = p.path("read").path("existingNo").asText("");
    String policy = rel.path("definition").path("existingNoPolicy").asText("IGNORE");
    boolean legacy = !existing.isBlank();
    ObjectNode rendered;
    if (legacy) {
      if (policy.equals("IGNORE")) {
        snapshot(c, rel, sourceKey, p, null);
        return Json.object(
            "status",
            "IGNORED_EXISTING_ERP_NUMBER",
            "materialNo",
            existing,
            "sourceRecordKey",
            sourceKey);
      }
      Problem.require(
          Set.of("VERIFY", "IMPORT_AS_LEGACY").contains(policy),
          422,
          "ERP_EXISTING_NUMBER",
          "ERP已有料号，不允许覆盖");
      if (policy.equals("VERIFY")) {
        var parsed =
            parse(
                c,
                Json.object("codeRuleVersionId", rel.path("codeRuleId"), "materialNo", existing));
        for (var f = p.path("identity").path("values").fields(); f.hasNext(); ) {
          var field = f.next();
          Problem.require(
              parsed.path("attributes").has(field.getKey())
                  && Json.canonical(parsed.path("attributes").path(field.getKey()))
                      .equals(Json.canonical(field.getValue())),
              422,
              "LEGACY_IDENTITY_MISMATCH",
              "ERP旧料号解析与Identity不一致");
        }
      }
      rendered =
          Json.object("materialNo", existing, "segments", Json.arr(), "legacyPolicy", policy);
    } else rendered = render(c, rel, p, true);
    String no = rendered.path("materialNo").asText();
    Problem.require(
        db.count(
                "select count(*) from material_assignment where tenant_id=? and material_no=?",
                c.tid(),
                no)
            == 0,
        409,
        "CODE_CONFLICT",
        "不同Identity生成了相同料号，请发布补足区分字段的新规则");
    UUID aid = UUID.randomUUID(), task = UUID.randomUUID();
    ObjectNode input =
        Json.object(
            "source",
            p.path("read"),
            "mapped",
            p.path("mapped"),
            "attributes",
            p.path("attributes"),
            "identity",
            p.path("identity"),
            "references",
            p.path("references"),
            "segments",
            rendered.path("segments"),
            "warnings",
            p.path("warnings"),
            "dependencies",
            rel.path("dependencySnapshot"));
    String version = p.path("read").path("sourceVersion").asText(null);
    String state = legacy ? "SUCCEEDED" : "PENDING";
    db.run(
        "insert into"
            + " material_assignment(id,tenant_id,source_system_id,dataset_version_id,dataset_code,source_record_key,category_id,schema_version_id,mapping_version_id,identity_definition_version_id,identity_canonical,identity_hash,code_rule_version_id,release_id,material_no,number_source,input_snapshot,source_version,issue_request_id,writeback_status,erp_confirmed_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,case when ? then now() else"
            + " null end)",
        aid,
        c.tid(),
        SourceMetadata.id(rel.path("sourceSystemId").asText()),
        SourceMetadata.id(rel.path("datasetId").asText()),
        dsCode,
        sourceKey,
        SourceMetadata.id(rel.path("categoryId").asText()),
        SourceMetadata.id(rel.path("schemaId").asText()),
        SourceMetadata.id(rel.path("mappingId").asText()),
        SourceMetadata.id(rel.path("identityId").asText()),
        canonical,
        p.path("identity").path("hash").asText(),
        legacy ? null : SourceMetadata.id(rel.path("codeRuleId").asText()),
        SourceMetadata.id(rel.path("id").asText()),
        no,
        legacy ? "LEGACY" : "GENERATED",
        Json.str(input),
        version,
        requestId,
        state,
        legacy);
    ObjectNode adapter = (ObjectNode) rel.path("definition").path("writeback").deepCopy();
    if (!adapter.has("baseUrl"))
      adapter.put(
          "baseUrl",
          sources
              .system(c, rel.path("sourceSystemId").asText())
              .path("connectionProfile")
              .path("writebackBaseUrl")
              .asText(
                  sources
                      .system(c, rel.path("sourceSystemId").asText())
                      .path("connectionProfile")
                      .path("baseUrl")
                      .asText()));
    SourceMetadata.url(adapter.path("baseUrl").asText());
    sources.rejectCredentials(adapter);
    String stable = "MDM-" + c.tenant() + "-" + aid;
    db.run(
        "insert into"
            + " writeback_task(id,tenant_id,assignment_id,source_system_id,actor,state,adapter_snapshot,idempotency_key,confirmed_at)"
            + " values(?,?,?,?,?,?,?::jsonb,?,case when ? then now() else null end)",
        task,
        c.tid(),
        aid,
        SourceMetadata.id(rel.path("sourceSystemId").asText()),
        c.uid(),
        state,
        Json.str(adapter),
        stable,
        legacy);
    db.run(
        "insert into numbering_outbox(id,tenant_id,assignment_id,task_id,snapshot,trace_id)"
            + " values(?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        c.tid(),
        aid,
        task,
        Json.str(Json.object("materialNo", no, "sourceRecordKey", sourceKey, "assignmentId", aid)),
        c.trace());
    snapshot(c, rel, sourceKey, p, aid.toString());
    db.log(
        c,
        "MATERIAL_ASSIGNMENT",
        aid.toString(),
        1,
        "ISSUE",
        "ERP来源发号",
        Json.object(
            "materialNo",
            no,
            "numberSource",
            legacy ? "LEGACY" : "GENERATED",
            "releaseId",
            rel.path("id")),
        rel.path("categoryId").asText());
    return result(ledger(c, aid.toString()), false);
  }

  void assertUnchanged(JsonNode old, JsonNode p) {
    Problem.require(
        old.path("identityCanonical")
            .asText()
            .equals(p.path("identity").path("canonical").asText()),
        409,
        "IDENTITY_CHANGED_AFTER_ISSUE",
        "ERP身份属性在发号后改变，须人工处理，不自动重编号");
    Set<String> protectedKeys = rules.protectedFields(p.path("dependencies").path("schema"));
    for (String field : protectedKeys)
      Problem.require(
          Json.canonical(old.path("inputSnapshot").path("attributes").path(field))
              .equals(Json.canonical(p.path("attributes").path(field))),
          409,
          "IDENTITY_CHANGED_AFTER_ISSUE",
          "ERP编码依赖在发号后改变：" + field);
  }

  ObjectNode render(Ctx c, JsonNode rel, JsonNode p, boolean reserve) {
    var bundle = (ObjectNode) p.path("dependencies").path("schema").deepCopy();
    JsonNode code = bundle.path("codeRule");
    ArrayNode explained = Json.arr();
    List<String> parts = new ArrayList<>();
    int index = 0;
    for (var segment : code.path("segments")) {
      var one = (ObjectNode) bundle.deepCopy();
      var rule = (ObjectNode) code.deepCopy();
      rule.set("segments", Json.arr().add(segment));
      rule.put("separator", "");
      rule.put("allowedPattern", "[\\s\\S]+");
      one.set("codeRule", rule);
      String output =
          rules.number(c, rel.path("codeRuleId").asText(), one, p.path("attributes"), reserve);
      parts.add(output);
      explained.add(
          Json.object(
              "index",
              index++,
              "definition",
              segment,
              "input",
              segment.has("field")
                  ? Json.path(p.path("attributes"), segment.path("field").asText())
                  : segment.path("value"),
              "output",
              output));
    }
    String no = String.join(code.path("separator").asText("-"), parts);
    Problem.require(!no.isBlank() && no.length() <= 128, 422, "CODE_LENGTH", "料号长度须为1至128");
    if (reserve)
      Problem.require(
          no.matches(code.path("allowedPattern").asText("[A-Za-z0-9_./-]+")),
          422,
          "CODE_FORMAT",
          "料号不符合声明格式");
    return Json.object("materialNo", no, "segments", explained);
  }

  void snapshot(Ctx c, JsonNode rel, String sourceKey, JsonNode p, String assignment) {
    db.run(
        "insert into"
            + " material_source_snapshot(tenant_id,source_system_id,dataset_code,source_record_key,source_version,release_id,attributes,identity_canonical,assignment_id,observed_status)"
            + " values(?,?,?,?,?,?,?::jsonb,?,?,?) on"
            + " conflict(tenant_id,source_system_id,dataset_code,source_record_key) do update set"
            + " source_version=excluded.source_version,release_id=excluded.release_id,attributes=excluded.attributes,identity_canonical=excluded.identity_canonical,assignment_id=coalesce(excluded.assignment_id,material_source_snapshot.assignment_id),last_read_at=now(),observed_status=excluded.observed_status",
        c.tid(),
        SourceMetadata.id(rel.path("sourceSystemId").asText()),
        rel.path("dependencySnapshot").path("datasetCode").asText(),
        sourceKey,
        p.path("read").path("sourceVersion").asText(null),
        SourceMetadata.id(rel.path("id").asText()),
        Json.str(p.path("attributes")),
        p.path("identity").path("canonical").asText(),
        assignment == null ? null : SourceMetadata.id(assignment),
        p.path("read").path("sourceStatus").asText("ACTIVE"));
  }

  ObjectNode result(ObjectNode ledger, boolean replay) {
    return Json.object(
        "assignment",
        ledger,
        "materialNo",
        ledger.path("materialNo"),
        "assignmentId",
        ledger.path("id"),
        "requestId",
        ledger.path("issueRequestId"),
        "replayed",
        replay,
        "status",
        ledger.path("writebackStatus"));
  }

  public JsonNode search(Ctx c, JsonNode b) {
    c.check("READ", null);
    String category = b.path("categoryCode").asText();
    Problem.require(!category.isBlank(), 422, "SEARCH_CATEGORY", "属性查询须明确类别");
    var cat = models.category(c, category);
    ObjectNode latest = null;
    for (var candidate :
        db.list(
            "select r.* from release_package r join dataset_runtime d on d.tenant_id=r.tenant_id"
                + " and d.release_id=r.id where r.tenant_id=? and r.category_id=? and"
                + " r.status='PUBLISHED' order by r.version_no desc",
            c.tid(),
            SourceMetadata.id(cat.path("id").asText())))
      try {
        releases.release(c, candidate.path("id").asText());
        latest = candidate;
        break;
      } catch (Problem denied) {
      }
    Problem.require(latest != null, 422, "SEARCH_SCHEMA", "类别尚无当前授权范围内的已发布Schema");
    JsonNode bundle = latest.path("dependencySnapshot").path("schema");
    var fields = rules.fields(bundle);
    List<Object> args =
        new ArrayList<>(List.of(c.tid(), SourceMetadata.id(cat.path("id").asText())));
    StringBuilder where = new StringBuilder("a.tenant_id=? and a.category_id=?");
    var membership =
        db.one(
            "select source_scope from tenant_member where tenant_id=? and user_id=? and active",
            c.tid(),
            c.uid());
    List<String> allowed = DatasetEngine.toStrings(membership.path("sourceScope"));
    if (!allowed.contains("*")) {
      if (allowed.isEmpty()) where.append(" and false");
      else {
        where
            .append(" and (s.id::text in (")
            .append(String.join(",", Collections.nCopies(allowed.size(), "?")))
            .append(") or s.code in (")
            .append(String.join(",", Collections.nCopies(allowed.size(), "?")))
            .append("))");
        args.addAll(allowed);
        args.addAll(allowed);
      }
    }
    for (var binding :
        Map.of(
                "materialNo",
                "a.material_no",
                "sourceRecordKey",
                "a.source_record_key",
                "datasetCode",
                "a.dataset_code",
                "writebackStatus",
                "a.writeback_status")
            .entrySet())
      if (b.hasNonNull(binding.getKey())) {
        where.append(" and ").append(binding.getValue()).append("=?");
        args.add(b.path(binding.getKey()).asText());
      }
    for (var binding : Map.of("issuedFrom", ">=", "issuedTo", "<=").entrySet())
      if (b.hasNonNull(binding.getKey())) {
        try {
          java.time.Instant.parse(b.path(binding.getKey()).asText());
        } catch (Exception e) {
          throw new Problem(422, "SEARCH_DATE", "发号日期须为ISO UTC时间");
        }
        where.append(" and a.issued_at ").append(binding.getValue()).append(" ?::timestamptz");
        args.add(b.path(binding.getKey()).asText());
      }
    if (b.hasNonNull("errorCode")) {
      where.append(
          " and exists(select 1 from writeback_task w where w.tenant_id=a.tenant_id and"
              + " w.assignment_id=a.id and w.error_code=?)");
      args.add(b.path("errorCode").asText());
    }
    if (b.hasNonNull("sourceSystemId")) {
      sources.scope(c, b.path("sourceSystemId").asText());
      where.append(" and a.source_system_id=?");
      args.add(SourceMetadata.id(b.path("sourceSystemId").asText()));
    }
    if (b.hasNonNull("materialNo")) {
      where.append(" and a.material_no=?");
      args.add(b.path("materialNo").asText());
    }
    Problem.require(b.path("filters").size() <= 20, 422, "SEARCH_FILTERS", "最多20个属性条件");
    for (var filter : b.path("filters")) {
      String key = filter.path("field").asText(), op = filter.path("op").asText("eq");
      var f = fields.get(key);
      Problem.require(
          f != null && f.path("searchable").asBoolean(),
          422,
          "SEARCH_FIELD",
          "只允许已发布的可搜索属性：" + key);
      Problem.require(
          Set.of("eq", "ne", "gt", "gte", "lt", "lte", "exists").contains(op),
          422,
          "SEARCH_OPERATOR",
          "不支持的查询运算符");
      String raw = "a.input_snapshot->'attributes'->'" + key + "'";
      if (op.equals("exists")) {
        where
            .append(" and ")
            .append(raw)
            .append(" is not null and ")
            .append(raw)
            .append("<>'null'::jsonb");
        continue;
      }
      var value = rules.typed(c, bundle, f, filter.path("value"), false);
      String type = f.path("type").asText(), expr;
      Object bind;
      if (Set.of("DECIMAL", "INTEGER").contains(type)) {
        expr =
            "case when jsonb_typeof("
                + raw
                + "->'value')='number' then ("
                + raw
                + "->>'value')::numeric end";
        bind = value.path("value").decimalValue();
      } else if (type.equals("BOOLEAN")) {
        expr = "(" + raw + ")::text";
        bind = value.asText();
      } else if (type.equals("REFERENCE")) {
        Problem.require(Set.of("eq", "ne").contains(op), 422, "SEARCH_OPERATOR", "Reference只支持等值");
        expr = raw + "->>'id'";
        bind = value.path("id").asText();
      } else {
        expr = raw + "#>>'{}'";
        bind = value.asText();
      }
      String comparison =
          switch (op) {
            case "eq" -> "=";
            case "ne" -> "<>";
            case "gt" -> ">";
            case "gte" -> ">=";
            case "lt" -> "<";
            default -> "<=";
          };
      where.append(" and (").append(expr).append(") ").append(comparison).append(" ?");
      args.add(bind);
    }
    int page = b.path("page").asInt(1), size = b.path("pageSize").asInt(50);
    Problem.require(
        page >= 1 && page <= 100000 && size >= 1 && size <= 200, 422, "PAGE_SIZE", "查询分页参数无效");
    String from =
        " from material_assignment a join source_system s on s.tenant_id=a.tenant_id and"
            + " s.id=a.source_system_id where "
            + where;
    long count = db.count("select count(*)" + from, args.toArray());
    args.add(size);
    args.add((page - 1) * size);
    return Json.object(
        "items",
        db.list(
            "select a.*" + from + " order by a.issued_at desc,a.id limit ? offset ?",
            args.toArray()),
        "total",
        count,
        "page",
        page,
        "pageSize",
        size);
  }

  public JsonNode explain(Ctx c, String id) {
    var a = ledger(c, id);
    c.check("READ", models.categoryById(c, a.path("categoryId").asText()).path("code").asText());
    return Json.object(
        "ledger",
        a,
        "explanation",
        a.path("inputSnapshot"),
        "writebackTasks",
        db.list(
            "select * from writeback_task where tenant_id=? and assignment_id=? order by revision",
            c.tid(),
            SourceMetadata.id(id)),
        "writebackAttempts",
        db.list(
            "select a.* from writeback_attempt a join writeback_task t on a.tenant_id=t.tenant_id"
                + " and a.task_id=t.id where t.tenant_id=? and t.assignment_id=? order by"
                + " a.created_at",
            c.tid(),
            SourceMetadata.id(id)),
        "operationLogs",
        db.list(
            "select * from operation_log where tenant_id=? and object_id=? order by created_at",
            c.tid(),
            id));
  }

  public JsonNode parse(Ctx c, JsonNode b) {
    c.check("READ", null);
    List<String> ids = new ArrayList<>();
    if (b.has("codeRuleVersionIds")) b.path("codeRuleVersionIds").forEach(x -> ids.add(x.asText()));
    else if (b.hasNonNull("codeRuleVersionId")) ids.add(b.path("codeRuleVersionId").asText());
    Problem.require(
        !ids.isEmpty() && ids.size() <= 20,
        422,
        "EXPLICIT_PARSE_VERSION_REQUIRED",
        "必须明确提供解析规则版本，不自动猜测");
    ArrayNode matches = Json.arr();
    for (String id : ids) {
      releases.config(c, id, "CODE_RULE");
      var rel =
          db.maybe(
              "select * from release_package where tenant_id=? and code_rule_id=? and"
                  + " ever_published order by version_no desc limit 1",
              c.tid(),
              SourceMetadata.id(id));
      Problem.require(rel != null, 422, "PARSE_RULE_NOT_PUBLISHED", "解析规则尚未发布");
      releases.release(c, rel.path("id").asText());
      var schema = Json.object("id", id, "bundle", rel.path("dependencySnapshot").path("schema"));
      var parsed = rules.parse(c, schema, b.path("materialNo").asText());
      if (!parsed.path("matchType").asText().equals("NONE") && parsed.path("errors").isEmpty())
        matches.add(parsed);
    }
    Problem.require(matches.size() > 0, 422, "PARSE_FAILED", "所选明确版本均无法解析");
    Problem.require(matches.size() == 1, 422, "PARSE_AMBIGUOUS", "多个明确版本同时匹配，须指定唯一版本");
    return matches.get(0);
  }
}
