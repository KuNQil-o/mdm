package com.acme.mdm;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class Deliveries {
  @org.springframework.beans.factory.annotation.Value("${mdm.legacy-v2:false}")
  boolean legacyV2;

  final Db db;
  final Models models;
  final Integrations integrations;
  final Materials materials;
  final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  final int[] delays;

  public Deliveries(
      Db d, Models m, Integrations i, Materials a, @Value("${mdm.retry-delays}") String retry) {
    db = d;
    models = m;
    integrations = i;
    materials = a;
    delays = Arrays.stream(retry.split(",")).mapToInt(Integer::parseInt).toArray();
  }

  public List<ObjectNode> list(Ctx c) {
    c.check("INTEGRATE", null);
    return db
        .list(
            "select d.*,s.name as system_name,s.code as system_code,e.material_id,e.row_version as"
                + " event_version,e.previous_published_version,c.code as category_code from"
                + " delivery d join integration_system s on d.system_id=s.id join outbox_event e on"
                + " d.event_id=e.id join material m on m.id=e.material_id join category c on"
                + " c.id=m.category_id where d.tenant_id=? order by d.created_at desc",
            c.tid())
        .stream()
        .filter(x -> c.allows(Json.text(x, "categoryCode")))
        .toList();
  }

  public ObjectNode get(Ctx c, String id) {
    c.check("INTEGRATE", null);
    var d = db.one("select * from delivery where tenant_id=? and id=?", c.tid(), models.uuid(id));
    var e =
        db.one(
            "select * from outbox_event where tenant_id=? and id=?",
            c.tid(),
            models.uuid(Json.text(d, "eventId")));
    materials.get(c, Json.text(e, "materialId"), false);
    d.set("event", e);
    d.set(
        "attemptLogs",
        Json.M.valueToTree(
            db.list(
                "select * from delivery_attempt where tenant_id=? and delivery_id=? order by"
                    + " created_at",
                c.tid(),
                models.uuid(id))));
    var f =
        db.maybe(
            "select id,name from file_record where tenant_id=? and query->>'deliveryId'=?",
            c.tid(),
            id);
    if (f != null) d.set("file", f);
    return d;
  }

  public JsonNode resend(Ctx c, String id, JsonNode b) {
    var d = get(c, id);
    Problem.require(
        Set.of("FAILED", "RESULT_UNKNOWN").contains(Json.text(d, "state")),
        409,
        "DELIVERY_STATE",
        "仅失败或已核实的未知结果可补发");
    Problem.require(!Json.text(b, "reason").isBlank(), 422, "REASON_REQUIRED", "补发需填写核对原因");
    if (Json.text(d, "state").equals("RESULT_UNKNOWN"))
      Problem.require(
          b.path("confirmedNotCreated").asBoolean(), 422, "CONFIRM_REQUIRED", "结果未知须先确认目标未创建");
    String map = b.path("mappingId").asText(Json.text(d, "mappingId"));
    var m = integrations.mapping(c, map);
    Problem.require(
        Json.text(m, "state").equals("PUBLISHED") && m.path("systemId").equals(d.path("systemId")),
        422,
        "MAPPING_SYSTEM",
        "补发映射必须属于相同系统且已发布");
    UUID next = UUID.randomUUID();
    long rev =
        db.count(
            "select coalesce(max(revision),0)+1 from delivery where tenant_id=? and event_id=? and"
                + " system_id=?",
            c.tid(),
            models.uuid(Json.text(d, "eventId")),
            models.uuid(Json.text(d, "systemId")));
    db.run(
        "insert into delivery(id,tenant_id,event_id,system_id,mapping_id,parent_id,revision)"
            + " values(?,?,?,?,?,?,?)",
        next,
        c.tid(),
        models.uuid(Json.text(d, "eventId")),
        models.uuid(Json.text(d, "systemId")),
        models.uuid(map),
        models.uuid(id),
        rev);
    db.log(
        c,
        "DELIVERY",
        next.toString(),
        0,
        "RESEND",
        Json.text(b, "reason"),
        Json.object("parentId", id, "mappingId", map),
        null);
    return get(c, next.toString());
  }

  public JsonNode confirm(Ctx c, String id, JsonNode b) {
    var d = get(c, id);
    Problem.require(
        Set.of("WAIT_CONFIRMATION", "RESULT_UNKNOWN").contains(Json.text(d, "state")),
        409,
        "DELIVERY_STATE",
        "只有待确认或结果未知可人工确认");
    Problem.require(!Json.text(b, "reason").isBlank(), 422, "REASON_REQUIRED", "人工确认需要核实原因");
    boolean success = b.path("success").asBoolean();
    var event = d.path("event");
    finish(
        d,
        event,
        success ? "SUCCEEDED" : "FAILED",
        200,
        b,
        success ? null : Json.text(b, "reason"),
        b.path("externalId").asText(""));
    db.log(c, "DELIVERY", id, 0, "MANUAL_CONFIRM", Json.text(b, "reason"), b, null);
    return get(c, id);
  }

  @Scheduled(fixedDelayString = "${mdm.worker-delay:1000}")
  public void work() {
    if (!legacyV2) return;
    for (int n = 0; n < 10; n++) {
      ObjectNode d = claim();
      if (d == null) break;
      try {
        send(d);
      } catch (Exception e) {
        db.tx(
            () -> {
              db.run(
                  "update delivery set state='RESULT_UNKNOWN',lease_until=null,error=? where id=?"
                      + " and state='SENDING' and attempts=?",
                  e.getClass().getSimpleName() + ": " + e.getMessage(),
                  models.uuid(Json.text(d, "id")),
                  d.path("attempts").asInt());
              return null;
            });
      }
    }
  }

  ObjectNode claim() {
    return db.tx(
        () -> {
          var d =
              db.maybe(
                  """
select d.* from delivery d join tenant t on t.id=d.tenant_id join integration_system s on s.id=d.system_id join outbox_event e on e.id=d.event_id
where t.status='ACTIVE' and s.active and not s.paused and s.next_send_at<=now()
and ((d.state in ('PENDING','RETRY_WAIT','WAIT_CONFIRMATION') and d.next_attempt_at<=now()) or (d.state='SENDING' and d.lease_until<now()))
and not (d.state='WAIT_CONFIRMATION' and coalesce(s.config->>'transport','REST')='FILE')
and not exists(select 1 from delivery newer where newer.tenant_id=d.tenant_id and newer.system_id=d.system_id and newer.event_id=d.event_id and newer.revision>d.revision)
and not exists(select 1 from outbox_event prev join delivery pd on pd.event_id=prev.id where prev.tenant_id=e.tenant_id and prev.material_id=e.material_id and prev.row_version=e.previous_published_version and pd.system_id=d.system_id and pd.state<>'SUCCEEDED' and not exists(select 1 from delivery pn where pn.event_id=pd.event_id and pn.system_id=pd.system_id and pn.revision>pd.revision))
order by d.created_at for update of d,s skip locked limit 1
""");
          if (d == null) return null;
          db.run(
              "update integration_system set"
                  + " next_send_at=now()+(coalesce((config->>'minimumIntervalMs')::int,0) *"
                  + " interval '1 millisecond') where id=?",
              models.uuid(Json.text(d, "systemId")));
          String prior = Json.text(d, "state");
          db.run(
              "update delivery set state='SENDING',lease_until=now()+interval '90"
                  + " seconds',attempts=attempts+1 where id=?",
              models.uuid(Json.text(d, "id")));
          d.put("attempts", d.path("attempts").asInt() + 1);
          d.put("confirmOnly", prior.equals("WAIT_CONFIRMATION") || prior.equals("SENDING"));
          d.put("previousState", prior);
          return d;
        });
  }

  void send(ObjectNode d) {
    var event =
        db.one(
            "select * from outbox_event where tenant_id=? and id=?",
            models.uuid(Json.text(d, "tenantId")),
            models.uuid(Json.text(d, "eventId")));
    var sys =
        db.one(
            "select * from integration_system where tenant_id=? and id=?",
            models.uuid(Json.text(d, "tenantId")),
            models.uuid(Json.text(d, "systemId")));
    JsonNode cfg = sys.path("config"),
        mapping =
            db.one(
                    "select config from mapping_version where tenant_id=? and id=?",
                    models.uuid(Json.text(d, "tenantId")),
                    models.uuid(Json.text(d, "mappingId")))
                .path("config");
    ObjectNode payload;
    try {
      payload = integrations.map(mapping, event.path("snapshot").path("data"));
    } catch (Problem p) {
      db.tx(
          () -> {
            finish(d, event, "FAILED", 0, Json.object("code", p.code), p.getMessage(), null);
            return null;
          });
      return;
    }
    if (cfg.path("transport").asText("REST").equals("FILE")) {
      db.tx(
          () -> {
            var existing =
                db.maybe(
                    "select id from file_record where tenant_id=? and query->>'deliveryId'=?",
                    models.uuid(Json.text(d, "tenantId")),
                    Json.text(d, "id"));
            if (existing == null) {
              UUID f = UUID.randomUUID();
              byte[] content =
                  (Json.str(
                              Json.object(
                                  "event",
                                  event.path("snapshot"),
                                  "mappingVersionId",
                                  d.path("mappingId"),
                                  "payload",
                                  payload))
                          + "\n")
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8);
              db.run(
                  "insert into"
                      + " file_record(id,tenant_id,category_id,name,kind,content,row_count,query)"
                      + " values(?,?,?,?,'INTEGRATION',?,1,?::jsonb)",
                  f,
                  models.uuid(Json.text(d, "tenantId")),
                  models.uuid(Json.text(event.path("snapshot").path("data"), "categoryId")),
                  "出站批次-" + Json.text(d, "id") + ".jsonl",
                  content,
                  Json.str(
                      Json.object("deliveryId", d.path("id"), "systemId", d.path("systemId"))));
            }
            finish(
                d,
                event,
                "WAIT_CONFIRMATION",
                202,
                Json.object("fileGenerated", true),
                "等待文件回执",
                null);
            return null;
          });
      return;
    }
    String eventId = Json.text(event, "id");
    String queryUrl = cfg.path("queryUrl").asText("").replace("{eventId}", eventId);
    boolean poll = d.path("confirmOnly").asBoolean();
    if (poll && queryUrl.isEmpty() && !cfg.path("idempotent").asBoolean()) {
      db.tx(
          () -> {
            finish(d, event, "RESULT_UNKNOWN", 0, Json.obj(), "目标无结果查询或幂等能力，需人工核实", null);
            return null;
          });
      return;
    }
    HttpRequest.Builder req =
        HttpRequest.newBuilder(
                URI.create(poll && !queryUrl.isEmpty() ? queryUrl : cfg.path("url").asText()))
            .timeout(Duration.ofMillis(cfg.path("timeoutMs").asInt(3000)))
            .header("Content-Type", "application/json")
            .header("Idempotency-Key", eventId)
            .header("X-Event-ID", eventId)
            .header("X-Tenant-ID", Json.text(d, "tenantId"));
    if (poll && !queryUrl.isEmpty()) req.GET();
    else {
      db.tx(
          () -> {
            db.run(
                "update delivery set send_attempts=send_attempts+1 where id=? and attempts=?",
                models.uuid(Json.text(d, "id")),
                d.path("attempts").asInt());
            return null;
          });
      d.put("sendAttempts", d.path("sendAttempts").asInt() + 1);
      ObjectNode envelope =
          Json.object(
              "eventId",
              eventId,
              "tenantId",
              d.path("tenantId"),
              "materialId",
              event.path("materialId"),
              "rowVersion",
              event.path("rowVersion"),
              "previousPublishedVersion",
              event.path("previousPublishedVersion"),
              "mappingVersionId",
              d.path("mappingId"),
              "data",
              payload);
      req.POST(HttpRequest.BodyPublishers.ofString(Json.str(envelope)));
    }
    try {
      var response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      JsonNode body;
      try {
        body = Json.read(response.body());
      } catch (Problem p) {
        body =
            Json.object(
                "unparsedResponse",
                response.body().substring(0, Math.min(1000, response.body().length())));
      }
      JsonNode result = body;
      String external = Json.path(body, cfg.path("externalIdPath").asText("externalId")).asText("");
      boolean business =
          status >= 200
              && status < 300
              && Json.path(body, cfg.path("successPath").asText("success"))
                  .equals(cfg.has("successValue") ? cfg.get("successValue") : BooleanNode.TRUE);
      String state, error = null;
      if (status == 202) {
        state =
            Duration.between(
                            d.hasNonNull("confirmationStartedAt")
                                ? Instant.parse(d.path("confirmationStartedAt").asText())
                                : Instant.now(),
                            Instant.now())
                        .toSeconds()
                    > cfg.path("confirmationTimeoutSeconds").asLong(3600)
                ? "RESULT_UNKNOWN"
                : "WAIT_CONFIRMATION";
        error = "目标已接收，等待业务确认";
      } else if (poll && status == 404) {
        if (cfg.path("idempotent").asBoolean()) {
          state = "RETRY_WAIT";
          error = "目标查询确认尚未创建，等待带幂等键重试";
        } else {
          state = "RESULT_UNKNOWN";
          error = "目标查询未确认结果，请人工核实";
        }
      } else if (status == 429 || status >= 500) {
        state = d.path("sendAttempts").asInt() <= delays.length ? "RETRY_WAIT" : "FAILED";
        error = "HTTP " + status;
      } else if (business) {
        state = "SUCCEEDED";
        Problem.require(!external.isEmpty(), 422, "EXTERNAL_ID_MISSING", "目标业务成功但未返回外部ID");
      } else {
        state = "FAILED";
        error = "HTTP " + status + "，业务未确认成功";
      }
      String finalState = state, finalError = error;
      db.tx(
          () -> {
            finish(d, event, finalState, status, result, finalError, external);
            if (finalState.equals("RETRY_WAIT")) {
              int sec =
                  delays[
                      Math.min(delays.length - 1, Math.max(0, d.path("sendAttempts").asInt() - 1))];
              if (status == 429) {
                try {
                  sec =
                      Math.max(
                          sec,
                          Math.min(
                              7200,
                              Integer.parseInt(
                                  response.headers().firstValue("Retry-After").orElse("0"))));
                } catch (NumberFormatException ignored) {
                }
              }
              db.run(
                  "update delivery set next_attempt_at=now()+(? * interval '1 second') where id=?",
                  sec,
                  models.uuid(Json.text(d, "id")));
            } else if (finalState.equals("WAIT_CONFIRMATION"))
              db.run(
                  "update delivery set next_attempt_at=now()+(? * interval '1 second') where id=?",
                  cfg.path("confirmationPollSeconds").asInt(10),
                  models.uuid(Json.text(d, "id")));
            return null;
          });
    } catch (Exception e) {
      db.tx(
          () -> {
            String state =
                cfg.path("queryUrl").asText("").isEmpty() && !cfg.path("idempotent").asBoolean()
                    ? "RESULT_UNKNOWN"
                    : "WAIT_CONFIRMATION";
            finish(d, event, state, 0, Json.obj(), "请求结果未确认：" + e.getClass().getSimpleName(), null);
            db.run(
                "update delivery set next_attempt_at=now()+(? * interval '1 second') where id=?",
                cfg.path("confirmationPollSeconds").asInt(10),
                models.uuid(Json.text(d, "id")));
            return null;
          });
    }
  }

  void finish(
      JsonNode d,
      JsonNode event,
      String state,
      int httpStatus,
      JsonNode response,
      String error,
      String external) {
    var current =
        db.one(
            "select * from delivery where tenant_id=? and id=? for update",
            models.uuid(Json.text(d, "tenantId")),
            models.uuid(Json.text(d, "id")));
    if (current.path("attempts").asInt() != d.path("attempts").asInt()
        || Json.text(current, "state").equals("SUCCEEDED")) return;
    UUID tid = models.uuid(Json.text(d, "tenantId"));
    if (state.equals("SUCCEEDED")) {
      Problem.require(
          external != null && !external.isBlank(), 422, "EXTERNAL_ID_MISSING", "确认成功须提供externalId");
      var existing =
          db.maybe(
              "select * from external_identity where tenant_id=? and system_id=? and (external_id=?"
                  + " or material_id=?) for update",
              tid,
              models.uuid(Json.text(d, "systemId")),
              external,
              models.uuid(Json.text(event, "materialId")));
      if (existing != null)
        Problem.require(
            existing.path("materialId").equals(event.path("materialId"))
                && existing.path("externalId").asText().equals(external),
            409,
            "EXTERNAL_ID_CONFLICT",
            "目标身份绑定冲突，需人工核对");
      db.run(
          "insert into"
              + " external_identity(tenant_id,system_id,external_id,material_id,confirmed_version,digest)"
              + " values(?,?,?,?,?,?) on conflict(tenant_id,system_id,external_id) do update set"
              + " confirmed_version=greatest(external_identity.confirmed_version,excluded.confirmed_version),digest=excluded.digest,row_version=external_identity.row_version+1",
          tid,
          models.uuid(Json.text(d, "systemId")),
          external,
          models.uuid(Json.text(event, "materialId")),
          event.path("rowVersion").asLong(),
          Json.hash(Json.canonical(event.path("snapshot").path("data"))));
    }
    db.run(
        "update delivery set"
            + " state=?,lease_until=null,error=?,external_id=?,confirmed_version=?,confirmation_started_at=case"
            + " when ?='WAIT_CONFIRMATION' then coalesce(confirmation_started_at,now()) else"
            + " confirmation_started_at end where tenant_id=? and id=?",
        state,
        error,
        external,
        state.equals("SUCCEEDED") ? event.path("rowVersion").asLong() : null,
        state,
        tid,
        models.uuid(Json.text(d, "id")));
    db.run(
        "insert into"
            + " delivery_attempt(id,tenant_id,delivery_id,attempt,http_status,business_success,response,error)"
            + " values(?,?,?,?,?,?,?::jsonb,?)",
        UUID.randomUUID(),
        tid,
        models.uuid(Json.text(d, "id")),
        d.path("attempts").asInt(),
        httpStatus,
        state.equals("SUCCEEDED"),
        Json.str(response),
        error);
    db.log(
        new Ctx(
            Json.text(d, "tenantId"),
            "DELIVERY_WORKER",
            Set.of(),
            Set.of("*"),
            Json.text(event, "traceId")),
        "DELIVERY",
        Json.text(d, "id"),
        event.path("rowVersion").asLong(),
        state,
        error,
        Json.object(
            "eventId",
            d.path("eventId"),
            "httpStatus",
            httpStatus,
            "businessSuccess",
            state.equals("SUCCEEDED")),
        Json.text(event.path("snapshot").path("data"), "categoryId"));
  }

  @Scheduled(cron = "0 0 2 * * *", zone = "UTC")
  public void dailyReconcile() {
    if (!legacyV2) return;
    for (var s :
        db.list(
            "select s.* from integration_system s join tenant t on t.id=s.tenant_id where s.active"
                + " and t.status='ACTIVE'")) {
      String user =
          s.path("config")
              .path("integrationUserId")
              .asText(s.path("config").path("inboundUserId").asText());
      if (user.isBlank()) continue;
      try {
        db.tx(
            () ->
                reconcile(
                    integrations.auth.worker(Json.text(s, "tenantId"), user), Json.text(s, "id")));
      } catch (Exception e) {
        org.slf4j.LoggerFactory.getLogger(Deliveries.class)
            .warn(
                "Daily reconciliation failed for {}: {}",
                Json.text(s, "id"),
                e.getClass().getSimpleName());
      }
    }
  }

  public JsonNode reconcile(Ctx c, String sid) {
    var sys = integrations.system(c, sid);
    ArrayNode results = Json.arr();
    for (var identity :
        db.list(
            "select i.*,c.code as category_code from external_identity i join material m on"
                + " m.id=i.material_id join category c on c.id=m.category_id where i.tenant_id=?"
                + " and i.system_id=?",
            c.tid(),
            models.uuid(sid))) {
      if (!c.allows(Json.text(identity, "categoryCode"))) continue;
      var material = materials.get(c, Json.text(identity, "materialId"), false);
      var event =
          db.maybe(
              "select * from outbox_event where tenant_id=? and material_id=? and row_version=?",
              c.tid(),
              models.uuid(Json.text(material, "id")),
              material.path("publishedVersion").asLong());
      String url =
          sys.path("config")
              .path("identityQueryUrl")
              .asText("")
              .replace(
                  "{externalId}",
                  URLEncoder.encode(
                      Json.text(identity, "externalId"), java.nio.charset.StandardCharsets.UTF_8));
      ObjectNode row =
          Json.object(
              "materialId",
              identity.path("materialId"),
              "externalId",
              identity.path("externalId"),
              "localVersion",
              material.path("publishedVersion"),
              "confirmedVersion",
              identity.path("confirmedVersion"));
      try {
        Problem.require(!url.isEmpty(), 422, "QUERY_UNSUPPORTED", "目标未配置身份查询");
        var res =
            http.send(
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString());
        var remote = Json.read(res.body());
        boolean version =
            res.statusCode() == 200
                && remote.path("rowVersion").asLong() == material.path("publishedVersion").asLong();
        boolean payload = true;
        if (event != null) {
          var mapId =
              db.one(
                  "select mapping_id from delivery where tenant_id=? and event_id=? and system_id=?"
                      + " order by revision desc limit 1",
                  c.tid(),
                  models.uuid(Json.text(event, "id")),
                  models.uuid(sid));
          var mapping = integrations.mapping(c, Json.text(mapId, "mappingId"));
          payload =
              Json.canonical(remote.path("data"))
                  .equals(
                      Json.canonical(
                          integrations.map(
                              mapping.path("config"), event.path("snapshot").path("data"))));
        }
        row.put("status", version && payload ? "MATCH" : "DIFFERENT");
        row.set("remote", remote);
      } catch (Exception e) {
        row.put("status", "UNCONFIRMED");
        row.put("error", e.getMessage());
      }
      results.add(row);
    }
    // Formal materials without any external identity must also appear in reconciliation.
    for (var missing :
        db.list(
            "select m.id,m.published_version,c.code as category_code from material m join category"
                + " c on c.id=m.category_id where m.tenant_id=? and m.material_no is not null and"
                + " not exists(select 1 from external_identity i where i.tenant_id=m.tenant_id and"
                + " i.system_id=? and i.material_id=m.id)",
            c.tid(),
            models.uuid(sid)))
      if (c.allows(Json.text(missing, "categoryCode")))
        results.add(
            Json.object(
                "materialId",
                missing.path("id"),
                "status",
                "MISSING_EXTERNAL_ID",
                "localVersion",
                missing.path("publishedVersion")));
    UUID id = UUID.randomUUID();
    db.run(
        "insert into reconciliation_job(id,tenant_id,system_id,state,results)"
            + " values(?,?,?,'COMPLETED',?::jsonb)",
        id,
        c.tid(),
        models.uuid(sid),
        Json.str(results));
    db.log(c, "RECONCILIATION", id.toString(), 0, "RECONCILE", "", results, null);
    return Json.object("id", id, "state", "COMPLETED", "results", results);
  }
}
