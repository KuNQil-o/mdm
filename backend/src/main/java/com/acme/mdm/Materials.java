package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Materials {
  final Db db;
  final Models models;
  final Rules rules;
  final Auth auth;

  public Materials(Db d, Models m, Rules r, Auth a) {
    db = d;
    models = m;
    rules = r;
    auth = a;
  }

  UUID id(String s) {
    return models.uuid(s);
  }

  public ObjectNode get(Ctx c, String mid, boolean detail) {
    var m = db.one("select * from material where tenant_id=? and id=?", c.tid(), id(mid));
    models.categoryById(c, Json.text(m, "categoryId"));
    if (detail) {
      m.set("schema", models.schema(c, Json.text(m, "schemaVersionId"), false));
      m.set(
          "requests",
          Json.M.valueToTree(
              db.list(
                  "select * from material_request where tenant_id=? and material_id=? order by"
                      + " created_at desc",
                  c.tid(),
                  id(mid))));
      m.set(
          "deliveries",
          Json.M.valueToTree(
              db.list(
                  "select d.*,s.name as system_name,e.row_version as event_version from delivery d"
                      + " join outbox_event e on e.id=d.event_id join integration_system s on"
                      + " s.id=d.system_id where d.tenant_id=? and e.material_id=? order by"
                      + " d.created_at desc",
                  c.tid(),
                  id(mid))));
      m.set(
          "replacements",
          Json.M.valueToTree(
              db
                  .list(
                      "select r.*,c.code as category_code from material_relation r join material"
                          + " target on target.id=r.replacement_id join category c on"
                          + " c.id=target.category_id where r.tenant_id=? and r.material_id=?",
                      c.tid(),
                      id(mid))
                  .stream()
                  .filter(x -> c.allows(Json.text(x, "categoryCode")))
                  .toList()));
      m.set(
          "identities",
          Json.M.valueToTree(
              db.list(
                  "select * from external_identity where tenant_id=? and material_id=?",
                  c.tid(),
                  id(mid))));
    }
    return m;
  }

  public ObjectNode body(Ctx c, JsonNode b, boolean full) {
    String schemaId = Json.text(b, "schemaVersionId");
    Problem.require(!schemaId.isEmpty(), 422, "SCHEMA_REQUIRED", "必须携带表单锁定的schemaVersionId");
    var s = models.schema(c, schemaId, true);
    var cat = models.categoryById(c, Json.text(s, "categoryId"));
    if (b.has("categoryCode"))
      Problem.require(
          b.path("categoryCode").equals(cat.path("code")), 422, "SCHEMA_CATEGORY", "类别与Schema不匹配");
    if (b.has("materialName"))
      Problem.require(b.get("materialName").isTextual(), 422, "NAME_TYPE", "物料名称必须为文本，不能显式null");
    if (b.has("baseUnitCode"))
      Problem.require(
          b.get("baseUnitCode").isTextual() && !b.path("baseUnitCode").asText().isBlank(),
          422,
          "BASE_UNIT_REQUIRED",
          "基础单位不能清空");
    if (full)
      Problem.require(!Json.text(b, "materialName").isBlank(), 422, "NAME_REQUIRED", "物料名称必填");
    var attrs = rules.normalize(c, s.path("bundle"), b.path("attributes"), full, true);
    return Json.object(
        "schemaVersionId",
        schemaId,
        "categoryId",
        cat.path("id"),
        "categoryCode",
        cat.path("code"),
        "materialName",
        b.path("materialName").asText("未命名草稿"),
        "baseUnitCode",
        b.path("baseUnitCode").asText("pcs"),
        "attributes",
        attrs,
        "originalInput",
        b.path("attributes"),
        "numberSource",
        b.path("numberSource").asText("GENERATED"),
        "legacyNo",
        b.path("legacyNo"),
        "parseRuleVersionId",
        b.path("parseRuleVersionId"),
        "sourceEvidence",
        b.path("sourceEvidence"));
  }

  public JsonNode decisions(Ctx c, JsonNode b) {
    var n = body(c, b, false);
    var schema = models.schema(c, Json.text(n, "schemaVersionId"), true);
    ObjectNode fields = Json.obj();
    for (var f : schema.path("bundle").path("attributes")) {
      boolean visible = true, required = f.path("required").asBoolean();
      try {
        if (f.has("visibleWhen"))
          visible = Json.truth(rules.eval(f.get("visibleWhen"), n.path("attributes")));
        if (f.has("requiredWhen"))
          required =
              required || Json.truth(rules.eval(f.get("requiredWhen"), n.path("attributes")));
      } catch (Problem ignored) {
      }
      fields.set(f.path("code").asText(), Json.object("visible", visible, "required", required));
    }
    return Json.object("fields", fields, "normalized", n.path("attributes"));
  }

  public JsonNode validate(Ctx c, JsonNode b) {
    var n = body(c, b, true);
    var s = models.schema(c, Json.text(n, "schemaVersionId"), true);
    return Json.object(
        "attributes",
        n.path("attributes"),
        "errors",
        Json.arr(),
        "warnings",
        Json.arr(),
        "schemaVersionId",
        s.path("id"));
  }

  public JsonNode preview(Ctx c, JsonNode b) {
    var n = body(c, b, true);
    var s = models.schema(c, Json.text(n, "schemaVersionId"), true);
    String no = rules.number(c, Json.text(s, "id"), s.path("bundle"), n.path("attributes"), false);
    ObjectNode res =
        Json.object(
            "candidateMaterialNo",
            no,
            "reserved",
            false,
            "hasSequencePlaceholder",
            no.contains("{流水"),
            "codeRuleVersion",
            s.path("version"),
            "segments",
            s.path("bundle").path("codeRule").path("segments"),
            "warnings",
            Json.arr());
    var conflict =
        db.maybe(
            "select id,category_id from material where tenant_id=? and material_no=?", c.tid(), no);
    if (conflict != null) {
      var cat = models.categoryById(c, Json.text(conflict, "categoryId"));
      if (c.allows(Json.text(cat, "code")))
        res.put("conflictMaterialId", Json.text(conflict, "id"));
      res.set("warnings", Json.arr().add("候选料号已占用，审批时将返回CODE_CONFLICT"));
    }
    return res;
  }

  public JsonNode create(Ctx c, JsonNode b) {
    var n = body(c, b, false);
    c.check("CREATE", Json.text(n, "categoryCode"));
    if (Json.text(n, "numberSource").equals("LEGACY")) {
      c.check("LEGACY", Json.text(n, "categoryCode"));
      Problem.require(
          n.hasNonNull("legacyNo") && !n.path("sourceEvidence").asText().isBlank(),
          422,
          "LEGACY_EVIDENCE",
          "保留旧号须提供旧号及来源证据");
      if (n.hasNonNull("parseRuleVersionId"))
        models.schema(c, Json.text(n, "parseRuleVersionId"), false);
    } else
      Problem.require(
          Json.text(n, "numberSource").equals("GENERATED"), 422, "NUMBER_SOURCE", "编号来源无效");
    UUID mid = UUID.randomUUID();
    var schema = models.schema(c, Json.text(n, "schemaVersionId"), true);
    db.run(
        "insert into"
            + " material(id,tenant_id,category_id,schema_version_id,number_source,parse_rule_version_id,material_name,base_unit_code,attributes,original_input,reference_snapshot,created_by,updated_by)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?::jsonb,?,?)",
        mid,
        c.tid(),
        id(Json.text(n, "categoryId")),
        id(Json.text(n, "schemaVersionId")),
        Json.text(n, "numberSource"),
        n.hasNonNull("parseRuleVersionId") ? id(Json.text(n, "parseRuleVersionId")) : null,
        Json.text(n, "materialName"),
        Json.text(n, "baseUnitCode"),
        Json.str(n.path("attributes")),
        Json.str(
            Json.object(
                "attributes",
                b.path("attributes"),
                "legacyNo",
                b.path("legacyNo"),
                "sourceEvidence",
                b.path("sourceEvidence"))),
        Json.str(rules.referenceSnapshot(c, schema.path("bundle"), n.path("attributes"))),
        c.uid(),
        c.uid());
    var m = get(c, mid.toString(), false);
    projection(c, m, schema.path("bundle"));
    revision(c, m, "CREATE", "", null);
    return response(c, m);
  }

  public JsonNode patch(Ctx c, String mid, JsonNode b, String version) {
    var m = getLocked(c, mid);
    var cat = models.categoryById(c, Json.text(m, "categoryId"));
    c.check("EDIT", Json.text(cat, "code"));
    Db.version(m, version);
    Problem.require(
        Json.text(m, "status").equals("DRAFT"), 409, "USE_CHANGE_REQUEST", "仅草稿可直接编辑，正式物料需变更申请");
    ObjectNode next = draftBody(c, m);
    for (String key : List.of("materialName", "baseUnitCode", "schemaVersionId"))
      if (b.has(key)) next.set(key, b.get(key));
    if (b.has("attributes")) {
      Problem.require(b.get("attributes").isObject(), 422, "ATTRIBUTES_SHAPE", "PATCH属性必须是对象");
      ObjectNode attrs = (ObjectNode) next.path("attributes");
      b.path("attributes").fields().forEachRemaining(e -> attrs.set(e.getKey(), e.getValue()));
    }
    var n = body(c, next, false);
    Problem.require(
        n.path("categoryId").equals(m.path("categoryId")), 422, "CATEGORY_IMMUTABLE", "草稿类别不可变更");
    db.run(
        "update material set"
            + " material_name=?,base_unit_code=?,schema_version_id=?,attributes=?::jsonb,original_input=?::jsonb,reference_snapshot=?::jsonb,row_version=row_version+1,updated_by=?,updated_at=now()"
            + " where tenant_id=? and id=?",
        Json.text(n, "materialName"),
        Json.text(n, "baseUnitCode"),
        id(Json.text(n, "schemaVersionId")),
        Json.str(n.path("attributes")),
        Json.str(
            Json.object(
                "attributes",
                next.path("attributes"),
                "legacyNo",
                m.path("originalInput").path("legacyNo"),
                "sourceEvidence",
                m.path("originalInput").path("sourceEvidence"))),
        Json.str(
            rules.referenceSnapshot(
                c,
                models.schema(c, Json.text(n, "schemaVersionId"), true).path("bundle"),
                n.path("attributes"))),
        c.uid(),
        c.tid(),
        id(mid));
    var res = get(c, mid, false);
    projection(c, res, models.schema(c, Json.text(res, "schemaVersionId"), false).path("bundle"));
    revision(c, res, "EDIT_DRAFT", "", null);
    return response(c, res);
  }

  ObjectNode getLocked(Ctx c, String mid) {
    var m =
        db.one("select * from material where tenant_id=? and id=? for update", c.tid(), id(mid));
    models.categoryById(c, Json.text(m, "categoryId"));
    return m;
  }

  public ObjectNode draftBody(Ctx c, JsonNode m) {
    var b = models.schema(c, Json.text(m, "schemaVersionId"), false).path("bundle");
    return Json.object(
        "schemaVersionId",
        m.path("schemaVersionId"),
        "materialName",
        m.path("materialName"),
        "baseUnitCode",
        m.path("baseUnitCode"),
        "attributes",
        rules.inputOnly(b, m.path("attributes")),
        "numberSource",
        m.path("numberSource"),
        "legacyNo",
        m.path("originalInput").path("legacyNo"),
        "sourceEvidence",
        m.path("originalInput").path("sourceEvidence"),
        "parseRuleVersionId",
        m.path("parseRuleVersionId"));
  }

  public JsonNode copy(Ctx c, String mid, JsonNode b) {
    var src = get(c, mid, false);
    var body = draftBody(c, src);
    body.put("numberSource", "GENERATED");
    body.remove(List.of("legacyNo", "parseRuleVersionId", "sourceEvidence"));
    body.put(
        "materialName", b.path("materialName").asText(Json.text(src, "materialName") + "（复制）"));
    return create(c, body);
  }

  public JsonNode deleteDraft(Ctx c, String mid, String v) {
    var m = getLocked(c, mid);
    var cat = models.categoryById(c, Json.text(m, "categoryId"));
    c.check("EDIT", Json.text(cat, "code"));
    Db.version(m, v);
    Problem.require(
        Json.text(m, "status").equals("DRAFT")
            && db.count(
                    "select count(*) from material_request where tenant_id=? and material_id=?",
                    c.tid(),
                    id(mid))
                == 0,
        409,
        "DRAFT_HAS_HISTORY",
        "仅无申请关联草稿可删除");
    db.run("delete from material_revision where tenant_id=? and material_id=?", c.tid(), id(mid));
    db.run("delete from material_projection where tenant_id=? and material_id=?", c.tid(), id(mid));
    db.run("delete from material where tenant_id=? and id=?", c.tid(), id(mid));
    db.log(
        c,
        "MATERIAL",
        mid,
        m.path("rowVersion").asLong(),
        "DELETE_DRAFT",
        "",
        m,
        Json.text(m, "categoryId"));
    return Json.object("deleted", true);
  }

  public JsonNode newRequest(Ctx c, String mid, JsonNode body, String v) {
    var m = getLocked(c, mid);
    var cat = models.categoryById(c, Json.text(m, "categoryId"));
    c.check("EDIT", Json.text(cat, "code"));
    Db.version(m, v);
    String kind = body.path("kind").asText("CHANGE");
    Problem.require(
        Set.of("CHANGE", "DEACTIVATE", "REACTIVATE", "NEW").contains(kind),
        422,
        "REQUEST_KIND",
        "申请类型无效");
    if (kind.equals("NEW"))
      Problem.require(Json.text(m, "status").equals("DRAFT"), 409, "STATE_CONFLICT", "首次申请须为草稿");
    else {
      Problem.require(m.hasNonNull("materialNo"), 409, "STATE_CONFLICT", "正式变更须先生效");
      Problem.require(!Json.text(body, "reason").isBlank(), 422, "REASON_REQUIRED", "变更/停启用须填写原因");
      if (kind.equals("DEACTIVATE"))
        Problem.require(Json.text(m, "status").equals("ACTIVE"), 409, "STATE_CONFLICT", "仅生效物料可停用");
      if (kind.equals("REACTIVATE"))
        Problem.require(
            Json.text(m, "status").equals("INACTIVE"), 409, "STATE_CONFLICT", "仅停用物料可启用");
    }
    ObjectNode candidate = draftBody(c, m);
    if (body.has("candidate")) {
      JsonNode patch = body.get("candidate");
      for (String k : List.of("materialName", "baseUnitCode", "schemaVersionId"))
        if (patch.has(k)) candidate.set(k, patch.get(k));
      if (patch.has("attributes")) {
        Problem.require(patch.path("attributes").isObject(), 422, "ATTRIBUTES_SHAPE", "候选属性必须是对象");
        patch
            .path("attributes")
            .fields()
            .forEachRemaining(
                e -> ((ObjectNode) candidate.get("attributes")).set(e.getKey(), e.getValue()));
      }
    }
    var normalized = body(c, candidate, false);
    Problem.require(
        normalized.path("categoryId").equals(m.path("categoryId")),
        422,
        "CATEGORY_IMMUTABLE",
        "正式类别不可变更");
    if (!kind.equals("NEW")) protect(c, m, normalized);
    UUID rid = UUID.randomUUID();
    db.run(
        "insert into"
            + " material_request(id,tenant_id,material_id,kind,base_version,candidate,submitted_by,reason)"
            + " values(?,?,?,?,?,?::jsonb,?,?)",
        rid,
        c.tid(),
        id(mid),
        kind,
        m.path("rowVersion").asLong(),
        Json.str(candidate),
        c.uid(),
        Json.text(body, "reason"));
    db.log(
        c,
        "REQUEST",
        rid.toString(),
        1,
        "CREATE_REQUEST",
        Json.text(body, "reason"),
        candidate,
        Json.text(m, "categoryId"));
    return request(c, rid.toString());
  }

  void protect(Ctx c, JsonNode m, JsonNode n) {
    var old = models.schema(c, Json.text(m, "schemaVersionId"), false).path("bundle");
    var next = models.schema(c, Json.text(n, "schemaVersionId"), false).path("bundle");
    Set<String> locked = rules.protectedFields(old);
    locked.addAll(rules.protectedFields(next));
    for (String k : locked) {
      var of = rules.fields(old).get(k);
      var nf = rules.fields(next).get(k);
      Problem.require(
          of != null
              && nf != null
              && of.path("type").equals(nf.path("type"))
              && of.path("unit").equals(nf.path("unit"))
              && of.path("referenceType").equals(nf.path("referenceType")),
          422,
          "CODE_ATTRIBUTE_PROTECTED",
          "编码属性语义版本不能改义：" + k);
      Problem.require(
          rules.equal(m.path("attributes").path(k), n.path("attributes").path(k)),
          422,
          "CODE_ATTRIBUTE_PROTECTED",
          "编码属性及其派生依赖不可修改：" + k);
    }
    Problem.require(
        Json.canonical(old.path("codeRule")).equals(Json.canonical(next.path("codeRule"))),
        422,
        "CODE_RULE_PROTECTED",
        "Schema迁移不可改变正式料号语义");
  }

  public ObjectNode request(Ctx c, String rid) {
    var r = db.one("select * from material_request where tenant_id=? and id=?", c.tid(), id(rid));
    get(c, Json.text(r, "materialId"), false);
    return r;
  }

  public List<ObjectNode> requests(Ctx c) {
    c.check("READ", null);
    return db
        .list(
            "select r.*,m.material_name,m.material_no,c.code as category_code from material_request"
                + " r join material m on r.material_id=m.id join category c on m.category_id=c.id"
                + " where r.tenant_id=? order by r.created_at desc",
            c.tid())
        .stream()
        .filter(r -> c.allows(Json.text(r, "categoryCode")))
        .toList();
  }

  public JsonNode action(Ctx c, String rid, String action, JsonNode b, String version) {
    var r =
        db.one(
            "select * from material_request where tenant_id=? and id=? for update",
            c.tid(),
            id(rid));
    var m = getLocked(c, Json.text(r, "materialId"));
    var cat = models.categoryById(c, Json.text(m, "categoryId"));
    String code = Json.text(cat, "code"),
        state = Json.text(r, "state"),
        kind = Json.text(r, "kind");
    Db.version(r, version);
    if (action.equals("submit")) {
      c.check("SUBMIT", code);
      Problem.require(state.equals("DRAFT"), 409, "STATE_CONFLICT", "申请不是草稿");
      Problem.require(
          c.user().equals(Json.text(r, "submittedBy")), 403, "NOT_SUBMITTER", "只有申请创建人可提交");
      Problem.require(
          m.path("rowVersion").asLong() == r.path("baseVersion").asLong(),
          409,
          "VERSION_CONFLICT",
          "申请基础版本已变化");
      auth.availableApprover(c, code, Json.text(cat, "approvalRole"));
      var n = body(c, r.path("candidate"), true);
      if (!kind.equals("NEW")) protect(c, m, n);
      long base = m.path("rowVersion").asLong();
      if (kind.equals("NEW")) {
        db.run(
            "update material set"
                + " status='IN_REVIEW',row_version=row_version+1,updated_by=?,updated_at=now()"
                + " where tenant_id=? and id=?",
            c.uid(),
            c.tid(),
            id(Json.text(m, "id")));
        base++;
        revision(c, get(c, Json.text(m, "id"), false), "SUBMIT", Json.text(r, "reason"), rid);
      }
      db.run(
          "update material_request set state='REVIEW',base_version=?,row_version=row_version+1"
              + " where tenant_id=? and id=?",
          base,
          c.tid(),
          id(rid));
    } else if (action.equals("approve")) {
      c.check("APPROVE", code);
      auth.role(c, Json.text(cat, "approvalRole"));
      Problem.require(state.equals("REVIEW"), 409, "STATE_CONFLICT", "申请不在待审状态");
      Problem.require(
          !c.user().equals(Json.text(r, "submittedBy")), 403, "SELF_APPROVAL", "不能审批自己提交的申请");
      Problem.require(
          m.path("rowVersion").asLong() == r.path("baseVersion").asLong(),
          409,
          "VERSION_CONFLICT",
          "正式记录版本与申请基础版本不一致");
      var n = body(c, r.path("candidate"), true);
      if (!kind.equals("NEW")) protect(c, m, n);
      String no = m.hasNonNull("materialNo") ? Json.text(m, "materialNo") : "";
      var schema = models.schema(c, Json.text(n, "schemaVersionId"), true);
      if (kind.equals("NEW")) {
        if (Json.text(m, "numberSource").equals("LEGACY")) {
          no = r.path("candidate").path("legacyNo").asText();
          Problem.require(no.matches("[A-Za-z0-9_./-]{1,128}"), 422, "CODE_FORMAT", "旧料号格式非法");
        } else
          no =
              rules.number(
                  c, Json.text(schema, "id"), schema.path("bundle"), n.path("attributes"), true);
      }
      String target = kind.equals("DEACTIVATE") ? "INACTIVE" : "ACTIVE";
      long previous = m.hasNonNull("publishedVersion") ? m.path("publishedVersion").asLong() : 0;
      db.run(
          "update material set"
              + " material_no=?,material_name=?,base_unit_code=?,schema_version_id=?,attributes=?::jsonb,original_input=?::jsonb,reference_snapshot=?::jsonb,status=?,row_version=row_version+1,published_version=row_version+1,updated_by=?,updated_at=now()"
              + " where tenant_id=? and id=?",
          no,
          Json.text(n, "materialName"),
          Json.text(n, "baseUnitCode"),
          id(Json.text(n, "schemaVersionId")),
          Json.str(n.path("attributes")),
          Json.str(
              Json.object(
                  "attributes",
                  r.path("candidate").path("attributes"),
                  "legacyNo",
                  r.path("candidate").path("legacyNo"),
                  "sourceEvidence",
                  r.path("candidate").path("sourceEvidence"))),
          Json.str(officialReferences(c, m, schema.path("bundle"), n.path("attributes"))),
          target,
          c.uid(),
          c.tid(),
          id(Json.text(m, "id")));
      db.run(
          "update material set code_rule_version_id=case when number_source='GENERATED' then"
              + " schema_version_id else null end where tenant_id=? and id=?",
          c.tid(),
          id(Json.text(m, "id")));
      var now = get(c, Json.text(m, "id"), false);
      unique(c, now, schema.path("bundle"));
      projection(c, now, schema.path("bundle"));
      revision(c, now, "APPROVE_" + kind, Json.text(r, "reason"), rid);
      UUID eid = UUID.randomUUID();
      ObjectNode event =
          Json.object(
              "eventId",
              eid,
              "tenantId",
              c.tenant(),
              "eventType",
              "material.master." + kind.toLowerCase() + ".v1",
              "aggregate",
              Json.object("id", now.path("id"), "rowVersion", now.path("rowVersion")),
              "previousPublishedVersion",
              previous == 0 ? null : previous,
              "schemaVersionId",
              now.path("schemaVersionId"),
              "data",
              now,
              "sourceSystem",
              r.hasNonNull("sourceSystemId")
                  ? r.path("sourceSystemId")
                  : Json.M.valueToTree("MATERIAL_MASTER"),
              "correlationId",
              rid,
              "traceId",
              c.trace(),
              "occurredAt",
              java.time.Instant.now().toString(),
              "changedFields",
              changed(m, now));
      db.run(
          "insert into"
              + " outbox_event(id,tenant_id,material_id,row_version,previous_published_version,event_type,snapshot,source_system_id,trace_id)"
              + " values(?,?,?,?,?,?,?::jsonb,?,?)",
          eid,
          c.tid(),
          id(Json.text(m, "id")),
          now.path("rowVersion").asLong(),
          previous == 0 ? null : previous,
          Json.text(event, "eventType"),
          Json.str(event),
          r.hasNonNull("sourceSystemId") ? id(Json.text(r, "sourceSystemId")) : null,
          c.trace());
      for (var sys :
          db.list(
              "select * from integration_system where tenant_id=? and active and default_mapping_id"
                  + " is not null and direction<>'INBOUND'",
              c.tid())) {
        if (r.hasNonNull("sourceSystemId") && r.path("sourceSystemId").equals(sys.path("id")))
          continue;
        db.run(
            "insert into delivery(id,tenant_id,event_id,system_id,mapping_id) values(?,?,?,?,?)",
            UUID.randomUUID(),
            c.tid(),
            eid,
            id(Json.text(sys, "id")),
            id(Json.text(sys, "defaultMappingId")));
      }
      db.run(
          "update material_request set state='APPROVED',row_version=row_version+1 where tenant_id=?"
              + " and id=?",
          c.tid(),
          id(rid));
    } else if (action.equals("reject") || action.equals("withdraw")) {
      if (action.equals("reject")) {
        c.check("APPROVE", code);
        auth.role(c, Json.text(cat, "approvalRole"));
        Problem.require(
            !c.user().equals(Json.text(r, "submittedBy")), 403, "SELF_APPROVAL", "不能驳回自己的申请");
        Problem.require(!Json.text(b, "reason").isBlank(), 422, "REASON_REQUIRED", "驳回意见必填");
      } else {
        c.check("SUBMIT", code);
        Problem.require(
            c.user().equals(Json.text(r, "submittedBy")), 403, "NOT_SUBMITTER", "仅提交人可撤回");
      }
      Problem.require(state.equals("REVIEW"), 409, "STATE_CONFLICT", "申请不在待审状态");
      if (kind.equals("NEW")) {
        db.run(
            "update material set"
                + " status='DRAFT',row_version=row_version+1,updated_by=?,updated_at=now() where"
                + " tenant_id=? and id=?",
            c.uid(),
            c.tid(),
            id(Json.text(m, "id")));
        revision(c, get(c, Json.text(m, "id"), false), action, Json.text(b, "reason"), rid);
      }
      db.run(
          "update material_request set state='DRAFT',base_version=?,row_version=row_version+1 where"
              + " tenant_id=? and id=?",
          kind.equals("NEW") ? m.path("rowVersion").asLong() + 1 : m.path("rowVersion").asLong(),
          c.tid(),
          id(rid));
    } else if (action.equals("cancel")) {
      c.check("SUBMIT", code);
      Problem.require(
          c.user().equals(Json.text(r, "submittedBy")) && state.equals("DRAFT"),
          409,
          "STATE_CONFLICT",
          "仅创建人可取消草稿申请");
      db.run(
          "update material_request set state='CANCELLED',row_version=row_version+1 where"
              + " tenant_id=? and id=?",
          c.tid(),
          id(rid));
    } else throw new Problem(400, "BAD_ACTION", "申请动作无效");
    db.run(
        "insert into approval_action(id,tenant_id,object_id,object_type,actor,action,comment)"
            + " values(?,?,?,'MATERIAL',?,?,?)",
        UUID.randomUUID(),
        c.tid(),
        id(rid),
        c.uid(),
        action,
        Json.text(b, "reason"));
    db.log(
        c,
        "REQUEST",
        rid,
        r.path("rowVersion").asLong() + 1,
        action,
        Json.text(b, "reason"),
        Json.object("materialId", m.path("id"), "candidate", r.path("candidate")),
        Json.text(m, "categoryId"));
    return request(c, rid);
  }

  ObjectNode officialReferences(Ctx c, JsonNode m, JsonNode bundle, JsonNode attrs) {
    var out = rules.referenceSnapshot(c, bundle, attrs);
    if (m.hasNonNull("materialNo")) {
      for (String key : rules.protectedFields(bundle))
        if (m.path("referenceSnapshot").has(key))
          out.set(key, m.path("referenceSnapshot").get(key));
    }
    return out;
  }

  void unique(Ctx c, JsonNode m, JsonNode bundle) {
    ArrayNode values = Json.arr();
    for (var f : bundle.path("uniqueFields")) values.add(m.path("attributes").path(f.asText()));
    if (values.isEmpty()) return;
    String hash = Json.hash(Json.canonical(values));
    db.run(
        "delete from material_unique_key where tenant_id=? and material_id=?",
        c.tid(),
        id(Json.text(m, "id")));
    db.run(
        "insert into material_unique_key(tenant_id,category_id,key_hash,material_id)"
            + " values(?,?,?,?)",
        c.tid(),
        id(Json.text(m, "categoryId")),
        hash,
        id(Json.text(m, "id")));
  }

  void projection(Ctx c, JsonNode m, JsonNode b) {
    db.run(
        "delete from material_projection where tenant_id=? and material_id=?",
        c.tid(),
        id(Json.text(m, "id")));
    for (var f : b.path("attributes")) {
      if (!f.path("searchable").asBoolean() && !f.path("sortable").asBoolean()) continue;
      String code = Json.text(f, "code"), type = Json.text(f, "type");
      JsonNode a = m.path("attributes").path(code);
      if (a.isNull() || a.isMissingNode()) continue;
      BigDecimal num = null;
      String text = null, date = null;
      Boolean bool = null;
      switch (type) {
        case "INTEGER":
        case "DECIMAL":
          num = Json.decimal(a);
          var u = b.path("units").path(a.path("unit").asText());
          if (!u.isMissingNode())
            num =
                num.multiply(new BigDecimal(u.path("factor").asText("1")))
                    .add(new BigDecimal(u.path("offset").asText("0")));
          break;
        case "BOOLEAN":
          bool = a.asBoolean();
          break;
        case "DATE":
          date = a.asText();
          break;
        case "REFERENCE":
          text = a.path("id").asText();
          break;
        default:
          text = a.asText();
      }
      db.run(
          "insert into"
              + " material_projection(tenant_id,material_id,attribute_code,kind,num_value,text_value,bool_value,date_value)"
              + " values(?,?,?,?,?,?,?,?::date)",
          c.tid(),
          id(Json.text(m, "id")),
          code,
          type,
          num,
          text,
          bool,
          date);
    }
  }

  ArrayNode changed(JsonNode old, JsonNode n) {
    ArrayNode a = Json.arr();
    n.fields()
        .forEachRemaining(
            e -> {
              if (!e.getValue().equals(old.path(e.getKey()))) a.add(e.getKey());
            });
    return a;
  }

  void revision(Ctx c, ObjectNode m, String action, String reason, String rid) {
    ObjectNode snap = m.deepCopy();
    snap.set(
        "schemaBundle", models.schema(c, Json.text(m, "schemaVersionId"), false).path("bundle"));
    db.run(
        "insert into"
            + " material_revision(tenant_id,material_id,row_version,snapshot,actor,reason,request_id)"
            + " values(?,?,?,?::jsonb,?,?,?)",
        c.tid(),
        id(Json.text(m, "id")),
        m.path("rowVersion").asLong(),
        Json.str(snap),
        c.uid(),
        reason,
        rid == null ? null : id(rid));
    db.log(
        c,
        "MATERIAL",
        Json.text(m, "id"),
        m.path("rowVersion").asLong(),
        action,
        reason,
        snap,
        Json.text(m, "categoryId"));
  }

  public JsonNode history(Ctx c, String mid) {
    get(c, mid, false);
    return Json.M.valueToTree(
        db.list(
            "select * from material_revision where tenant_id=? and material_id=? order by"
                + " row_version desc",
            c.tid(),
            id(mid)));
  }

  public JsonNode relation(Ctx c, String mid, JsonNode b) {
    var m = get(c, mid, false);
    var cat = models.categoryById(c, Json.text(m, "categoryId"));
    c.check("EDIT", Json.text(cat, "code"));
    var replacement = get(c, Json.text(b, "replacementId"), false);
    Problem.require(
        m.hasNonNull("materialNo") && Json.text(replacement, "status").equals("ACTIVE"),
        422,
        "REPLACEMENT_STATE",
        "须使用已生效替代物料");
    Problem.require(
        !mid.equals(Json.text(b, "replacementId")) && !Json.text(b, "reason").isBlank(),
        422,
        "RELATION_REASON",
        "替代对象不同且须填写原因");
    db.run(
        "insert into material_relation(tenant_id,material_id,replacement_id,reason)"
            + " values(?,?,?,?)",
        c.tid(),
        id(mid),
        id(Json.text(b, "replacementId")),
        Json.text(b, "reason"));
    db.log(
        c,
        "MATERIAL",
        mid,
        m.path("rowVersion").asLong(),
        "REPLACEMENT",
        Json.text(b, "reason"),
        b,
        Json.text(m, "categoryId"));
    return b;
  }

  ObjectNode response(Ctx c, ObjectNode m) {
    m.put("requestId", c.trace());
    return m;
  }
}
