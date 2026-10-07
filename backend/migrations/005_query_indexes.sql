CREATE INDEX assignment_category_time ON material_assignment(tenant_id,category_id,issued_at DESC,id);
CREATE INDEX binding_assignment_lookup ON source_binding(tenant_id,assignment_id,caller_system_id);
CREATE INDEX binding_caller_source_lookup ON source_binding(tenant_id,caller_system_id,source_record_key,category_id);
CREATE INDEX release_category_lookup ON release_package(tenant_id,category_id,status);
CREATE INDEX config_category_versions ON config_version(tenant_id,category_id,kind,version DESC);
CREATE INDEX assignment_request_lookup ON material_assignment(tenant_id,issue_request_id);
