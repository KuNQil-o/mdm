ALTER TABLE processing_task ADD CONSTRAINT processing_job_source UNIQUE(tenant_id,scan_job_id,source_record_key);
CREATE OR REPLACE FUNCTION guard_v3_versions() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN IF OLD.ever_published THEN RAISE EXCEPTION 'IMMUTABLE_PUBLISHED_CONFIGURATION'; END IF; RETURN OLD; END IF;
 IF OLD.ever_published AND (to_jsonb(NEW)-'status'-'row_version'-'tests') IS DISTINCT FROM (to_jsonb(OLD)-'status'-'row_version'-'tests') THEN RAISE EXCEPTION 'IMMUTABLE_PUBLISHED_CONFIGURATION'; END IF;
 IF OLD.ever_published AND NEW.status NOT IN('PUBLISHED','RETIRED') THEN RAISE EXCEPTION 'PUBLISHED_CANNOT_BECOME_DRAFT'; END IF;
 RETURN NEW; END; $$;
