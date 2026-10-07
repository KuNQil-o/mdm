ALTER TABLE request_metric ADD COLUMN outcome text NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE request_metric ADD COLUMN sequence_latency_ms numeric;
CREATE INDEX caller_metric_time ON request_metric(tenant_id,caller_code,created_at DESC);
