ALTER TABLE file_record ADD CONSTRAINT file_tenant_id UNIQUE(tenant_id,id);
ALTER TABLE file_record ADD CONSTRAINT file_category_tenant FOREIGN KEY(tenant_id,category_id) REFERENCES category(tenant_id,id);
ALTER TABLE import_job ADD CONSTRAINT import_file_tenant FOREIGN KEY(tenant_id,file_id) REFERENCES file_record(tenant_id,id);
ALTER TABLE material_request ADD CONSTRAINT request_source_tenant FOREIGN KEY(tenant_id,source_system_id) REFERENCES integration_system(tenant_id,id);
ALTER TABLE delivery ADD COLUMN send_attempts int NOT NULL DEFAULT 0;
UPDATE delivery SET send_attempts=attempts WHERE attempts>0;
ALTER TABLE delivery ADD COLUMN confirmation_started_at timestamptz;
