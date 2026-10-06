ALTER TABLE material ADD COLUMN code_rule_version_id uuid;
UPDATE material SET code_rule_version_id=schema_version_id WHERE material_no IS NOT NULL AND number_source='GENERATED';
ALTER TABLE material ADD CONSTRAINT material_code_rule_tenant FOREIGN KEY(tenant_id,code_rule_version_id) REFERENCES schema_version(tenant_id,id);
ALTER TABLE material ADD CONSTRAINT material_parse_rule_tenant FOREIGN KEY(tenant_id,parse_rule_version_id) REFERENCES schema_version(tenant_id,id);
