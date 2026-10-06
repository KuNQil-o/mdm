package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.apache.commons.csv.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

@Service
public class Files {
  final Db db;
  final Search search;
  final Models models;

  public Files(Db d, Search s, Models m) {
    db = d;
    search = s;
    models = m;
  }

  public JsonNode export(Ctx c, JsonNode b) {
    String code = b.path("query").path("categoryCode").asText("");
    c.check("EXPORT", code.isEmpty() ? null : code);
    ObjectNode q =
        b.path("query").isObject() ? (ObjectNode) b.path("query").deepCopy() : Json.obj();
    if (!q.hasNonNull("cutoff")) q.put("cutoff", java.time.Instant.now().toString());
    q.set("page", Json.object("size", 200));
    int limit =
        db.one("select settings from tenant where id=?", c.tid())
            .path("settings")
            .path("exportLimit")
            .asInt(50000);
    List<JsonNode> items = new ArrayList<>();
    while (true) {
      var batch = search.find(c, q);
      batch.path("items").forEach(items::add);
      Problem.require(items.size() <= limit, 422, "EXPORT_LIMIT", "结果超过租户导出上限，请收紧筛选");
      if (!batch.path("hasMore").asBoolean()) break;
      q.set("page", Json.object("size", 200, "cursor", batch.path("nextCursor")));
    }
    List<String> cols =
        new ArrayList<>(
            List.of(
                "id",
                "materialNo",
                "materialName",
                "categoryCode",
                "status",
                "rowVersion",
                "schemaVersionId"));
    if (b.path("fields").isArray() && !b.path("fields").isEmpty()) {
      cols.clear();
      b.path("fields")
          .forEach(
              x -> {
                String f = x.asText();
                Problem.require(
                    Set.of(
                                "id",
                                "materialNo",
                                "materialName",
                                "categoryCode",
                                "status",
                                "rowVersion",
                                "schemaVersionId",
                                "baseUnitCode",
                                "updatedAt")
                            .contains(f)
                        || (!code.isEmpty() && f.startsWith("attributes.")),
                    422,
                    "EXPORT_FIELD",
                    "未知导出字段");
                if (f.startsWith("attributes.")) {
                  var schema = models.currentSchema(c, code, null);
                  Problem.require(
                      schema
                          .path("bundle")
                          .path("attributes")
                          .toString()
                          .contains("\"" + f.substring(11) + "\""),
                      422,
                      "EXPORT_FIELD",
                      "未知动态属性");
                }
                cols.add(f);
              });
    } else if (!code.isEmpty()) {
      var schema = models.currentSchema(c, code, null);
      for (var f : schema.path("bundle").path("attributes"))
        cols.add("attributes." + f.path("code").asText());
    }
    Set<String> categoryIds = new HashSet<>();
    items.forEach(x -> categoryIds.add(Json.text(x, "categoryId")));
    q.set("categoryIds", Json.M.valueToTree(categoryIds));
    byte[] content = table(cols, items, b.path("format").asText("CSV"));
    UUID fid = UUID.randomUUID();
    UUID cat = code.isEmpty() ? null : models.uuid(Json.text(models.category(c, code), "id"));
    String name = "物料导出." + (b.path("format").asText().equals("XLSX") ? "xlsx" : "csv");
    db.run(
        "insert into"
            + " file_record(id,tenant_id,category_id,name,kind,content,checksum,row_count,query)"
            + " values(?,?,?,?,?,?,?,?,?::jsonb)",
        fid,
        c.tid(),
        cat,
        name,
        "EXPORT",
        content,
        Json.hash(Base64.getEncoder().encodeToString(content)),
        items.size(),
        Json.str(q));
    return Json.object(
        "id",
        fid,
        "name",
        name,
        "rowCount",
        items.size(),
        "queryCutoff",
        q.path("cutoff"),
        "status",
        "COMPLETED",
        "downloadPath",
        "/api/v1/files/" + fid);
  }

