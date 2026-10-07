CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE tenant (
 id uuid PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL,
 status text NOT NULL CHECK(status IN ('DRAFT','ACTIVE','SUSPENDED','ARCHIVED')),
 row_version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE app_user (id uuid PRIMARY KEY, code text UNIQUE NOT NULL, name text NOT NULL,
 password_hash text NOT NULL, platform_admin boolean NOT NULL DEFAULT false);
CREATE TABLE membership (tenant_id uuid REFERENCES tenant, user_id uuid REFERENCES app_user,
 roles jsonb NOT NULL, category_scope jsonb NOT NULL DEFAULT '["*"]', active boolean NOT NULL DEFAULT true,
 row_version bigint NOT NULL DEFAULT 1, PRIMARY KEY(tenant_id,user_id));
CREATE TABLE role (tenant_id uuid REFERENCES tenant, code text NOT NULL, permissions jsonb NOT NULL,
 row_version bigint NOT NULL DEFAULT 1, PRIMARY KEY(tenant_id,code));
CREATE TABLE auth_session (token_hash text PRIMARY KEY, user_id uuid REFERENCES app_user,
 expires_at timestamptz NOT NULL);
CREATE TABLE caller_system (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant, code text NOT NULL, name text NOT NULL,
 system_type text NOT NULL CHECK(system_type IN ('ERP','PLM','MES','CUSTOM','OTHER')),
 environment text NOT NULL CHECK(environment IN ('DEV','TEST','PROD')),
 status text NOT NULL CHECK(status IN ('DRAFT','ACTIVE','SUSPENDED','RETIRED')),
 credential_hash text NOT NULL, auth_profile_ref text NOT NULL DEFAULT 'API_KEY_SHA256',
 allowed_category_codes jsonb NOT NULL, owner text NOT NULL DEFAULT '', description text NOT NULL DEFAULT '',
 rate_limit integer NOT NULL DEFAULT 10000 CHECK(rate_limit BETWEEN 1 AND 100000),
 row_version bigint NOT NULL DEFAULT 1, created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,code), UNIQUE(tenant_id,id));
CREATE TABLE category (id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant, code text NOT NULL,
 enabled boolean NOT NULL DEFAULT true, row_version bigint NOT NULL DEFAULT 1,
 UNIQUE(tenant_id,code), UNIQUE(tenant_id,id));
CREATE TABLE config_version (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant, category_id uuid NOT NULL,
 kind text NOT NULL CHECK(kind IN ('CATEGORY','SCHEMA','INPUT_PROFILE','VALIDATION','DERIVATION','IDENTITY','CODE_RULE','DICTIONARY')),
 code text NOT NULL, version integer NOT NULL, status text NOT NULL DEFAULT 'DRAFT'
 CHECK(status IN ('DRAFT','REVIEW','PUBLISHED','RETIRED')), ever_published boolean NOT NULL DEFAULT false,
 definition jsonb NOT NULL, created_by uuid NOT NULL REFERENCES app_user, row_version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(tenant_id,id), UNIQUE(tenant_id,kind,code,version),
 FOREIGN KEY(tenant_id,category_id) REFERENCES category(tenant_id,id));
CREATE TABLE release_package (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant, category_id uuid NOT NULL,
 code text NOT NULL, version integer NOT NULL, status text NOT NULL DEFAULT 'DRAFT'
 CHECK(status IN ('DRAFT','REVIEW','PUBLISHED','RETIRED')), ever_published boolean NOT NULL DEFAULT false,
 refs jsonb NOT NULL, samples jsonb NOT NULL, dependency_snapshot jsonb, tests jsonb,
 created_by uuid NOT NULL REFERENCES app_user, submitted_by uuid REFERENCES app_user,
 approved_by uuid REFERENCES app_user, row_version bigint NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT now(), published_at timestamptz,
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,code,version),
 FOREIGN KEY(tenant_id,category_id) REFERENCES category(tenant_id,id));
