package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class ReleasePackages {
  final Db db;
  final Models models;
  final Rules rules;
  final Auth auth;
  final SourceMetadata sources;
  final DatasetEngine datasets;

  public ReleasePackages(Db d, Models m, Rules r, Auth a, SourceMetadata s, DatasetEngine e) {
    db = d;
    models = m;
    rules = r;
    auth = a;
    sources = s;
    datasets = e;
  }

  String action(String kind) {
    return switch (kind) {
      case "DATASET" -> "DATASET_DESIGN";
      case "MAPPING" -> "MAPPING_DESIGN";
      case "IDENTITY" -> "IDENTITY_DESIGN";
      case "CODE_RULE" -> "RULE_DESIGN";
      default -> throw new Problem(422, "CONFIG_KIND", "配置类型无效");
    };
  }

  public ObjectNode config(Ctx c, String key, String kind) {
    var n =
        db.one(
            "select * from numbering_config where tenant_id=? and id=?",
            c.tid(),
            SourceMetadata.id(key));
    Problem.require(n.path("kind").asText().equals(kind), 422, "CONFIG_KIND", "依赖版本类型不符");
    if (n.hasNonNull("sourceSystemId")) sources.scope(c, n.path("sourceSystemId").asText());
    if (n.hasNonNull("categoryId")) models.categoryById(c, n.path("categoryId").asText());
    return n;
  }

  public List<ObjectNode> configs(Ctx c, String kind) {
    c.check("READ", null);
    return db
        .list(
            "select * from numbering_config where tenant_id=? and kind=? order by code,version_no"
                + " desc",
            c.tid(),
            kind)
        .stream()
        .filter(
            x -> {
              try {
                config(c, x.path("id").asText(), kind);
                return true;
              } catch (Problem p) {
                return false;
              }
            })
        .toList();
  }

  public JsonNode saveConfig(Ctx c, String kind, JsonNode b, String key, String expected) {
    c.check(action(kind), null);
    ObjectNode old = key == null ? null : config(c, key, kind);
    if (old != null)
      Problem.require(
          db.count(
                  "select count(*) from release_package where tenant_id=? and status='REVIEW' and ?"
                      + " in (dataset_id,mapping_id,identity_id,code_rule_id)",
                  c.tid(),
                  SourceMetadata.id(key))
              == 0,
          409,
          "CONFIGURATION_IN_REVIEW",
          "配置在待审发布包中，须先撤回再编辑");
    if (old != null) {
      Db.version(old, expected);
      Problem.require(
          old.path("status").asText().equals("DRAFT") && !old.path("everPublished").asBoolean(),
          409,
          "IMMUTABLE_CONFIGURATION",
          "已提交或发布配置不可原地编辑，请新建版本");
    }
    JsonNode def = b.path("definition");
    Problem.require(def.isObject(), 422, "CONFIG_DEFINITION", "请提供配置定义");
    String
        sid =
            b.path("sourceSystemId").asText(old == null ? "" : old.path("sourceSystemId").asText()),
        category = b.path("categoryCode").asText();
    UUID categoryId = null;
    if (!category.isEmpty()) {
      categoryId = SourceMetadata.id(models.category(c, category).path("id").asText());
      c.check(action(kind), category);
    } else if (old != null && old.hasNonNull("categoryId"))
      categoryId = SourceMetadata.id(old.path("categoryId").asText());
    if (!sid.isBlank()) sources.scope(c, sid);
    if (kind.equals("DATASET"))
      Problem.require(!sid.isBlank(), 422, "SOURCE_REQUIRED", "Dataset必须关联来源系统");
    UUID id = key == null ? UUID.randomUUID() : SourceMetadata.id(key);
    if (old == null) {
      String code = b.path("code").asText();
      Problem.require(code.matches("[A-Za-z][A-Za-z0-9_-]{0,79}"), 422, "CONFIG_CODE", "配置code无效");
      db.jdbc.queryForObject(
          "select pg_advisory_xact_lock(hashtextextended(?,0))",
          Object.class,
          c.tenant() + kind + code);
      int version =
          (int)
              db.count(
                  "select coalesce(max(version_no),0)+1 from numbering_config where tenant_id=? and"
                      + " kind=? and code=?",
                  c.tid(),
                  kind,
                  code);
      db.run(
          "insert into"
              + " numbering_config(id,tenant_id,kind,code,version_no,source_system_id,category_id,definition,created_by)"
              + " values(?,?,?,?,?,?,?,?::jsonb,?)",
          id,
          c.tid(),
          kind,
          code,
          version,
          sid.isBlank() ? null : SourceMetadata.id(sid),
          categoryId,
          Json.str(def),
          c.uid());
    } else
      Problem.require(
          db.run(
                  "update numbering_config set definition=?::jsonb,row_version=row_version+1 where"
                      + " tenant_id=? and id=? and row_version=?",
                  Json.str(def),
                  c.tid(),
                  id,
                  old.path("rowVersion").asLong())
              == 1,
          409,
          "VERSION_CONFLICT",
          "配置已变更");
    var n = config(c, id.toString(), kind);
    if (kind.equals("DATASET")) datasets.plan(c, n);
    db.log(
        c,
        "NUMBERING_CONFIG",
        id.toString(),
        n.path("rowVersion").asLong(),
        old == null ? "CREATE" : "UPDATE",
        "配置草稿",
        Json.object("kind", kind),
        categoryId == null ? null : categoryId.toString());
    return n;
  }

  public ObjectNode release(Ctx c, String id) {
    var n =
        db.one(
            "select * from release_package where tenant_id=? and id=?",
            c.tid(),
            SourceMetadata.id(id));
    sources.scope(c, n.path("sourceSystemId").asText());
    models.categoryById(c, n.path("categoryId").asText());
    return n;
  }

  public List<ObjectNode> list(Ctx c) {
    c.check("READ", null);
    return db
        .list(
            "select * from release_package where tenant_id=? order by code,version_no desc",
            c.tid())
        .stream()
        .filter(
            x -> {
              try {
                release(c, x.path("id").asText());
                return true;
              } catch (Problem p) {
                return false;
              }
            })
        .toList();
  }

  public JsonNode create(Ctx c, JsonNode b) {
    String category = b.path("categoryCode").asText();
    c.check("DESIGN", category);
    var cat = models.category(c, category);
    var ds = config(c, b.path("datasetVersionId").asText(), "DATASET");
    sources.scope(c, ds.path("sourceSystemId").asText());
    for (var pair :
        Map.of(
                "mappingVersionId",
                "MAPPING",
                "identityDefinitionVersionId",
                "IDENTITY",
                "codeRuleVersionId",
                "CODE_RULE")
            .entrySet()) config(c, b.path(pair.getKey()).asText(), pair.getValue());
    var schema = models.schema(c, b.path("schemaVersionId").asText(), false);
    Problem.require(
        schema.path("categoryId").equals(cat.path("id")), 422, "SCHEMA_CATEGORY", "Schema类别不符");
    var id = UUID.randomUUID();
    String code = b.path("code").asText(ds.path("code").asText());
    db.jdbc.queryForObject(
        "select pg_advisory_xact_lock(hashtextextended(?,0))",
        Object.class,
        c.tenant() + ":release:" + code);
    int version =
        (int)
            db.count(
                "select coalesce(max(version_no),0)+1 from release_package where tenant_id=? and"
                    + " code=?",
                c.tid(),
                code);
    db.run(
        "insert into"
            + " release_package(id,tenant_id,code,version_no,source_system_id,category_id,dataset_id,schema_id,mapping_id,identity_id,code_rule_id,definition,created_by)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?)",
        id,
        c.tid(),
        code,
        version,
        SourceMetadata.id(ds.path("sourceSystemId").asText()),
        SourceMetadata.id(cat.path("id").asText()),
        SourceMetadata.id(ds.path("id").asText()),
        SourceMetadata.id(schema.path("id").asText()),
        SourceMetadata.id(b.path("mappingVersionId").asText()),
        SourceMetadata.id(b.path("identityDefinitionVersionId").asText()),
        SourceMetadata.id(b.path("codeRuleVersionId").asText()),
        Json.str(b.path("definition")),
        c.uid());
    return release(c, id.toString());
  }

  public ObjectNode dependency(Ctx c, JsonNode rel) {
    var ds = config(c, rel.path("datasetId").asText(), "DATASET");
    var mp = config(c, rel.path("mappingId").asText(), "MAPPING");
    var identity = config(c, rel.path("identityId").asText(), "IDENTITY");
    var code = config(c, rel.path("codeRuleId").asText(), "CODE_RULE");
    var schema = models.schema(c, rel.path("schemaId").asText(), false);
    var plan = datasets.plan(c, ds);
    ObjectNode objects = Json.obj();
    for (var object : plan.objects().values()) {
      objects.set(
          object.path("id").asText(),
          Json.object(
              "definition",
              object.path("definition"),
              "versionNo",
              object.path("versionNo"),
              "objectName",
              object.path("objectName"),
              "structureHash",
              object.path("structureHash")));
    }
    for (var conf : List.of(ds, mp, identity, code)) {
      Problem.require(
          !conf.path("status").asText().equals("RETIRED"), 422, "CONFIG_RETIRED", "依赖已停用");
      if (conf.hasNonNull("categoryId"))
        Problem.require(
            conf.path("categoryId").equals(rel.path("categoryId")),
            422,
            "CONFIG_CATEGORY",
            "配置类别不一致");
      if (conf.hasNonNull("sourceSystemId"))
        Problem.require(
            conf.path("sourceSystemId").equals(rel.path("sourceSystemId")),
            422,
            "SOURCE_SCOPE",
            "配置来源不一致");
    }
    Problem.require(
        !schema.path("status").asText().equals("RETIRED"), 422, "SCHEMA_RETIRED", "Schema已停用");
    ObjectNode effective = (ObjectNode) schema.path("bundle").deepCopy();
    effective.set("codeRule", code.path("definition"));
    if (code.path("definition").has("parseRule"))
      effective.set("parseRule", code.path("definition").path("parseRule"));
    validateMapping(ds, mp, identity, code, effective);
    return Json.object(
        "dataset",
        ds.path("definition"),
        "datasetCode",
        ds.path("code"),
        "datasetVersion",
        ds.path("versionNo"),
        "objects",
        objects,
        "referenceDatasets",
        referenceDependencies(c, effective, objects),
        "lookupDatasets",
        lookupDependencies(c, ds, mp, objects),
        "schema",
        effective,
        "schemaVersion",
        schema.path("version"),
        "mapping",
        mp.path("definition"),
        "mappingVersion",
        mp.path("versionNo"),
        "identity",
        identity.path("definition"),
        "identityVersion",
        identity.path("versionNo"),
        "codeRule",
        code.path("definition"),
        "codeRuleVersion",
        code.path("versionNo"));
  }

  ObjectNode lookupDependencies(Ctx c, JsonNode root, JsonNode mapping, ObjectNode objects) {
    ObjectNode result = Json.obj();
    Set<String> names = new HashSet<>();
    root.path("definition").path("output").fieldNames().forEachRemaining(names::add);
    for (var lookup : mapping.path("definition").path("lookups")) {
      Problem.require(
          root.path("definition").path("output").has(lookup.path("keySource").asText()),
          422,
          "MAPPING_ERROR",
          "Lookup来源键未在主Dataset输出中声明");
      var ds = config(c, lookup.path("datasetVersionId").asText(), "DATASET");
      Problem.require(
          !ds.path("status").asText().equals("RETIRED"),
          422,
          "CONFIG_RETIRED",
          "Lookup Dataset已停用");
      var plan = datasets.plan(c, ds);
      Problem.require(
          lookup.path("projection").isObject() && !lookup.path("projection").isEmpty(),
          422,
          "MAPPING_ERROR",
          "Lookup须配置投影字段");
      lookup
          .path("projection")
          .fields()
          .forEachRemaining(
              entry -> {
                Problem.require(
                    ds.path("definition").path("output").has(entry.getValue().asText())
                        && names.add(entry.getKey()),
                    422,
                    "MAPPING_ERROR",
                    "Lookup投影来源未知或输出重名");
              });
      result.set(ds.path("id").asText(), ds);
      for (var o : plan.objects().values())
        objects.set(
            o.path("id").asText(),
            Json.object(
                "definition",
                o.path("definition"),
                "versionNo",
                o.path("versionNo"),
                "objectName",
                o.path("objectName"),
                "structureHash",
                o.path("structureHash")));
    }
    return result;
  }

  ObjectNode expandLookups(Ctx c, JsonNode deps, JsonNode source) {
    ObjectNode expanded = (ObjectNode) source.deepCopy();
    for (var lookup : deps.path("mapping").path("lookups")) {
      String key = source.path(lookup.path("keySource").asText()).asText();
      Problem.require(!key.isBlank(), 422, "SOURCE_INCOMPLETE", "Lookup引用键缺失");
      var ds = deps.path("lookupDatasets").path(lookup.path("datasetVersionId").asText());
      Problem.require(ds.isObject(), 422, "MAPPING_ERROR", "Lookup依赖未固定");
      var read = datasets.preview(c, ds, key, null);
      Problem.require(
          read.path("candidateEligible").asBoolean(true),
          422,
          "REFERENCE_OWNERSHIP",
          "Lookup记录归属不符");
      lookup
          .path("projection")
          .fields()
          .forEachRemaining(
              entry ->
                  expanded.set(
                      entry.getKey(), read.path("output").path(entry.getValue().asText())));
    }
    return expanded;
  }

  ObjectNode referenceDependencies(Ctx c, JsonNode bundle, ObjectNode objects) {
    ObjectNode references = Json.obj();
    for (var f : rules.fields(bundle).values())
      if (f.path("type").asText().equals("REFERENCE")) {
        if (f.path("referenceTargetKind").asText().equals("METADATA_CATALOG")) continue;
        String rid = f.path("referenceSource").path("datasetVersionId").asText();
        Problem.require(
            !rid.isBlank(),
            422,
            "REFERENCE_SOURCE_REQUIRED",
            "ERP业务Reference必须声明来源Dataset版本：" + f.path("code").asText());
        var ds = config(c, rid, "DATASET");
        var plan = datasets.plan(c, ds);
        String active = f.path("referenceSource").path("activeField").asText();
        Problem.require(
            !active.isBlank() && ds.path("definition").path("output").has(active),
            422,
            "REFERENCE_SOURCE_REQUIRED",
            "Reference须声明来源有效性输出字段");
        Problem.require(
            !ds.path("status").asText().equals("RETIRED"),
            422,
            "REFERENCE_INACTIVE",
            "Reference来源Dataset已停用");
        references.set(
            rid,
            Json.object(
                "id",
                rid,
                "code",
                ds.path("code"),
                "sourceSystemId",
                ds.path("sourceSystemId"),
                "definition",
                ds.path("definition"),
                "versionNo",
                ds.path("versionNo")));
        for (var o : plan.objects().values())
          objects.set(
              o.path("id").asText(),
              Json.object(
                  "definition",
                  o.path("definition"),
                  "versionNo",
                  o.path("versionNo"),
                  "objectName",
                  o.path("objectName"),
                  "structureHash",
                  o.path("structureHash")));
      }
    return references;
  }

  ObjectNode resolveReferences(Ctx c, JsonNode deps, JsonNode mapped, boolean observation) {
    ObjectNode checked = Json.obj();
    for (var f : rules.fields(deps.path("schema")).values())
      if (f.path("type").asText().equals("REFERENCE") && f.has("referenceSource")) {
        String field = f.path("code").asText();
        var value = mapped.path(field);
        if (value.isMissingNode() || value.isNull()) continue;
        String id = value.path("id").asText(), type = value.path("type").asText();
        try {
          var ref = f.path("referenceSource");
          var ds = deps.path("referenceDatasets").path(ref.path("datasetVersionId").asText());
          Problem.require(ds.isObject(), 422, "REFERENCE_OWNERSHIP", "引用来源不属于该发布组合");
          var read = datasets.preview(c, ds, id, null);
          Problem.require(
              read.path("candidateEligible").asBoolean(true),
              422,
              "REFERENCE_OWNERSHIP",
              "ERP引用记录不在配置的来源范围");
          boolean active =
              read.path("output")
                  .path(ref.path("activeField").asText())
                  .equals(ref.has("activeValue") ? ref.path("activeValue") : BooleanNode.TRUE);
          if (ref.path("requireIssuedNumber").asBoolean())
            active = active && !read.path("existingMaterialNo").asText("").isBlank();
          if (!observation)
            active =
                active
                    && sources
                        .system(c, ds.path("sourceSystemId").asText())
                        .path("status")
                        .asText()
                        .equals("ACTIVE");
          checked.set(
              type + ":" + id,
              Json.object(
                  "type",
                  type,
                  "id",
                  id,
                  "active",
                  active,
                  "datasetVersionId",
                  ds.path("id"),
                  "sourceRecordKey",
                  id,
                  "sourceVersion",
                  read.path("sourceVersion"),
                  "sourceValues",
                  read.path("output")));
        } catch (Problem p) {
          if (p.status == 403 || p.status >= 500) throw p;
          throw new Problem(
              422,
              "VALIDATION_ERROR",
              "ERP Reference不可用",
              Json.arr()
                  .add(
                      Json.object(
                          "fieldPath",
                          "/attributes/" + field,
                          "code",
                          "REFERENCE_OWNERSHIP",
                          "message",
                          "ERP引用不存在、归属不符或记录无效",
                          "sourceRecordKey",
                          id,
                          "sourceValue",
                          value,
                          "suggestion",
                          "请在ERP检查引用记录与有效性",
                          "ruleVersion",
                          deps.path("schemaVersion"))));
        }
      }
    return checked;
  }

  void validateMapping(
      JsonNode ds, JsonNode mapping, JsonNode identity, JsonNode code, JsonNode bundle) {
    rules.compile(bundle);
    var fs = rules.fields(bundle);
    Set<String> sourceFields = new HashSet<>();
    ds.path("definition").path("output").fieldNames().forEachRemaining(sourceFields::add);
    for (var l : mapping.path("definition").path("lookups"))
      l.path("projection").fieldNames().forEachRemaining(sourceFields::add);
    Set<String> covered = new HashSet<>();
    for (var f : mapping.path("definition").path("fields")) {
      String target = f.path("target").asText();
      Problem.require(
          fs.containsKey(target) && !fs.get(target).has("derived") && covered.add(target),
          422,
          "MAPPING_ERROR",
          "映射属性未知、重复或为派生属性：" + target);
      if (f.has("source"))
        Problem.require(
            sourceFields.contains(f.path("source").asText()),
            422,
            "MAPPING_ERROR",
            "来源输出不存在：" + f.path("source").asText());
      Problem.require(
          f.has("source") || f.has("constant") || f.has("expression"),
          422,
          "MAPPING_ERROR",
          "映射缺来源/常量/组合表达式");
    }
    for (var f : fs.values())
      if (f.path("required").asBoolean() && !f.has("default") && !f.has("derived"))
        Problem.require(
            covered.contains(f.path("code").asText()),
            422,
            "MAPPING_ERROR",
            "缺少必填属性映射：" + f.path("code").asText());
    var fields = identity.path("definition").path("fields");
    Problem.require(
        fields.isArray() && !fields.isEmpty(), 422, "IDENTITY_EMPTY", "Identity必须配置属性及样本");
    Set<String> seen = new HashSet<>();
    for (var f : fields) {
      String key = f.path("field").asText();
      Problem.require(fs.containsKey(key) && seen.add(key), 422, "IDENTITY_FIELD", "身份属性未知或重复");
    }
    var segments = code.path("definition").path("segments");
    Problem.require(
        segments.isArray() && !segments.isEmpty(), 422, "RULE_NOT_CONFIGURED", "未配置有效Code Rule");
    for (var s : segments) {
      Problem.require(!s.has("ruleId"), 422, "CODE_RULE", "序列作用域由当前Code Rule版本固定，不允许覆盖");
      if (s.has("field")) {
        String key = s.path("field").asText().split("\\.")[0];
        Problem.require(
            fs.get(key).path("canCode").asBoolean(true), 422, "CODE_FIELD", "属性不允许参与编码");
      }
    }
  }

  public ObjectNode map(Ctx c, JsonNode deps, JsonNode output) {
    ObjectNode attrs = Json.obj();
    var fs = rules.fields(deps.path("schema"));
    for (var m : deps.path("mapping").path("fields")) {
      String target = m.path("target").asText();
      JsonNode value =
          m.has("constant")
              ? m.path("constant")
              : m.has("expression")
                  ? rules.eval(m.path("expression"), output)
                  : output.path(m.path("source").asText());
      if (m.has("lookup") && !value.isNull() && !value.isMissingNode()) {
        value = m.path("lookup").path(value.asText());
        Problem.require(!value.isMissingNode(), 422, "MAPPING_ERROR", "缺少字典映射：" + target);
      }
      if (value.isMissingNode()) continue;
      var f = fs.get(target);
      String type = f.path("type").asText();
      if (!value.isNull()) {
        if (Set.of("DECIMAL", "INTEGER").contains(type) && !value.isObject()) {
          value =
              Json.object("value", value.isNumber() ? value.decimalValue() : Json.decimal(value));
          String unit =
              m.has("unitSource")
                  ? output.path(m.path("unitSource").asText()).asText()
                  : m.path("unit").asText(f.path("unit").asText());
          if (!unit.isEmpty()) ((ObjectNode) value).put("unit", unit);
        } else if (type.equals("REFERENCE") && !value.isObject())
          value = Json.object("type", f.path("referenceType"), "id", value.asText());
        else if (type.equals("STRING")
            && value.isNumber()
            && m.path("format").asText().equals("TEXT")) value = TextNode.valueOf(value.asText());
        else if (type.equals("BOOLEAN") && value.isTextual()) {
          Problem.require(
              value.asText().equals("true") || value.asText().equals("false"),
              422,
              "MAPPING_ERROR",
              "布尔文本映射无效");
          value = BooleanNode.valueOf(value.asBoolean());
        }
        if (value.isTextual()) {
          String format = m.path("format").asText();
          if (format.equals("TRIM")) value = TextNode.valueOf(value.asText().trim());
          if (format.equals("UPPER"))
            value = TextNode.valueOf(value.asText().trim().toUpperCase(Locale.ROOT));
          if (format.equals("LOWER"))
            value = TextNode.valueOf(value.asText().trim().toLowerCase(Locale.ROOT));
        }
      }
      attrs.set(target, value);
    }
    return attrs;
  }

  public ObjectNode identity(JsonNode deps, JsonNode normalized) {
    ArrayNode canonical = Json.arr();
    ObjectNode values = Json.obj();
    for (var f : deps.path("identity").path("fields")) {
      String key = f.path("field").asText();
      JsonNode v = normalized.path(key);
      Problem.require(
          (!v.isMissingNode() && !v.isNull()) || f.path("allowNull").asBoolean(false),
          422,
          "IDENTITY_EMPTY",
          "身份属性为空：" + key);
      if (v.isMissingNode()) v = NullNode.instance;
      if (v.isTextual()) {
        String raw = f.path("trim").asBoolean(true) ? v.asText().trim() : v.asText();
        String mode = f.path("case").asText("KEEP");
        if (mode.equals("UPPER")) raw = raw.toUpperCase(Locale.ROOT);
        if (mode.equals("LOWER")) raw = raw.toLowerCase(Locale.ROOT);
        v = TextNode.valueOf(raw);
        Problem.require(
            !raw.isBlank() || f.path("allowNull").asBoolean(),
            422,
            "IDENTITY_EMPTY",
            "身份字符串为空：" + key);
      }
      values.set(key, v);
      canonical.add(Json.object("field", key, "value", v));
    }
    String text = Json.canonical(canonical);
    return Json.object("canonical", text, "hash", Json.hash(text), "values", values);
  }

  public ObjectNode prepare(
      Ctx c, JsonNode rel, String sourceKey, JsonNode payload, boolean fixed) {
    return prepare(c, rel, sourceKey, payload, fixed, false);
  }

  public ObjectNode prepare(
      Ctx c, JsonNode rel, String sourceKey, JsonNode payload, boolean fixed, boolean observation) {
    JsonNode deps = fixed ? rel.path("dependencySnapshot") : dependency(c, rel);
    var ds = config(c, rel.path("datasetId").asText(), "DATASET");
    if (fixed) {
      ds = (ObjectNode) ds.deepCopy();
      ((ObjectNode) ds).set("definition", deps.path("dataset"));
    }
    ObjectNode read = datasets.preview(c, ds, sourceKey, payload);
    var mapped = map(c, deps, expandLookups(c, deps, read.path("output")));
    ObjectNode checked = resolveReferences(c, deps, mapped, observation);
    ObjectNode effective = (ObjectNode) deps.path("schema").deepCopy();
    effective.put("_syntaxOnly", false);
    effective.set("_resolvedReferences", checked);
    effective.put("_observeExisting", observation);
    ObjectNode normalized;
    try {
      normalized = rules.normalize(c, effective, mapped, true, true);
    } catch (Problem p) {
      ArrayNode errors = Json.arr();
      for (var e : p.errors) {
        var er = (ObjectNode) e.deepCopy();
        er.put("sourceRecordKey", sourceKey);
        String key = er.path("fieldPath").asText().replace("/attributes/", "");
        er.set("sourceValue", mapped.path(key));
        er.put("suggestion", "请在ERP修正源字段或发布新的Mapping/Schema版本");
        errors.add(er);
      }
      throw new Problem(p.status, p.code, p.getMessage(), errors);
    }
    var identity = identity(deps, normalized);
    var preview =
        rules.number(c, rel.path("codeRuleId").asText(), deps.path("schema"), normalized, false);
    ArrayNode warnings = Json.arr();
    for (var rule : deps.path("schema").path("validations"))
      if (rule.path("severity").asText().equalsIgnoreCase("warning")
          && !Json.truth(rules.eval(rule.path("assert"), normalized)))
        warnings.add(
            Json.object(
                "code",
                "RULE_WARNING",
                "fieldPath",
                rule.path("field"),
                "message",
                rule.path("message"),
                "sourceRecordKey",
                sourceKey,
                "ruleVersion",
                deps.path("schemaVersion")));
    return Json.object(
        "read",
        read,
        "mapped",
        mapped,
        "references",
        checked,
        "attributes",
        normalized,
        "identity",
        identity,
        "candidateMaterialNo",
        preview,
        "warnings",
        warnings,
        "dependencies",
        deps);
  }

  public JsonNode test(Ctx c, String rid) {
    var r = release(c, rid);
    c.check("READ", models.categoryById(c, r.path("categoryId").asText()).path("code").asText());
    Problem.require(
        c.actions().contains("DESIGN") || c.actions().contains("PUBLISH"),
        403,
        "ACTION_FORBIDDEN",
        "配置测试需要设计或发布权限");
    var deps = dependency(c, r);
    var samples = r.path("definition").path("samples");
    Problem.require(
        samples.isArray() && !samples.isEmpty(),
        422,
        "GOLDEN_SAMPLES_REQUIRED",
        "发布必须有真实Dataset样本与预期结果");
    Set<String> identities = new HashSet<>();
    Set<String> numbers = new HashSet<>();
    ArrayNode results = Json.arr();
    int validCount = 0;
    for (var sample : samples) {
      boolean valid = sample.path("valid").asBoolean(true);
      Problem.require(
          valid ? sample.hasNonNull("expectedMaterialNo") : sample.hasNonNull("errorCode"),
          422,
          "GOLDEN_EXPECTATION_REQUIRED",
          "样本必须声明独立预期料号或错误码");
      try {
        var prepared =
            prepare(c, r, sample.path("sourceRecordKey").asText(), sample.get("payload"), false);
        Problem.require(valid, 422, "GOLDEN_SAMPLE_MISMATCH", "应失败的样本意外通过");
        String identity = prepared.path("identity").path("canonical").asText();
        Problem.require(
            identities.add(identity), 422, "IDENTITY_SAMPLE_CONFLICT", "样本身份重复，请核对身份粒度");
        String no = prepared.path("candidateMaterialNo").asText();
        if (!no.contains("{流水:"))
          Problem.require(numbers.add(no), 422, "CODE_SAMPLE_CONFLICT", "不同样本生成同一码");
        if (sample.has("expectedMaterialNo"))
          Problem.require(
              no.equals(sample.path("expectedMaterialNo").asText()),
              422,
              "GOLDEN_SAMPLE_MISMATCH",
              "料号不符合Golden预期");
        if (!no.contains("{流水:"))
          Problem.require(
              no.matches(deps.path("codeRule").path("allowedPattern").asText("[A-Za-z0-9_./-]+")),
              422,
              "CODE_FORMAT",
              "样本料号字符非法");
        results.add(
            Json.object(
                "sourceRecordKey",
                sample.path("sourceRecordKey"),
                "passed",
                true,
                "candidateMaterialNo",
                no,
                "identity",
                identity));
        validCount++;
      } catch (Problem p) {
        if (valid || p.code.equals("GOLDEN_SAMPLE_MISMATCH")) throw p;
        if (sample.has("errorCode"))
          Problem.require(
              sample.path("errorCode").asText().equals(p.code),
              422,
              "GOLDEN_SAMPLE_MISMATCH",
              "失败码与预期不同");
        results.add(
            Json.object(
                "sourceRecordKey",
                sample.path("sourceRecordKey"),
                "passed",
                true,
                "expectedFailure",
                p.code));
      }
    }
    Problem.require(validCount > 0, 422, "GOLDEN_SAMPLES_REQUIRED", "发布须至少一个成功样本");
    var writeback = r.path("definition").path("writeback");
    Problem.require(
        writeback.isObject()
            && writeback.path("queryPath").asText().contains("{key}")
            && writeback.path("path").asText().contains("{key}"),
        422,
        "WRITEBACK_CONTRACT",
        "回写须声明身份路径和当前料号查询契约");
    for (String path : List.of("path", "queryPath"))
      Problem.require(
          writeback.path(path).asText().startsWith("/")
              && !writeback.path(path).asText().contains(".."),
          422,
          "WRITEBACK_CONTRACT",
          "回写路径必须为相对登记资源");
    Problem.require(
        writeback.path("conditionalEmptyWrite").asBoolean(),
        422,
        "WRITEBACK_CONTRACT",
        "回写必须声明条件空值写入以防覆盖ERP变更");
    sources.connections.destination(writeback.path("baseUrl").asText());
    var contract = contractTest(c, writeback);
    return Json.object(
        "passed", true, "samples", results, "dependencies", deps, "writebackContract", contract);
  }

  public JsonNode contractTest(Ctx c, JsonNode adapter) {
    String base = adapter.path("baseUrl").asText();
    sources.connections.destination(base);
    SourceMetadata.url(base);
    for (String field : List.of("contractPath", "businessValidationPath")) {
      String path = adapter.path(field).asText();
      Problem.require(
          path.startsWith("/") && !path.contains("..") && !path.startsWith("//"),
          422,
          "WRITEBACK_CONTRACT",
          "发布必须声明只读能力查询和无副作用业务校验契约：" + field);
    }
    try {
      var client =
          java.net.http.HttpClient.newBuilder()
              .connectTimeout(java.time.Duration.ofSeconds(3))
              .build();
      var get =
          java.net.http.HttpRequest.newBuilder(
                  java.net.URI.create(base + adapter.path("contractPath").asText()))
              .timeout(java.time.Duration.ofSeconds(5));
      var post =
          java.net.http.HttpRequest.newBuilder(
                  java.net.URI.create(base + adapter.path("businessValidationPath").asText()))
              .timeout(java.time.Duration.ofSeconds(5))
              .header("Content-Type", "application/json");
      if (adapter.has("tokenEnv")) {
        String token = sources.env(adapter, "tokenEnv", c.tenant());
        get.header("Authorization", "Bearer " + token);
        post.header("Authorization", "Bearer " + token);
      }
      var capabilities =
          client.send(get.GET().build(), java.net.http.HttpResponse.BodyHandlers.ofString());
      var cp = Json.read(capabilities.body());
      Problem.require(
          capabilities.statusCode() == 200
              && cp.path("queryCurrentValue").asBoolean()
              && cp.path("conditionalEmptyWrite").asBoolean()
              && (!adapter.path("supportsIdempotency").asBoolean()
                  || cp.path("idempotency").asBoolean()),
          422,
          "WRITEBACK_CONTRACT",
          "ERP契约不支持当前值查询、条件写入或声明的幂等能力");
      var invalid =
          client.send(
              post.POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build(),
              java.net.http.HttpResponse.BodyHandlers.ofString());
      var body = Json.read(invalid.body());
      Problem.require(
          invalid.statusCode() == 200
              && body.has("success")
              && !body.path("success").asBoolean()
              && body.path("code")
                  .asText()
                  .equals(adapter.path("expectedBusinessError").asText("MATERIAL_NO_REQUIRED")),
          422,
          "WRITEBACK_CONTRACT",
          "ERP业务失败契约不符合预期，HTTP成功不得误判为保存成功");
      return Json.object(
          "passed", true, "capabilities", cp, "businessFailureCode", body.path("code"));
    } catch (Problem p) {
      throw p;
    } catch (Exception e) {
      throw new Problem(503, "WRITEBACK_CONTRACT_UNAVAILABLE", "发布前ERP契约测试失败，请检查登记接口");
    }
  }

  Set<String> allReferencedDatasets(JsonNode deps) {
    Set<String> ids = new HashSet<>();
    deps.path("referenceDatasets").fieldNames().forEachRemaining(ids::add);
    deps.path("lookupDatasets").fieldNames().forEachRemaining(ids::add);
    return ids;
  }

  public JsonNode action(Ctx c, String rid, String action, JsonNode b, String version) {
    var r = release(c, rid);
    Db.version(r, version);
    String cat = models.categoryById(c, r.path("categoryId").asText()).path("code").asText();
    String state = r.path("status").asText();
    if (action.equals("submit")) {
      c.check("DESIGN", cat);
      Problem.require(
          state.equals("DRAFT") && !r.path("everPublished").asBoolean(),
          409,
          "RELEASE_STATE",
          "仅草稿可提交");
      var tests = test(c, rid);
      auth.availableApprover(
          c, cat, models.category(c, cat).path("publishRole").asText("PUBLISHER"));
      db.run(
          "update release_package set"
              + " status='REVIEW',submitted_by=?,tests=?::jsonb,dependency_snapshot=?::jsonb,row_version=row_version+1"
              + " where tenant_id=? and id=? and row_version=?",
          c.uid(),
          Json.str(tests),
          Json.str(tests.path("dependencies")),
          c.tid(),
          SourceMetadata.id(rid),
          r.path("rowVersion").asLong());
    } else if (action.equals("approve")) {
      c.check("PUBLISH", cat);
      auth.role(c, models.category(c, cat).path("publishRole").asText("PUBLISHER"));
      Problem.require(state.equals("REVIEW"), 409, "RELEASE_STATE", "配置不在审核状态");
      Problem.require(
          !r.path("submittedBy").asText().equals(c.user()), 403, "SELF_APPROVAL", "提交人与最终审批人必须不同");
      Problem.require(
          Json.canonical(dependency(c, r)).equals(Json.canonical(r.path("dependencySnapshot"))),
          409,
          "DEPENDENCY_CHANGED",
          "提交后依赖变化，请撤回重新测试");
      var tests = test(c, rid);
      db.run(
          "update release_package set"
              + " status='PUBLISHED',ever_published=true,row_version=row_version+1 where"
              + " tenant_id=? and id=?",
          c.tid(),
          SourceMetadata.id(rid));
      for (String field : List.of("datasetId", "mappingId", "identityId", "codeRuleId"))
        db.run(
            "update numbering_config set status='PUBLISHED',ever_published=true where tenant_id=?"
                + " and id=?",
            c.tid(),
            SourceMetadata.id(r.path(field).asText()));
      for (var refIds = allReferencedDatasets(r.path("dependencySnapshot")).iterator();
          refIds.hasNext(); ) {
        String ref = refIds.next();
        db.run(
            "update numbering_config set status='PUBLISHED',ever_published=true where tenant_id=?"
                + " and id=?",
            c.tid(),
            SourceMetadata.id(ref));
      }
      for (var objectIds = r.path("dependencySnapshot").path("objects").fieldNames();
          objectIds.hasNext(); ) {
        String oid = objectIds.next();
        db.run(
            "update source_object_version set status='PUBLISHED',ever_published=true where"
                + " tenant_id=? and id=?",
            c.tid(),
            SourceMetadata.id(oid));
      }
      db.run(
          "update schema_version set status='PUBLISHED',ever_published=true where tenant_id=? and"
              + " id=?",
          c.tid(),
          SourceMetadata.id(r.path("schemaId").asText()));
      var ds = config(c, r.path("datasetId").asText(), "DATASET");
      db.run(
          "insert into dataset_runtime(tenant_id,dataset_code,release_id,actor) values(?,?,?,?) on"
              + " conflict(tenant_id,dataset_code) do update set"
              + " release_id=excluded.release_id,row_version=dataset_runtime.row_version+1",
          c.tid(),
          ds.path("code").asText(),
          SourceMetadata.id(rid),
          r.path("createdBy").asText().isBlank()
              ? c.uid()
              : SourceMetadata.id(r.path("createdBy").asText()));
    } else if (Set.of("withdraw", "reject").contains(action)) {
      c.check(action.equals("withdraw") ? "DESIGN" : "PUBLISH", cat);
      Problem.require(state.equals("REVIEW"), 409, "RELEASE_STATE", "仅待审可撤回/驳回");
      if (action.equals("withdraw"))
        Problem.require(
            r.path("submittedBy").asText().equals(c.user()), 403, "FORBIDDEN", "仅提交人可撤回");
      db.run(
          "update release_package set status='DRAFT',row_version=row_version+1 where tenant_id=?"
              + " and id=?",
          c.tid(),
          SourceMetadata.id(rid));
    } else if (action.equals("retire")) {
      c.check("PUBLISH", cat);
      Problem.require(state.equals("PUBLISHED"), 409, "RELEASE_STATE", "仅已发布版本可停用");
      db.run(
          "update release_package set status='RETIRED',row_version=row_version+1 where tenant_id=?"
              + " and id=?",
          c.tid(),
          SourceMetadata.id(rid));
      db.run(
          "update dataset_runtime set enabled=false,row_version=row_version+1 where tenant_id=? and"
              + " release_id=?",
          c.tid(),
          SourceMetadata.id(rid));
    } else if (action.equals("set-default")) {
      c.check("PUBLISH", cat);
      Problem.require(r.path("everPublished").asBoolean(), 409, "RELEASE_STATE", "只能回退到曾发布的不可变版本");
      db.run(
          "update release_package set status='PUBLISHED',row_version=row_version+1 where"
              + " tenant_id=? and id=?",
          c.tid(),
          SourceMetadata.id(rid));
      var ds = config(c, r.path("datasetId").asText(), "DATASET");
      db.run(
          "update dataset_runtime set release_id=?,row_version=row_version+1 where tenant_id=? and"
              + " dataset_code=?",
          SourceMetadata.id(rid),
          c.tid(),
          ds.path("code").asText());
    } else throw new Problem(404, "API_NOT_FOUND", "发布动作不存在");
    db.log(
        c,
        "RELEASE",
        rid,
        r.path("rowVersion").asLong() + 1,
        action,
        b.path("reason").asText("配置治理"),
        Json.object("statusBefore", state),
        r.path("categoryId").asText());
    return release(c, rid);
  }

  public JsonNode impact(Ctx c, String rid) {
    var r = release(c, rid);
    c.check("READ", models.categoryById(c, r.path("categoryId").asText()).path("code").asText());
    ArrayNode results = Json.arr();
    var ds = config(c, r.path("datasetId").asText(), "DATASET");
    for (var old :
        db.list(
            "select * from material_assignment where tenant_id=? and category_id=? and"
                + " dataset_code=? order by issued_at",
            c.tid(),
            SourceMetadata.id(r.path("categoryId").asText()),
            ds.path("code").asText()))
      try {
        var p = prepare(c, r, old.path("sourceRecordKey").asText(), null, false);
        results.add(
            Json.object(
                "assignmentId",
                old.path("id"),
                "sourceRecordKey",
                old.path("sourceRecordKey"),
                "identityChanged",
                !old.path("identityCanonical")
                    .asText()
                    .equals(p.path("identity").path("canonical").asText()),
                "oldMaterialNo",
                old.path("materialNo"),
                "candidateMaterialNo",
                p.path("candidateMaterialNo"),
                "historyUnchanged",
                true));
      } catch (Problem p) {
        results.add(
            Json.object(
                "assignmentId",
                old.path("id"),
                "sourceRecordKey",
                old.path("sourceRecordKey"),
                "errorCode",
                p.code,
                "errors",
                p.errors,
                "historyUnchanged",
                true));
      }
    return Json.object("releaseId", rid, "affectedAssignments", results, "historyMutation", false);
  }

  public ObjectNode current(Ctx c, String code) {
    var runtime =
        db.one("select * from dataset_runtime where tenant_id=? and dataset_code=?", c.tid(), code);
    var release = release(c, runtime.path("releaseId").asText());
    Problem.require(
        release.path("status").asText().equals("PUBLISHED"),
        422,
        "RELEASE_RETIRED",
        "Dataset未绑定有效发布包");
    return release;
  }
}
