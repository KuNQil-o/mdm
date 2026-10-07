-- Deterministic trace roots retain correlation for existing immutable facts too.
ALTER TABLE material_assignment ADD COLUMN issue_trace_id text
 GENERATED ALWAYS AS (substr(encode(digest(issue_request_id,'sha256'),'hex'),1,32)) STORED;
ALTER TABLE operation_log ADD COLUMN trace_id text
 GENERATED ALWAYS AS (substr(encode(digest(request_id,'sha256'),'hex'),1,32)) STORED;
ALTER TABLE request_metric ADD COLUMN trace_id text
 GENERATED ALWAYS AS (substr(encode(digest(request_id,'sha256'),'hex'),1,32)) STORED;
CREATE INDEX assignment_trace ON material_assignment(tenant_id,issue_trace_id);
CREATE INDEX metric_trace ON request_metric(tenant_id,trace_id);