CREATE UNIQUE INDEX one_published_release ON release_package(tenant_id,category_id) WHERE status='PUBLISHED';
CREATE TABLE material_assignment (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant, category_id uuid NOT NULL,
 material_no varchar(128) NOT NULL, identity_canonical text NOT NULL, identity_hash text NOT NULL,
 collision_index integer NOT NULL, release_id uuid NOT NULL,
 schema_version_id uuid NOT NULL, input_profile_version_id uuid,
 validation_rule_version_ids jsonb NOT NULL, derivation_rule_version_ids jsonb NOT NULL,
 identity_definition_version_id uuid NOT NULL, code_rule_version_id uuid NOT NULL, category_version_id uuid NOT NULL,
 input_snapshot jsonb NOT NULL, mapped_snapshot jsonb NOT NULL, normalized_snapshot jsonb NOT NULL,
 derived_snapshot jsonb NOT NULL, code_segment_snapshot jsonb NOT NULL, validation_snapshot jsonb NOT NULL,
 issue_request_id text NOT NULL, issued_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,material_no), UNIQUE(tenant_id,category_id,identity_hash,collision_index),
 FOREIGN KEY(tenant_id,category_id) REFERENCES category(tenant_id,id),
 FOREIGN KEY(tenant_id,release_id) REFERENCES release_package(tenant_id,id),
 FOREIGN KEY(tenant_id,schema_version_id) REFERENCES config_version(tenant_id,id),
 FOREIGN KEY(tenant_id,input_profile_version_id) REFERENCES config_version(tenant_id,id),
 FOREIGN KEY(tenant_id,identity_definition_version_id) REFERENCES config_version(tenant_id,id),
 FOREIGN KEY(tenant_id,code_rule_version_id) REFERENCES config_version(tenant_id,id),
 FOREIGN KEY(tenant_id,category_version_id) REFERENCES config_version(tenant_id,id));
CREATE INDEX identity_lookup ON material_assignment(tenant_id,category_id,identity_hash);
CREATE INDEX assignment_time ON material_assignment(tenant_id,issued_at DESC,id);
CREATE TABLE source_binding (
 tenant_id uuid NOT NULL, caller_system_id uuid NOT NULL, category_id uuid NOT NULL,
 source_record_key varchar(1000) NOT NULL, assignment_id uuid NOT NULL,
 bound_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,caller_system_id,category_id,source_record_key),
 FOREIGN KEY(tenant_id,caller_system_id) REFERENCES caller_system(tenant_id,id),
 FOREIGN KEY(tenant_id,category_id) REFERENCES category(tenant_id,id),
 FOREIGN KEY(tenant_id,assignment_id) REFERENCES material_assignment(tenant_id,id));
CREATE TABLE assignment_idempotency (
 tenant_id uuid NOT NULL, caller_system_id uuid NOT NULL, idempotency_key varchar(200) NOT NULL,
 body_hash text NOT NULL, result jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,caller_system_id,idempotency_key),
 FOREIGN KEY(tenant_id,caller_system_id) REFERENCES caller_system(tenant_id,id));
CREATE TABLE sequence_counter (
 tenant_id uuid NOT NULL, code_rule_version_id uuid NOT NULL, sequence_name text NOT NULL,
 period_key text NOT NULL, last_value bigint NOT NULL CHECK(last_value>0),
 PRIMARY KEY(tenant_id,code_rule_version_id,sequence_name,period_key),
 FOREIGN KEY(tenant_id,code_rule_version_id) REFERENCES config_version(tenant_id,id));
