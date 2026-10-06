package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Search {
  final Db db;
  final Models models;
  final Rules rules;

  public Search(Db d, Models m, Rules r) {
    db = d;
    models = m;
    rules = r;
  }

  public ObjectNode find(Ctx c, JsonNode query) {
    c.check("READ", null);
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder("m.tenant_id=?");
    args.add(c.tid());
    if (!c.scope().contains("*")) {
      if (c.scope().isEmpty()) where.append(" and false");
      else {
        where
            .append(" and c.code in (")
            .append(String.join(",", Collections.nCopies(c.scope().size(), "?")))
            .append(")");
        args.addAll(c.scope());
      }
    }
    JsonNode bundle = Json.obj();
    String category = query.path("categoryCode").asText("");
    if (!category.isEmpty()) {
      models.category(c, category);
      where.append(" and c.code=?");
      args.add(category);
      var cat = models.category(c, category);
      if (cat.hasNonNull("defaultSchemaId"))
        bundle = models.schema(c, Json.text(cat, "defaultSchemaId"), false).path("bundle");
      else {
        var x =
            db.maybe(
                "select bundle from schema_version where tenant_id=? and category_id=? order by"
                    + " version desc limit 1",
                c.tid(),
                models.uuid(Json.text(cat, "id")));
        if (x != null) bundle = x.path("bundle");
      }
    }
    if (query.hasNonNull("name") && !query.path("name").asText().isBlank()) {
      where.append(" and m.material_name ilike ?");
      args.add("%" + query.path("name").asText().replace("%", "\\%").replace("_", "\\_") + "%");
    }
    if (query.hasNonNull("materialNo") && !query.path("materialNo").asText().isEmpty()) {
      where.append(" and m.material_no=?");
      args.add(query.path("materialNo").asText());
    }
    if (query.hasNonNull("status") && !query.path("status").asText().isEmpty()) {
      where.append(" and m.status=?");
      args.add(query.path("status").asText());
    }
    Map<String, JsonNode> fs = rules.fields(bundle);
    int index = 0;
    for (var filter : query.path("filters")) {
      String field = Json.text(filter, "field"), op = filter.path("op").asText("EQ").toUpperCase();
      JsonNode value = filter.path("value");
      String expr, kind;
      JsonNode def = null;
      if (field.startsWith("attributes.")) {
        Problem.require(!category.isEmpty(), 422, "CATEGORY_REQUIRED", "动态属性筛选须先选择类别");
        String code = field.substring(11);
        def = fs.get(code);
        Problem.require(
            def != null && def.path("searchable").asBoolean(), 422, "FILTER_FIELD", "未知或不可搜索属性");
        kind = def.path("type").asText();
        String column =
            switch (kind) {
              case "INTEGER", "DECIMAL" -> "num_value";
              case "BOOLEAN" -> "bool_value";
              case "DATE" -> "date_value";
              default -> "text_value";
            };
        String alias = "p" + (index++);
        where
            .append(" and exists(select 1 from material_projection ")
            .append(alias)
            .append(" where ")
            .append(alias)
            .append(".tenant_id=m.tenant_id and ")
            .append(alias)
            .append(".material_id=m.id and ")
            .append(alias)
            .append(".attribute_code=? and ");
        args.add(code);
        where.append(alias).append(".kind=? and ");
        args.add(kind);
        expr = alias + "." + column;
      } else {
        expr =
            switch (field) {
              case "materialNo" -> "m.material_no";
              case "materialName" -> "m.material_name";
              case "status" -> "m.status";
              case "updatedAt" -> "m.updated_at";
              default -> throw new Problem(422, "FILTER_FIELD", "未知核心查询字段");
            };
        kind = field.equals("updatedAt") ? "TIMESTAMP" : "STRING";
        where.append(" and ");
      }
      Set<String> legal = Set.of("EQ", "IN");
      if (Set.of("INTEGER", "DECIMAL", "DATE", "TIMESTAMP").contains(kind))
        legal = Set.of("EQ", "IN", "BETWEEN", "GTE", "LTE");
      if (kind.equals("STRING")) legal = Set.of("EQ", "IN", "CONTAINS");
      Problem.require(legal.contains(op), 422, "FILTER_OPERATOR", "运算符不适用于属性类型");
      String cast =
          kind.equals("DATE") ? "::date" : kind.equals("TIMESTAMP") ? "::timestamptz" : "";
      if (op.equals("IN")) {
        Problem.require(
            value.isArray() && !value.isEmpty() && value.size() <= 200,
            422,
            "FILTER_VALUE",
            "IN需要非空数组，最多200项");
        where
            .append(expr)
            .append(" in (")
            .append(String.join(",", Collections.nCopies(value.size(), "?" + cast)))
            .append(")");
        for (var v : value) args.add(value(bundle, def, kind, v, filter));
      } else if (op.equals("BETWEEN")) {
        Problem.require(value.isArray() && value.size() == 2, 422, "FILTER_VALUE", "BETWEEN需要两个值");
        where.append(expr).append(" between ?").append(cast).append(" and ?").append(cast);
        args.add(value(bundle, def, kind, value.get(0), filter));
        args.add(value(bundle, def, kind, value.get(1), filter));
      } else {
        where
            .append(expr)
            .append(
                switch (op) {
                  case "GTE" -> ">=?";
                  case "LTE" -> "<=?";
                  case "CONTAINS" -> " ilike ?";
                  default -> "=?";
                })
            .append(cast);
        Object v = value(bundle, def, kind, value, filter);
        args.add(op.equals("CONTAINS") ? "%" + v + "%" : v);
      }
      if (field.startsWith("attributes.")) where.append(")");
    }
    String field = query.path("sort").path(0).path("field").asText("updatedAt"),
        direction = query.path("sort").path(0).path("direction").asText("DESC");
    Problem.require(
        direction.equals("ASC") || direction.equals("DESC"), 422, "SORT_DIRECTION", "排序方向无效");
    String expr, cast = "", sortKind = "STRING";
    switch (field) {
      case "updatedAt":
        expr = "m.updated_at";
        cast = "::timestamptz";
        sortKind = "TIMESTAMP";
        break;
      case "materialNo":
        expr = "coalesce(m.material_no,'')";
        break;
      case "materialName":
        expr = "m.material_name";
        break;
      default:
        Problem.require(
            field.startsWith("attributes.") && !category.isEmpty(), 422, "SORT_FIELD", "动态排序须选择类别");
        String code = field.substring(11);
        JsonNode def = fs.get(code);
        Problem.require(
            def != null && def.path("sortable").asBoolean(), 422, "SORT_FIELD", "字段未配置排序");
        Problem.require(code.matches("[A-Za-z][A-Za-z0-9_]*"), 422, "SORT_FIELD", "排序code无效");
        sortKind = def.path("type").asText();
        String col =
            switch (sortKind) {
              case "INTEGER", "DECIMAL" -> "num_value";
              case "BOOLEAN" -> "bool_value::text";
              case "DATE" -> "date_value::text";
              default -> "text_value";
            };
        boolean num = sortKind.equals("DECIMAL") || sortKind.equals("INTEGER");
        expr =
            "coalesce((select "
                + col
                + " from material_projection sp where sp.tenant_id=m.tenant_id and"
                + " sp.material_id=m.id and sp.attribute_code='"
                + code
                + "'),"
                + (num ? "'-1e1000'::numeric" : "''")
                + ")";
        cast = num ? "::numeric" : "";
    }
    int size =
        query
            .path("page")
            .path("size")
            .asInt(
                db.one("select settings from tenant where id=?", c.tid())
                    .path("settings")
                    .path("pageSize")
                    .asInt(50));
    Problem.require(size > 0 && size <= 200, 422, "PAGE_SIZE", "每页1—200条");
    String cutoff = query.path("cutoff").asText(Instant.now().toString());
    String cursor = query.path("page").path("cursor").asText("");
    ObjectNode shape = (ObjectNode) query.deepCopy();
    shape.remove(List.of("page", "cutoff"));
    String hash = Json.hash(Json.canonical(shape));
    if (!cursor.isEmpty()) {
      try {
        var cur =
            Json.read(
                new String(
                    Base64.getUrlDecoder().decode(cursor),
                    java.nio.charset.StandardCharsets.UTF_8));
        Problem.require(cur.path("hash").asText().equals(hash), 422, "CURSOR_QUERY", "游标与查询条件不一致");
        cutoff = cur.path("cutoff").asText();
        where
            .append(" and (")
            .append(expr)
            .append(",m.id) ")
            .append(direction.equals("DESC") ? "<" : " >")
            .append(" (?" + cast + ",?::uuid)");
        args.add(
            sortKind.equals("DECIMAL") || sortKind.equals("INTEGER")
                ? new BigDecimal(cur.path("value").asText())
                : cur.path("value").asText());
        args.add(cur.path("id").asText());
      } catch (IllegalArgumentException e) {
        throw new Problem(422, "CURSOR_INVALID", "游标无效");
      }
    }
    where.append(" and m.updated_at<=?::timestamptz");
    args.add(cutoff);
    args.add(size + 1);
    var items =
        db.list(
            "select m.*,c.code as category_code,c.name as category_name,"
                + expr
                + " as cursor_value from material m join category c on m.category_id=c.id where "
                + where
                + " order by "
                + expr
                + " "
                + direction
                + ",m.id "
                + direction
                + " limit ?",
            args.toArray());
    boolean more = items.size() > size;
    if (more) items.removeLast();
    String next = null;
    if (more) {
      var last = items.getLast();
      next =
          Base64.getUrlEncoder()
              .withoutPadding()
              .encodeToString(
                  Json.str(
                          Json.object(
                              "hash",
                              hash,
                              "cutoff",
                              cutoff,
                              "value",
                              last.path("cursorValue"),
                              "id",
                              last.path("id")))
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    items.forEach(x -> x.remove("cursorValue"));
    return Json.object("items", items, "hasMore", more, "nextCursor", next, "cutoff", cutoff);
  }

  Object value(JsonNode bundle, JsonNode def, String kind, JsonNode v, JsonNode filter) {
    switch (kind) {
      case "INTEGER":
      case "DECIMAL":
        Problem.require(v.isNumber() || v.isObject(), 422, "FILTER_VALUE", "数值查询应为数字");
        BigDecimal n = Json.decimal(v);
        String standard = def.path("unit").asText("");
        if (!standard.isEmpty()) {
          String from = filter.path("unit").asText(standard);
          n = rules.convert(bundle, n, from, standard);
          var u = bundle.path("units").path(standard);
          n =
              n.multiply(new BigDecimal(u.path("factor").asText("1")))
                  .add(new BigDecimal(u.path("offset").asText("0")));
        }
        return n;
      case "BOOLEAN":
        Problem.require(v.isBoolean(), 422, "FILTER_VALUE", "布尔查询应为true/false");
        return v.asBoolean();
      case "DATE":
        try {
          LocalDate.parse(v.asText());
        } catch (Exception e) {
          throw new Problem(422, "FILTER_VALUE", "日期查询无效");
        }
        return v.asText();
      case "TIMESTAMP":
        try {
          Instant.parse(v.asText());
        } catch (Exception e) {
          throw new Problem(422, "FILTER_VALUE", "时间查询应为UTC ISO8601");
        }
        return v.asText();
      case "REFERENCE":
        return v.isObject() ? v.path("id").asText() : v.asText();
      default:
        Problem.require(v.isTextual(), 422, "FILTER_VALUE", "查询值应为文本");
        return v.asText();
    }
  }
}
