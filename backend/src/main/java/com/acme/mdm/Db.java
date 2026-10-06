package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class Db {
  final JdbcTemplate jdbc;
  final TransactionTemplate tx;

  public Db(JdbcTemplate j, PlatformTransactionManager m) {
    jdbc = j;
    tx = new TransactionTemplate(m);
  }

  public <T> T tx(Supplier<T> s) {
    return tx.execute(x -> s.get());
  }

  public <T> T nested(Supplier<T> s) {
    var n = new TransactionTemplate(tx.getTransactionManager());
    n.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
    return n.execute(x -> s.get());
  }

  public int run(String sql, Object... a) {
    return jdbc.update(sql, a);
  }

  public long count(String sql, Object... a) {
    return jdbc.queryForObject(sql, Long.class, a);
  }

  public List<ObjectNode> list(String sql, Object... a) {
    return jdbc.query(sql, (r, k) -> row(r), a);
  }

  public ObjectNode one(String sql, Object... a) {
    var l = list(sql, a);
    if (l.isEmpty()) throw new Problem(404, "NOT_FOUND", "当前租户内记录不存在");
    return l.getFirst();
  }

  public ObjectNode maybe(String sql, Object... a) {
    var l = list(sql, a);
    return l.isEmpty() ? null : l.getFirst();
  }

  static ObjectNode row(ResultSet r) throws SQLException {
    ObjectNode o = Json.obj();
    var md = r.getMetaData();
    for (int i = 1; i <= md.getColumnCount(); i++) {
      String key = md.getColumnLabel(i);
      StringBuilder b = new StringBuilder();
      boolean up = false;
      for (char c : key.toCharArray()) {
        if (c == '_') up = true;
        else {
          b.append(up ? Character.toUpperCase(c) : c);
          up = false;
        }
      }
      Object v = r.getObject(i);
      if (v == null) o.putNull(b.toString());
      else if (md.getColumnTypeName(i).equals("jsonb"))
        o.set(b.toString(), Json.read(v.toString()));
      else if (v instanceof Number || v instanceof Boolean)
        o.set(b.toString(), Json.M.valueToTree(v));
      else o.put(b.toString(), v instanceof Timestamp t ? t.toInstant().toString() : v.toString());
    }
    return o;
  }

  public JsonNode idem(Ctx c, String op, String key, JsonNode body, Supplier<JsonNode> work) {
    Problem.require(
        key != null && !key.isBlank(), 400, "IDEMPOTENCY_REQUIRED", "写操作必须提供Idempotency-Key");
    Problem.require(key.length() <= 200, 400, "BAD_KEY", "幂等键过长");
    return tx(
        () -> {
          String h = Json.hash(Json.canonical(body));
          jdbc.queryForObject(
              "select pg_advisory_xact_lock(hashtextextended(?,0))",
              Object.class,
              c.tenant() + ":" + c.user() + ":" + op + ":" + key);
          var old =
              maybe(
                  "select * from idempotency_record where tenant_id=? and client=? and operation=?"
                      + " and key=?",
                  c.tid(),
                  c.user(),
                  op,
                  key);
          if (old != null) {
            Problem.require(
                h.equals(Json.text(old, "bodyHash")), 409, "IDEMPOTENCY_CONFLICT", "相同幂等键的请求内容不同");
            return old.get("result");
          }
          JsonNode res = work.get();
          run(
              "insert into idempotency_record(tenant_id,client,operation,key,body_hash,result)"
                  + " values(?,?,?,?,?,?::jsonb)",
              c.tid(),
              c.user(),
              op,
              key,
              h,
              Json.str(res));
          return res;
        });
  }

  public void log(
      Ctx c,
      String type,
      String id,
      long version,
      String action,
      String reason,
      JsonNode diff,
      String category) {
    run(
        "insert into"
            + " operation_log(id,tenant_id,object_id,object_type,category_id,version,action,actor,reason,diff,trace_id)"
            + " values(?,?,?,?,?,?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        c.tid(),
        id,
        type,
        category == null ? null : UUID.fromString(category),
        version,
        action,
        c.user(),
        reason,
        Json.str(diff),
        c.trace());
  }

  public static void version(JsonNode n, String expected) {
    Problem.require(
        expected != null && !expected.isBlank(), 428, "VERSION_REQUIRED", "请携带If-Match版本条件");
    Problem.require(
        expected.replace("\"", "").equals(n.path("rowVersion").asText()),
        409,
        "VERSION_CONFLICT",
        "记录已变更，请重新加载和比较");
  }
}