CREATE TABLE operation_log (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant, actor text NOT NULL,
 category_code text, object_type text NOT NULL, object_id text NOT NULL, action text NOT NULL,
 details jsonb NOT NULL, request_id text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX audit_time ON operation_log(tenant_id,created_at DESC);
CREATE TABLE request_metric (
 id bigserial PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES tenant, caller_code text,
 category_code text, request_id text NOT NULL, operation text NOT NULL, status integer NOT NULL,
 code text NOT NULL, latency_ms numeric NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX metric_time ON request_metric(tenant_id,created_at DESC);
CREATE TABLE rate_bucket (
 tenant_id uuid, caller_system_id uuid, category_code text, minute bigint, requests integer NOT NULL,
 PRIMARY KEY(tenant_id,caller_system_id,category_code,minute));
CREATE FUNCTION forbid_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'IMMUTABLE_FACT' USING ERRCODE='23514'; END $$;
CREATE TRIGGER immutable_assignment BEFORE UPDATE OR DELETE ON material_assignment FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER immutable_binding BEFORE UPDATE OR DELETE ON source_binding FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER immutable_idempotency BEFORE UPDATE OR DELETE ON assignment_idempotency FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE TRIGGER immutable_audit BEFORE UPDATE OR DELETE ON operation_log FOR EACH ROW EXECUTE FUNCTION forbid_mutation();
CREATE FUNCTION guard_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
  IF OLD.ever_published THEN RAISE EXCEPTION 'IMMUTABLE_PUBLISHED_VERSION' USING ERRCODE='23514'; END IF;
  RETURN OLD;
 END IF;
 IF (NEW.id,NEW.tenant_id,NEW.category_id,NEW.code,NEW.version) IS DISTINCT FROM
    (OLD.id,OLD.tenant_id,OLD.category_id,OLD.code,OLD.version) THEN
  RAISE EXCEPTION 'IMMUTABLE_VERSION_KEY' USING ERRCODE='23514';
 END IF;
 IF OLD.ever_published THEN
  IF NOT NEW.ever_published OR NEW.status NOT IN ('PUBLISHED','RETIRED') THEN
   RAISE EXCEPTION 'IMMUTABLE_PUBLISHED_VERSION' USING ERRCODE='23514';
  END IF;
  IF TG_TABLE_NAME='config_version' AND NEW.definition IS DISTINCT FROM OLD.definition THEN
   RAISE EXCEPTION 'IMMUTABLE_PUBLISHED_CONFIG' USING ERRCODE='23514';
  END IF;
  IF TG_TABLE_NAME='release_package' THEN
   IF (NEW.refs,NEW.samples,NEW.dependency_snapshot,NEW.tests,NEW.submitted_by,NEW.approved_by,NEW.published_at)
    IS DISTINCT FROM (OLD.refs,OLD.samples,OLD.dependency_snapshot,OLD.tests,OLD.submitted_by,OLD.approved_by,OLD.published_at) THEN
    RAISE EXCEPTION 'IMMUTABLE_PUBLISHED_RELEASE' USING ERRCODE='23514';
   END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER config_immutable BEFORE UPDATE OR DELETE ON config_version FOR EACH ROW EXECUTE FUNCTION guard_version();
CREATE TRIGGER release_immutable BEFORE UPDATE OR DELETE ON release_package FOR EACH ROW EXECUTE FUNCTION guard_version();
-- Long canonicals are compared in full; hash collisions may occupy separate slots.
CREATE FUNCTION guard_full_identity() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.identity_hash <> encode(digest(NEW.identity_canonical,'sha256'),'hex') THEN
  RAISE EXCEPTION 'INVALID_IDENTITY_HASH' USING ERRCODE='23514';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text||NEW.category_id::text||NEW.identity_hash,0));
 IF EXISTS(SELECT 1 FROM material_assignment WHERE tenant_id=NEW.tenant_id AND category_id=NEW.category_id
   AND identity_hash=NEW.identity_hash AND identity_canonical=NEW.identity_canonical) THEN
  RAISE EXCEPTION 'DUPLICATE_FULL_IDENTITY' USING ERRCODE='23505';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER full_identity_unique BEFORE INSERT ON material_assignment FOR EACH ROW EXECUTE FUNCTION guard_full_identity();
CREATE FUNCTION guard_sequence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'SEQUENCE_NOT_RECYCLABLE' USING ERRCODE='23514'; END IF;
 IF NEW.last_value<=OLD.last_value OR (NEW.tenant_id,NEW.code_rule_version_id,NEW.sequence_name,NEW.period_key)
  IS DISTINCT FROM (OLD.tenant_id,OLD.code_rule_version_id,OLD.sequence_name,OLD.period_key) THEN
  RAISE EXCEPTION 'SEQUENCE_CANNOT_REWIND' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER monotonic_sequence BEFORE UPDATE OR DELETE ON sequence_counter FOR EACH ROW EXECUTE FUNCTION guard_sequence();
