ALTER TABLE operation_log ADD COLUMN version bigint;
ALTER TABLE operation_log ADD COLUMN reason text NOT NULL DEFAULT '';
CREATE INDEX scoped_audit_time ON operation_log(tenant_id,category_code,created_at DESC);
