package com.acme.mdm;

import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class Monitoring {
  final Db db;
  final SourceMetadata sources;
  final Stats stats;

  public Monitoring(Db d, SourceMetadata s, Stats m) {
    db = d;
    sources = s;
    stats = m;
  }

  public ObjectNode read(Ctx c) {
    c.check("READ", null);
    ArrayNode results = Json.arr();
    for (var source : sources.systems(c)) {
      String sid = source.path("id").asText();
      var id = SourceMetadata.id(sid);
      var row =
          db.one(
              "select count(*) filter(where state in"
                  + " ('PENDING','SENDING','RETRY_WAIT','WAIT_CONFIRMATION')) as"
                  + " pending_writebacks,count(*) filter(where state='RESULT_UNKNOWN') as"
                  + " result_unknown,count(*) filter(where state='FAILED') as"
                  + " failed_writebacks,coalesce(max(extract(epoch from now()-created_at))"
                  + " filter(where state in"
                  + " ('PENDING','SENDING','RETRY_WAIT','WAIT_CONFIRMATION','RESULT_UNKNOWN')),0)"
                  + " as oldest_pending_writeback_seconds from writeback_task where tenant_id=? and"
                  + " source_system_id=?",
              c.tid(),
              id);
      row.put("sourceSystemId", sid);
      row.put("sourceCode", source.path("code").asText());
      row.set(
          "connectionHealth",
          sources.health.getOrDefault(
              sid, Json.object("status", "未采集", "note", "连接测试或来源读取后更新；重启后重新采集")));
      row.set(
          "datasets",
          Json.M.valueToTree(
              db.list(
                  "select d.dataset_code,d.enabled,extract(epoch from now()-d.next_scan_at) as"
                      + " overdue_seconds,d.cursor from dataset_runtime d join release_package r on"
                      + " r.tenant_id=d.tenant_id and r.id=d.release_id where d.tenant_id=? and"
                      + " r.source_system_id=?",
                  c.tid(),
                  id)));
      row.set(
          "processing",
          db.one(
              "select count(*) filter(where t.status in ('DISCOVERED','PROCESSING','RETRY_WAIT'))"
                  + " as pending_candidates,count(*) filter(where t.error_code='VALIDATION_ERROR')"
                  + " as validation_failed,count(*) filter(where t.error_code='IDENTITY_CONFLICT')"
                  + " as identity_conflicts from processing_task t join release_package r on"
                  + " r.tenant_id=t.tenant_id and r.id=t.release_id where t.tenant_id=? and"
                  + " r.source_system_id=?",
              c.tid(),
              id));
      row.put(
          "reconciliationDifferences",
          db.count(
              "select coalesce(sum(jsonb_array_length(results)),0) from numbering_reconciliation"
                  + " where tenant_id=? and source_system_id=? and created_at>now()-interval '1"
                  + " day'",
              c.tid(),
              id));
      results.add(row);
    }
    return Json.object(
        "sources",
        results,
        "numbering",
        stats.numberingMetrics(c.tenant()),
        "databaseLockWaiting",
        db.count(
            "select count(*) from pg_locks where not granted and database=(select oid from"
                + " pg_database where datname=current_database())"),
        "databaseLockScope",
        "当前共享平台数据库，非单租户",
        "sourceOfRecord",
        "ERP");
  }
}
