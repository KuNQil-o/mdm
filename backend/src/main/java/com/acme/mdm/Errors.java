package com.acme.mdm;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.dao.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class Errors {
  final Db db;
  final Auth auth;

  public Errors(Db d, Auth a) {
    db = d;
    auth = a;
  }

  @ExceptionHandler(Problem.class)
  public ResponseEntity<JsonNode> problem(Problem p, HttpServletRequest r) {
    return ResponseEntity.status(p.status)
        .body(
            Json.object(
                "code",
                p.code,
                "message",
                p.getMessage(),
                "errors",
                p.errors,
                "traceId",
                r.getHeader("X-Request-ID") == null
                    ? UUID.randomUUID().toString()
                    : r.getHeader("X-Request-ID")));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<JsonNode> constraint(
      DataIntegrityViolationException e, HttpServletRequest r) {
    String m = e.getMostSpecificCause().getMessage();
    String code =
        m.contains("material_tenant_id_material_no")
            ? "CODE_CONFLICT"
            : m.contains("external_identity")
                ? "EXTERNAL_ID_CONFLICT"
                : m.contains("material_unique_key")
                    ? "BUSINESS_KEY_CONFLICT"
                    : "UNIQUE_OR_REFERENCE_CONFLICT";
    var details = Json.arr();
    if (code.equals("CODE_CONFLICT")) {
      try {
        var ctx = auth.ctx(r, false);
        var match =
            java.util.regex.Pattern.compile("material_no\\)=\\([^,]+, (.+)\\) already exists")
                .matcher(m);
        if (match.find()) {
          var conflict =
              db.maybe(
                  "select m.id,c.code as category_code from material m join category c on"
                      + " c.id=m.category_id where m.tenant_id=? and m.material_no=?",
                  ctx.tid(),
                  match.group(1));
          if (conflict != null && ctx.allows(Json.text(conflict, "categoryCode")))
            details.add(
                Json.object(
                    "code",
                    code,
                    "conflictMaterialId",
                    conflict.path("id"),
                    "conflictPath",
                    "/api/v1/materials/" + Json.text(conflict, "id"),
                    "fieldPath",
                    "/materialNo",
                    "message",
                    "可查看范围内已有同号物料"));
        }
      } catch (Exception ignored) {
      }
    }
    return problem(new Problem(409, code, "数据库唯一性或关联约束冲突，操作已回滚", details), r);
  }

  @ExceptionHandler({
    org.springframework.http.converter.HttpMessageNotReadableException.class,
    IllegalArgumentException.class
  })
  public ResponseEntity<JsonNode> bad(Exception e, HttpServletRequest r) {
    return problem(new Problem(400, "BAD_REQUEST", "请求格式或参数错误"), r);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<JsonNode> unknown(Exception e, HttpServletRequest r) {
    org.slf4j.LoggerFactory.getLogger(Errors.class).error("API failure {}", r.getRequestURI(), e);
    return problem(new Problem(500, "INTERNAL_ERROR", "操作未完成，请按traceId查日志；请勿重复创建"), r);
  }
}
