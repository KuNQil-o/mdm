CREATE FUNCTION immutable_full_identity() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'IMMUTABLE_MATERIAL_IDENTITY'; END; $$;
CREATE TRIGGER identity_registry_immutable BEFORE UPDATE OR DELETE ON material_identity_registry FOR EACH ROW EXECUTE FUNCTION immutable_full_identity();
CREATE FUNCTION immutable_writeback_envelope() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'WRITEBACK_TASK_HISTORY_CANNOT_BE_DELETED'; END IF;
 IF (to_jsonb(NEW)-'state'-'attempts'-'error_code'-'error_details'-'lease_until'-'next_attempt_at'-'confirmed_at') IS DISTINCT FROM (to_jsonb(OLD)-'state'-'attempts'-'error_code'-'error_details'-'lease_until'-'next_attempt_at'-'confirmed_at') THEN RAISE EXCEPTION 'IMMUTABLE_WRITEBACK_ENVELOPE'; END IF; RETURN NEW; END; $$;
CREATE TRIGGER writeback_envelope_guard BEFORE UPDATE OR DELETE ON writeback_task FOR EACH ROW EXECUTE FUNCTION immutable_writeback_envelope();
