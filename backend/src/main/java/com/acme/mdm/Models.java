package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Models {
  final Db db;
  final Rules rules;
  final Auth auth;

  public Models(Db d, Rules r, Auth a) {
    db = d;
    rules = r;
    auth = a;
  }

  UUID uuid(String s) {
    try {
      return UUID.fromString(s);
    } catch (Exception e) {
      throw new Problem(400, "BAD_ID", "UUID格式错误");
    }
  }

  public ObjectNode category(Ctx c, String code) {
    var cat = db.one("select * from category where tenant_id=? and code=?", c.tid(), code);
    c.check("READ", code);
    return cat;
  }

  public ObjectNode categoryById(Ctx c, String id) {
    var cat = db.one("select * from category where tenant_id=? and id=?", c.tid(), uuid(id));
    c.check("READ", Json.text(cat, "code"));
    return cat;
  }

  public List<ObjectNode> categories(Ctx c) {
    c.check("READ", null);
    return db.list("select * from category where tenant_id=? order by name,id", c.tid()).stream()
        .filter(x -> c.allows(Json.text(x, "code")))
        .toList();
  }

  public JsonNode createCategory(Ctx c, JsonNode b) {
    c.check("DESIGN", null);
    String code = Json.text(b, "code");
    Problem.require(
        code.matches("[A-Z][A-Z0-9_]{0,79}") && c.allows(code),
        422,
        "CATEGORY_CODE",
        "类别code不合法或超出范围");
    UUID id = UUID.randomUUID();
    UUID parent = b.hasNonNull("parentId") ? uuid(b.path("parentId").asText()) : null;
    if (parent != null) categoryById(c, parent.toString());
    db.run(
        "insert into category(id,tenant_id,code,name,parent_id,approval_role,publish_role)"
            + " values(?,?,?,?,?,?,?)",
        id,
        c.tid(),
        code,
        b.path("name").asText(code),
        parent,
        b.path("approvalRole").asText("APPROVER"),
        b.path("publishRole").asText("PUBLISHER"));
    db.log(c, "CATEGORY", id.toString(), 1, "CREATE", "", b, id.toString());
    return category(c, code);
  }

  public JsonNode patchCategory(Ctx c, String id, JsonNode b, String v) {
    return db.tx(
        () -> {
          var x = categoryById(c, id);
          c.check("DESIGN", Json.text(x, "code"));
          Db.version(x, v);
          if (b.has("code"))
            Problem.require(
                b.path("code").equals(x.path("code")), 422, "CODE_IMMUTABLE", "启用类别code不可修改");
          UUID parent =
              b.has("parentId")
                  ? (b.hasNonNull("parentId") ? uuid(b.path("parentId").asText()) : null)
                  : (x.hasNonNull("parentId") ? uuid(Json.text(x, "parentId")) : null);
          if (parent != null) {
            var p = categoryById(c, parent.toString());
            Set<String> seen = new HashSet<>();
            while (p != null) {
              Problem.require(
                  !Json.text(p, "id").equals(id) && seen.add(Json.text(p, "id")),
                  422,
                  "CATEGORY_CYCLE",
                  "类别树不能循环");
              p = p.hasNonNull("parentId") ? categoryById(c, Json.text(p, "parentId")) : null;
            }
          }
          db.run(
              "update category set"
                  + " name=?,parent_id=?,active=?,approval_role=?,publish_role=?,row_version=row_version+1"
                  + " where tenant_id=? and id=? and row_version=?",
              b.path("name").asText(Json.text(x, "name")),
              parent,
              b.path("active").asBoolean(x.path("active").asBoolean()),
              b.path("approvalRole").asText(Json.text(x, "approvalRole")),
              b.path("publishRole").asText(Json.text(x, "publishRole")),
              c.tid(),
              uuid(id),
              x.path("rowVersion").asLong());
          db.log(
              c,
              "CATEGORY",
              id,
              x.path("rowVersion").asLong() + 1,
              "UPDATE",
              "",
              Json.object("before", x, "after", b),
              id);
          return categoryById(c, id);
        });
  }

  public JsonNode deleteCategory(Ctx c, String id, String v) {
    var x = categoryById(c, id);
    c.check("DESIGN", Json.text(x, "code"));
    Db.version(x, v);
    Problem.require(
        db.count(
                    "select count(*) from schema_version where tenant_id=? and category_id=?",
                    c.tid(),
                    uuid(id))
                == 0
            && db.count(
                    "select count(*) from material where tenant_id=? and category_id=?",
                    c.tid(),
                    uuid(id))
                == 0,
        409,
        "CONFIG_IN_USE",
        "已被引用类别只能停用");
    db.run("delete from category where tenant_id=? and id=?", c.tid(), uuid(id));
    return Json.object("deleted", true);
  }

  public ObjectNode schema(Ctx c, String id, boolean newUse) {
    var x = db.one("select * from schema_version where tenant_id=? and id=?", c.tid(), uuid(id));
    var cat = categoryById(c, Json.text(x, "categoryId"));
    if (newUse) {
      Problem.require(cat.path("active").asBoolean(), 422, "CATEGORY_INACTIVE", "类别已停用");
      Problem.require(
          Json.text(x, "status").equals("PUBLISHED"),
          422,
          "SCHEMA_RETIRED",
          "指定Schema版本不允许新建，请升级并重新校验");
      Problem.require(
          db.count(
                  "select count(*) from category where tenant_id=? and parent_id=? and active",
                  c.tid(),
                  uuid(Json.text(cat, "id")))
              == 0,
          422,
          "CATEGORY_NOT_LEAF",
          "只能在叶子类别新建物料");
    }
    return x;
  }

  public JsonNode currentSchema(Ctx c, String code, String id) {
    var cat = category(c, code);
    Problem.require(
        id != null || cat.hasNonNull("defaultSchemaId"), 422, "NO_PUBLISHED_SCHEMA", "请先发布此类别配置");
    var s = schema(c, id == null ? Json.text(cat, "defaultSchemaId") : id, false);
    Problem.require(
        s.path("categoryId").equals(cat.path("id")), 422, "SCHEMA_CATEGORY", "Schema与类别不一致");
    return s;
  }

  public List<ObjectNode> schemas(Ctx c, String code) {
    var cat = category(c, code);
    return db.list(
        "select * from schema_version where tenant_id=? and category_id=? order by version desc",
        c.tid(),
        uuid(Json.text(cat, "id")));
  }

  public ObjectNode enrich(Ctx c, JsonNode src, int version) {
    Problem.require(
        !src.has("_syntaxOnly") && !src.has("_resolvedReferences") && !src.has("_observeExisting"),
        422,
        "CONFIG_RESERVED_FIELD",
        "内部执行标记不可由配置写入");
    ObjectNode b = (ObjectNode) src.deepCopy();
    b.put("version", version);
    ObjectNode units = Json.obj();
    for (var u :
        db.list(
            "select * from metadata where tenant_id=? and kind='UNIT' and active order by version",
            c.tid())) units.set(Json.text(u, "code"), u.path("data"));
    if (b.has("units"))
      b.path("units").fields().forEachRemaining(e -> units.set(e.getKey(), e.getValue()));
    b.set("units", units);
    ArrayNode attrs = Json.arr();
    for (var a : b.path("attributes")) {
      ObjectNode f = (ObjectNode) a.deepCopy();
      if (a.hasNonNull("definitionId")) {
        var def =
            db.one(
                "select * from metadata where tenant_id=? and id=? and kind='ATTRIBUTE'",
                c.tid(),
                uuid(a.path("definitionId").asText()));
        Problem.require(def.path("active").asBoolean(), 422, "ATTRIBUTE_INACTIVE", "属性已停用");
        ObjectNode data = (ObjectNode) def.path("data").deepCopy();
        String type = data.path("type").asText();
        if (a.has("type"))
          Problem.require(
              type.equals(a.path("type").asText()), 422, "ATTRIBUTE_OVERRIDE", "复用属性不允许改变类型");
        if (a.has("unit"))
          Problem.require(
              a.path("unit").equals(data.path("unit")), 422, "ATTRIBUTE_OVERRIDE", "复用属性不能改变标准单位");
        if (a.has("min") && data.has("min"))
          Problem.require(
              Json.decimal(a.get("min")).compareTo(Json.decimal(data.get("min"))) >= 0,
              422,
              "ATTRIBUTE_OVERRIDE",
              "范围只能收紧");
        if (a.has("max") && data.has("max"))
          Problem.require(
              Json.decimal(a.get("max")).compareTo(Json.decimal(data.get("max"))) <= 0,
              422,
              "ATTRIBUTE_OVERRIDE",
              "范围只能收紧");
        f = data;
        f.put("code", Json.text(def, "code"));
        f.put("definitionId", Json.text(def, "id"));
        f.put("definitionVersion", def.path("version").asInt());
        for (String key :
            List.of(
                "label",
                "group",
                "order",
                "required",
                "min",
                "max",
                "maxLength",
                "visibleWhen",
                "requiredWhen",
                "searchable",
                "sortable")) if (a.has(key)) f.set(key, a.get(key));
      }
      if (f.has("dictionaryCode")) {
        var dict =
            db.one(
                "select * from metadata where tenant_id=? and kind='DICTIONARY' and code=? and"
                    + " active order by version desc limit 1",
                c.tid(),
                f.path("dictionaryCode").asText());
        f.set("options", dict.path("data").path("items"));
        f.put("dictionaryVersionId", Json.text(dict, "id"));
      }
      attrs.add(f);
    }
    b.set("attributes", attrs);
    ObjectNode ds = Json.object("type", "object", "additionalProperties", false),
        props = Json.obj();
    ArrayNode required = Json.arr();
    for (var f : attrs) {
      String type = f.path("type").asText();
      ObjectNode p = Json.obj();
      if (type.equals("INTEGER") || type.equals("DECIMAL")) {
        p =
            Json.object(
                "type",
                "object",
                "properties",
                Json.object(
                    "value",
                    Json.object("type", type.equals("INTEGER") ? "integer" : "number"),
                    "unit",
                    Json.object("type", "string")),
                "required",
                Json.arr().add("value"),
                "additionalProperties",
                false);
      } else if (type.equals("REFERENCE")) {
        p =
            Json.object(
                "type",
                "object",
                "properties",
                Json.object(
                    "type",
                    Json.object("const", f.path("referenceType")),
                    "id",
                    Json.object("type", "string")),
                "required",
                Json.arr().add("type").add("id"),
                "additionalProperties",
                false);
      } else p.put("type", type.equals("BOOLEAN") ? "boolean" : "string");
      if (!f.path("required").asBoolean())
        p.set("type", Json.arr().add(p.path("type").asText()).add("null"));
      props.set(f.path("code").asText(), p);
      if (f.path("required").asBoolean()) required.add(f.path("code").asText());
    }
    ds.set("properties", props);
    ds.set("required", required);
    b.set("dataSchema", ds);
    if (!b.has("uiSchema")) b.set("uiSchema", Json.object("order", attrs));
    return b;
  }

  public JsonNode createSchema(Ctx c, String code, JsonNode body) {
    var cat = category(c, code);
    c.check("DESIGN", code);
    UUID id = UUID.randomUUID();
    db.jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        c.tenant() + ":schema:" + code);
    int version =
        (int)
            db.count(
                "select coalesce(max(version),0)+1 from schema_version where tenant_id=? and"
                    + " category_id=?",
                c.tid(),
                uuid(Json.text(cat, "id")));
    var b = enrich(c, body.path("bundle"), version);
    rules.compile(b);
    db.run(
        "insert into schema_version(id,tenant_id,category_id,version,bundle,created_by)"
            + " values(?,?,?,?,?::jsonb,?)",
        id,
        c.tid(),
        uuid(Json.text(cat, "id")),
        version,
        Json.str(b),
        c.uid());
    db.log(c, "SCHEMA", id.toString(), 1, "CREATE", "", b, Json.text(cat, "id"));
    return schema(c, id.toString(), false);
  }

  public JsonNode patchSchema(Ctx c, String id, JsonNode body, String v) {
    Problem.require(
        db.count(
                "select count(*) from release_package where tenant_id=? and schema_id=? and"
                    + " status='REVIEW'",
                c.tid(),
                uuid(id))
            == 0,
        409,
        "CONFIGURATION_IN_REVIEW",
        "Schema在待审发布包中，须先撤回再编辑");
    var x = schema(c, id, false);
    var cat = categoryById(c, Json.text(x, "categoryId"));
    c.check("DESIGN", Json.text(cat, "code"));
    Db.version(x, v);
    Problem.require(
        Json.text(x, "status").equals("DRAFT") && !x.path("everPublished").asBoolean(),
        409,
        "IMMUTABLE_SCHEMA",
        "仅配置草稿可编辑，发布后请创建新版本");
    var b = enrich(c, body.path("bundle"), x.path("version").asInt());
    rules.compile(b);
    Problem.require(
        db.run(
                "update schema_version set bundle=?::jsonb,row_version=row_version+1 where"
                    + " tenant_id=? and id=? and row_version=?",
                Json.str(b),
                c.tid(),
                uuid(id),
                x.path("rowVersion").asLong())
            == 1,
        409,
        "VERSION_CONFLICT",
        "配置版本冲突");
    return schema(c, id, false);
  }

  public JsonNode regress(Ctx c, JsonNode b) {
    rules.compile(b);
    ArrayNode results = Json.arr();
    for (var sample : b.path("samples")) {
      String expected = sample.path("expectedError").asText("");
      try {
        var attrs = rules.normalize(c, b, sample.path("attributes"), true, true);
        Problem.require(expected.isEmpty(), 422, "SAMPLE_EXPECTATION", "样本期望失败但校验通过");
        results.add(Json.object("name", sample.path("name"), "passed", true, "attributes", attrs));
      } catch (Problem p) {
        boolean ok =
            !expected.isEmpty()
                && (p.code.equals(expected) || p.errors.toString().contains(expected));
        Problem.require(
            ok,
            422,
            "SAMPLE_FAILED",
            "配置样本回归失败：" + sample.path("name").asText() + " " + p.getMessage());
        results.add(
            Json.object("name", sample.path("name"), "passed", true, "expectedError", expected));
      }
    }
    return results;
  }

  public JsonNode schemaAction(Ctx c, String id, String action, JsonNode body, String v) {
    var x = schema(c, id, false);
    var cat = categoryById(c, Json.text(x, "categoryId"));
    String code = Json.text(cat, "code"), state = Json.text(x, "status");
    Db.version(x, v);
    String next = state;
    switch (action) {
      case "submit":
        c.check("DESIGN", code);
        Problem.require(state.equals("DRAFT"), 409, "STATE_CONFLICT", "配置不是草稿");
        auth.availableApprover(c, code, Json.text(cat, "publishRole"));
        ObjectNode frozen = enrich(c, x.path("bundle"), x.path("version").asInt());
        regress(c, frozen);
        db.run(
            "update schema_version set bundle=?::jsonb,submitted_by=? where tenant_id=? and id=?",
            Json.str(frozen),
            c.uid(),
            c.tid(),
            uuid(id));
        next = "REVIEW";
        break;
      case "approve":
        c.check("PUBLISH", code);
        auth.role(c, Json.text(cat, "publishRole"));
        Problem.require(state.equals("REVIEW"), 409, "STATE_CONFLICT", "配置不在待审状态");
        Problem.require(
            !c.user().equals(Json.text(x, "submittedBy")), 403, "SELF_APPROVAL", "不能审核自己的配置");
        regress(c, x.path("bundle"));
        next = "PUBLISHED";
        db.run(
            "update category set default_schema_id=?,row_version=row_version+1 where tenant_id=?"
                + " and id=?",
            uuid(id),
            c.tid(),
            uuid(Json.text(cat, "id")));
        break;
      case "reject":
        c.check("PUBLISH", code);
        auth.role(c, Json.text(cat, "publishRole"));
        Problem.require(
            state.equals("REVIEW") && !c.user().equals(Json.text(x, "submittedBy")),
            403,
            "SELF_APPROVAL",
            "须由其他审批人处理待审配置");
        Problem.require(!Json.text(body, "reason").isBlank(), 422, "REASON_REQUIRED", "驳回须填写原因");
        next = x.path("everPublished").asBoolean() ? "RETIRED" : "DRAFT";
        break;
      case "withdraw":
        c.check("DESIGN", code);
        Problem.require(
            state.equals("REVIEW") && c.user().equals(Json.text(x, "submittedBy")),
            403,
            "NOT_SUBMITTER",
            "只有提交人可撤回");
        next = x.path("everPublished").asBoolean() ? "RETIRED" : "DRAFT";
        break;
      case "retire":
        c.check("PUBLISH", code);
        Problem.require(state.equals("PUBLISHED"), 409, "STATE_CONFLICT", "只能停用已发布版本");
        next = "RETIRED";
        db.run(
            "update category set default_schema_id=null,row_version=row_version+1 where tenant_id=?"
                + " and default_schema_id=?",
            c.tid(),
            uuid(id));
        break;
      case "reactivate":
        c.check("DESIGN", code);
        Problem.require(state.equals("RETIRED"), 409, "STATE_CONFLICT", "仅停用版本可申请重新启用");
        auth.availableApprover(c, code, Json.text(cat, "publishRole"));
        regress(c, x.path("bundle"));
        db.run(
            "update schema_version set submitted_by=? where tenant_id=? and id=?",
            c.uid(),
            c.tid(),
            uuid(id));
        next = "REVIEW";
        break;
      default:
        throw new Problem(400, "BAD_ACTION", "配置动作无效");
    }
    Problem.require(
        db.run(
                "update schema_version set status=?,row_version=row_version+1 where tenant_id=? and"
                    + " id=? and row_version=?",
                next,
                c.tid(),
                uuid(id),
                x.path("rowVersion").asLong())
            == 1,
        409,
        "VERSION_CONFLICT",
        "配置版本已变化");
    db.run(
        "insert into approval_action(id,tenant_id,object_id,object_type,actor,action,comment)"
            + " values(?,?,?,'SCHEMA',?,?,?)",
        UUID.randomUUID(),
        c.tid(),
        uuid(id),
        c.uid(),
        action,
        Json.text(body, "reason"));
    db.log(
        c,
        "SCHEMA",
        id,
        x.path("rowVersion").asLong() + 1,
        action,
        Json.text(body, "reason"),
        Json.object("before", state, "after", next),
        Json.text(cat, "id"));
    return schema(c, id, false);
  }

  public JsonNode deleteSchema(Ctx c, String id, String v) {
    var x = schema(c, id, false);
    var cat = categoryById(c, Json.text(x, "categoryId"));
    c.check("DESIGN", Json.text(cat, "code"));
    Db.version(x, v);
    Problem.require(
        Json.text(x, "status").equals("DRAFT")
            && !x.path("everPublished").asBoolean()
            && db.count(
                    "select count(*) from material where tenant_id=? and schema_version_id=?",
                    c.tid(),
                    uuid(id))
                == 0,
        409,
        "CONFIG_IN_USE",
        "只能删除未引用且从未发布的配置草稿");
    db.run("delete from schema_version where tenant_id=? and id=?", c.tid(), uuid(id));
    db.log(
        c,
        "SCHEMA",
        id,
        x.path("rowVersion").asLong(),
        "DELETE_DRAFT",
        "",
        x,
        Json.text(cat, "id"));
    return Json.object("deleted", true);
  }

  public JsonNode impact(Ctx c, String id) {
    var x = schema(c, id, false);
    var cat = categoryById(c, Json.text(x, "categoryId"));
    JsonNode old =
        cat.hasNonNull("defaultSchemaId")
            ? schema(c, Json.text(cat, "defaultSchemaId"), false).path("bundle")
            : Json.obj();
    return Json.object(
        "existingMaterialCount",
        db.count(
            "select count(*) from material where tenant_id=? and category_id=?",
            c.tid(),
            uuid(Json.text(cat, "id"))),
        "before",
        old,
        "after",
        x.path("bundle"),
        "warning",
        "新增必填、类型/范围/单位/枚举变化不自动修改历史物料");
  }

  public List<ObjectNode> metadata(Ctx c) {
    c.check("READ", null);
    return db.list(
        "select * from metadata where tenant_id=? order by kind,code,version desc", c.tid());
  }

  public JsonNode createMeta(Ctx c, JsonNode b) {
    c.check("DESIGN", null);
    String kind = Json.text(b, "kind");
    Problem.require(
        Set.of("ATTRIBUTE", "DICTIONARY", "UNIT").contains(kind), 422, "META_KIND", "元数据类型无效");
    String code = Json.text(b, "code");
    Problem.require(!code.isBlank(), 422, "CODE_REQUIRED", "code必填");
    UUID id = UUID.randomUUID();
    db.jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        c.tenant() + ":meta:" + kind + ":" + code);
    long ver =
        db.count(
            "select coalesce(max(version),0)+1 from metadata where tenant_id=? and kind=? and"
                + " code=?",
            c.tid(),
            kind,
            code);
    db.run(
        "insert into metadata(id,tenant_id,kind,code,version,data) values(?,?,?,?,?,?::jsonb)",
        id,
        c.tid(),
        kind,
        code,
        ver,
        Json.str(b.path("data")));
    return db.one("select * from metadata where tenant_id=? and id=?", c.tid(), id);
  }

  public JsonNode patchMeta(Ctx c, String id, JsonNode b, String v) {
    c.check("DESIGN", null);
    var x = db.one("select * from metadata where tenant_id=? and id=?", c.tid(), uuid(id));
    Db.version(x, v);
    if (b.has("data"))
      Problem.require(
          db.count(
                  "select count(*) from schema_version where tenant_id=? and status in"
                      + " ('PUBLISHED','RETIRED') and bundle::text like ?",
                  c.tid(),
                  "%" + id + "%")
              == 0,
          409,
          "CONFIG_IN_USE",
          "已被发布包引用，请创建元数据新版本");
    Problem.require(
        db.run(
                "update metadata set active=?,data=?::jsonb,row_version=row_version+1 where"
                    + " tenant_id=? and id=? and row_version=?",
                b.path("active").asBoolean(x.path("active").asBoolean()),
                Json.str(b.has("data") ? b.get("data") : x.get("data")),
                c.tid(),
                uuid(id),
                x.path("rowVersion").asLong())
            == 1,
        409,
        "VERSION_CONFLICT",
        "元数据版本已变化");
    return db.one("select * from metadata where tenant_id=? and id=?", c.tid(), uuid(id));
  }

  public JsonNode reference(Ctx c, JsonNode b, String v) {
    c.check("ADMIN", null);
    String type = Json.text(b, "type"), id = Json.text(b, "id");
    Problem.require(!id.isBlank() && !type.isBlank(), 422, "REFERENCE_ID", "引用type和id必填");
    var x =
        db.maybe(
            "select * from reference_entity where tenant_id=? and type=? and id=?",
            c.tid(),
            type,
            id);
    if (x == null) {
      db.run(
          "insert into reference_entity(tenant_id,type,id,name,data) values(?,?,?,?,?::jsonb)",
          c.tid(),
          type,
          id,
          b.path("name").asText(id),
          Json.str(b.path("data")));
    } else {
      Db.version(x, v);
      Problem.require(
          db.run(
                  "update reference_entity set"
                      + " name=?,active=?,data=?::jsonb,row_version=row_version+1 where tenant_id=?"
                      + " and type=? and id=? and row_version=?",
                  b.path("name").asText(Json.text(x, "name")),
                  b.path("active").asBoolean(x.path("active").asBoolean()),
                  Json.str(b.has("data") ? b.get("data") : x.get("data")),
                  c.tid(),
                  type,
                  id,
                  x.path("rowVersion").asLong())
              == 1,
          409,
          "VERSION_CONFLICT",
          "引用版本冲突");
    }
    return db.one(
        "select * from reference_entity where tenant_id=? and type=? and id=?", c.tid(), type, id);
  }
}