  public byte[] table(List<String> cols, List<? extends JsonNode> items, String format) {
    try {
      if (format.equals("XLSX")) {
        try (var w = new XSSFWorkbook();
            var out = new ByteArrayOutputStream()) {
          var s = w.createSheet("物料");
          var head = s.createRow(0);
          for (int i = 0; i < cols.size(); i++) head.createCell(i).setCellValue(cols.get(i));
          int n = 1;
          for (var item : items) {
            var row = s.createRow(n++);
            for (int i = 0; i < cols.size(); i++)
              row.createCell(i).setCellValue(cell(Json.path(item, cols.get(i))));
          }
          w.write(out);
          return out.toByteArray();
        }
      }
      var out = new StringWriter();
      try (var printer =
          new CSVPrinter(
              out, CSVFormat.DEFAULT.builder().setHeader(cols.toArray(String[]::new)).get())) {
        for (var item : items) {
          List<String> row = new ArrayList<>();
          for (String col : cols) row.add(cell(Json.path(item, col)));
          printer.printRecord(row);
        }
      }
      return ("\uFEFF" + out).getBytes(StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new Problem(500, "FILE_ERROR", "文件生成失败");
    }
  }

  String cell(JsonNode n) {
    if (n == null || n.isNull() || n.isMissingNode()) return "";
    String s = n.isObject() || n.isArray() ? Json.str(n) : n.asText();
    return s.matches("^[=+@-].*") ? "'" + s : s;
  }

  public ObjectNode file(Ctx c, String id) {
    var f =
        db.one(
            "select"
                + " id,tenant_id,category_id,name,kind,checksum,row_count,query,created_at,cleaned_at"
                + " from file_record where tenant_id=? and id=?",
            c.tid(),
            models.uuid(id));
    if (f.hasNonNull("categoryId")) models.categoryById(c, Json.text(f, "categoryId"));
    for (var categoryId : f.path("query").path("categoryIds"))
      models.categoryById(c, categoryId.asText());
    if (Json.text(f, "kind").equals("INTEGRATION")
        && f.path("query").path("direction").asText().equals("INBOUND")
        && !c.scope().contains("*"))
      Problem.require(
          f.path("query").path("uploader").asText().equals(c.user()),
          403,
          "FILE_SCOPE",
          "混合入站源文件仅原上传成员或全类别集成岗位可下载");
    if (Json.text(f, "kind").equals("EXPORT")) c.check("EXPORT", null);
    else if (Json.text(f, "kind").equals("INTEGRATION")) c.check("INTEGRATE", null);
    else c.check("IMPORT", null);
    Problem.require(!f.hasNonNull("cleanedAt"), 410, "FILE_CLEANED", "文件已按保留策略清理，任务摘要仍保留");
    return f;
  }

  public byte[] content(Ctx c, String id) {
    file(c, id);
    return db.jdbc.queryForObject(
        "select content from file_record where tenant_id=? and id=?",
        byte[].class,
        c.tid(),
        models.uuid(id));
  }

  public JsonNode clean(Ctx c) {
    c.check("ADMIN", null);
    int days =
        db.one("select settings from tenant where id=?", c.tid())
            .path("settings")
            .path("retentionDays")
            .asInt(30);
    int n =
        db.run(
            "update file_record f set content=null,cleaned_at=now() where f.tenant_id=? and"
                + " cleaned_at is null and created_at<now()-(? * interval '1 day') and not"
                + " exists(select 1 from import_job j where j.file_id=f.id and j.status not in"
                + " ('COMPLETED','CANCELLED'))",
            c.tid(),
            days);
    db.log(c, "FILES", c.tenant(), 0, "CLEAN_FILES", "到期且无未结束导入", Json.object("count", n), null);
    return Json.object("cleaned", n);
  }

  public List<String> sheets(byte[] bytes, String name) {
    if (!name.toLowerCase().endsWith(".xlsx")) return List.of("CSV");
    try (var w = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
      List<String> s = new ArrayList<>();
      for (int i = 0; i < w.getNumberOfSheets(); i++) s.add(w.getSheetName(i));
      return s;
    } catch (Exception e) {
      throw new Problem(422, "FILE_INVALID", "XLSX无法读取");
    }
  }

  public List<ObjectNode> read(byte[] bytes, String name, String sheet) {
    List<ObjectNode> out = new ArrayList<>();
    try {
      if (name.toLowerCase().endsWith(".xlsx")) {
        try (var w = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
          Problem.require(sheet != null && !sheet.isBlank(), 422, "SHEET_REQUIRED", "请选择工作表");
          var s = w.getSheet(sheet);
          Problem.require(s != null, 422, "SHEET_NOT_FOUND", "工作表不存在");
          var formatter = new DataFormatter();
          var header = s.getRow(s.getFirstRowNum());
          Problem.require(
              header != null && header.getLastCellNum() <= 200, 422, "FILE_COLUMNS", "最多200列");
          Set<String> keys = new HashSet<>();
          for (var cell : header)
            Problem.require(
                keys.add(formatter.formatCellValue(cell))
                    && !formatter.formatCellValue(cell).isBlank(),
                422,
                "FILE_HEADER",
                "表头不能为空或重复");
          for (int row = s.getFirstRowNum() + 1; row <= s.getLastRowNum(); row++) {
            var r = s.getRow(row);
            if (r == null) continue;
            ObjectNode o = Json.obj();
            boolean empty = true;
            for (int col = 0; col < header.getLastCellNum(); col++) {
              var cell = r.getCell(col);
              if (cell != null)
                Problem.require(
                    cell.getCellType() != CellType.FORMULA, 422, "FORMULA_CELL", "公式单元格须转换为确定值");
              String v = cell == null ? "" : formatter.formatCellValue(cell);
              if (!v.isEmpty()) empty = false;
              o.put(formatter.formatCellValue(header.getCell(col)), v);
            }
            if (!empty) out.add(o);
          }
        }
      } else {
        Problem.require(name.toLowerCase().endsWith(".csv"), 422, "FILE_TYPE", "仅支持CSV/XLSX");
        String text =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
                .replaceFirst("^\uFEFF", "");
        try (var p =
            CSVFormat.DEFAULT
                .builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setAllowMissingColumnNames(false)
                .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
                .get()
                .parse(new StringReader(text))) {
          Problem.require(p.getHeaderMap().size() <= 200, 422, "FILE_COLUMNS", "最多200列");
          for (var r : p) {
            Problem.require(r.isConsistent(), 422, "CSV_ROW", "CSV列数不一致");
            ObjectNode o = Json.obj();
            r.toMap().forEach(o::put);
            out.add(o);
          }
        }
      }
      Problem.require(out.size() <= 50000, 422, "FILE_ROWS", "最多50000行");
      return out;
    } catch (Problem p) {
      throw p;
    } catch (Exception e) {
      throw new Problem(422, "FILE_INVALID", "文件无法读取：" + e.getMessage());
    }
  }
}
