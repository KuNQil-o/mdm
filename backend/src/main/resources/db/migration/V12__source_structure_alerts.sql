-- An observed structure change alerts consumers without rewriting pinned configurations.
CREATE TABLE source_structure_alert(
 id uuid PRIMARY KEY,tenant_id uuid NOT NULL,source_system_id uuid NOT NULL,
 old_object_id uuid NOT NULL,new_object_id uuid NOT NULL,
 incompatible boolean NOT NULL,details jsonb NOT NULL,created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,old_object_id,new_object_id),
 FOREIGN KEY(tenant_id,source_system_id) REFERENCES source_system(tenant_id,id),
 FOREIGN KEY(tenant_id,old_object_id) REFERENCES source_object_version(tenant_id,id),
 FOREIGN KEY(tenant_id,new_object_id) REFERENCES source_object_version(tenant_id,id)
);
