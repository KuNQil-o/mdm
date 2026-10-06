package com.acme.mdm;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Auth {
  final Db db;

  public Auth(Db d) {
    db = d;
  }

  public ObjectNode user(HttpServletRequest r) {
    var s = r.getSession(false);
    Problem.require(
        s != null && s.getAttribute("user") != null, 401, "LOGIN_REQUIRED", "请选择开发演示用户登录");
    return db.one(
        "select * from business_user where id=?",
        UUID.fromString(s.getAttribute("user").toString()));
  }

  public void platform(HttpServletRequest r) {
    Problem.require(
        user(r).path("platformAdmin").asBoolean(), 403, "PLATFORM_FORBIDDEN", "需要平台管理员岗位");
  }

  public Ctx ctx(HttpServletRequest r, boolean write) {
    var u = user(r);
    String code = r.getHeader("X-Tenant-Code");
    Problem.require(code != null && !code.isBlank(), 400, "TENANT_REQUIRED", "请明确选择当前租户");
    var t = db.one("select * from tenant where code=?", code);
    var m =
        db.maybe(
            "select * from tenant_member where tenant_id=? and user_id=? and active",
            UUID.fromString(Json.text(t, "id")),
            UUID.fromString(Json.text(u, "id")));
    Problem.require(m != null, 403, "MEMBERSHIP_REQUIRED", "不是该租户的有效成员");
    r.setAttribute("metricsTenantId", Json.text(t, "id"));
    if (write) active(Json.text(t, "id"));
    Set<String> a = new HashSet<>(), s = new HashSet<>();
    m.path("roles")
        .forEach(
            role -> {
              var x =
                  db.maybe(
                      "select actions from role where tenant_id=? and code=?",
                      UUID.fromString(Json.text(t, "id")),
                      role.asText());
              if (x != null) x.path("actions").forEach(v -> a.add(v.asText()));
            });
    m.path("categoryScope").forEach(v -> s.add(v.asText()));
    return new Ctx(
        Json.text(t, "id"),
        Json.text(u, "id"),
        a,
        s,
        Optional.ofNullable(r.getHeader("X-Request-ID")).orElse(UUID.randomUUID().toString()));
  }

  public void active(String tid) {
    Problem.require(
        "ACTIVE"
            .equals(
                Json.text(
                    db.one("select status from tenant where id=?", UUID.fromString(tid)),
                    "status")),
        409,
        "TENANT_PAUSED",
        "租户当前未运行，保留数据和任务进度");
  }

  public Ctx worker(String tenant, String user) {
    var m =
        db.one(
            "select * from tenant_member where tenant_id=? and user_id=? and active",
            UUID.fromString(tenant),
            UUID.fromString(user));
    Set<String> a = new HashSet<>(), s = new HashSet<>();
    m.path("roles")
        .forEach(
            role ->
                db.one(
                        "select actions from role where tenant_id=? and code=?",
                        UUID.fromString(tenant),
                        role.asText())
                    .path("actions")
                    .forEach(x -> a.add(x.asText())));
    m.path("categoryScope").forEach(x -> s.add(x.asText()));
    return new Ctx(tenant, user, a, s, UUID.randomUUID().toString());
  }

  public void availableApprover(Ctx c, String category, String role) {
    boolean found =
        db
            .list(
                "select m.* from tenant_member m where tenant_id=? and active and user_id<>? and"
                    + " roles @> ?::jsonb",
                c.tid(),
                c.uid(),
                Json.str(Json.arr().add(role)))
            .stream()
            .anyMatch(
                m -> {
                  var s = m.path("categoryScope");
                  for (var x : s)
                    if (x.asText().equals("*") || x.asText().equals(category)) return true;
                  return false;
                });
    Problem.require(found, 422, "NO_APPROVER", "无其他有效审批成员，请联系租户管理员");
  }

  public void role(Ctx c, String role) {
    var m =
        db.one(
            "select roles from tenant_member where tenant_id=? and user_id=? and active",
            c.tid(),
            c.uid());
    boolean ok = false;
    for (var x : m.path("roles")) if (x.asText().equals(role)) ok = true;
    Problem.require(ok, 403, "APPROVAL_ROLE_REQUIRED", "当前成员不具备类别指定审批角色");
  }
}
