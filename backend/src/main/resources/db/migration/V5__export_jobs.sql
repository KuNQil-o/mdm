create table export_job (
 id uuid primary key, tenant_id uuid not null references tenant(id), category_code text,
 actor uuid not null references business_user(id), status text not null default 'PENDING'
 check(status in ('PENDING','PROCESSING','COMPLETED','FAILED')),
 config jsonb not null, file_id uuid, error text, row_version bigint not null default 1,
 created_at timestamptz not null default now(), completed_at timestamptz,
 foreign key(tenant_id,file_id) references file_record(tenant_id,id), unique(tenant_id,id)
);
create index export_job_pending on export_job(tenant_id,status,created_at);
