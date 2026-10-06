-- Full canonical Identity remains authoritative. Hash collisions receive separate slots.
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE material_identity_registry(tenant_id uuid NOT NULL,category_id uuid NOT NULL,identity_hash text NOT NULL,slot bigint NOT NULL,canonical text NOT NULL,PRIMARY KEY(tenant_id,category_id,identity_hash,slot),FOREIGN KEY(tenant_id,category_id) REFERENCES category(tenant_id,id));
INSERT INTO material_identity_registry SELECT DISTINCT tenant_id,category_id,identity_hash,0,identity_canonical FROM material_assignment;
ALTER TABLE material_assignment ADD COLUMN identity_slot bigint NOT NULL DEFAULT 0;
ALTER TABLE material_assignment DROP CONSTRAINT material_assignment_tenant_id_category_id_identity_canonica_key;
ALTER TABLE material_assignment ADD CONSTRAINT assignment_identity_slot_unique UNIQUE(tenant_id,category_id,identity_hash,identity_slot);
ALTER TABLE material_assignment ADD CONSTRAINT assignment_identity_registry_fk FOREIGN KEY(tenant_id,category_id,identity_hash,identity_slot) REFERENCES material_identity_registry(tenant_id,category_id,identity_hash,slot);
CREATE FUNCTION claim_full_material_identity() RETURNS trigger LANGUAGE plpgsql AS $$ DECLARE bucket bigint; BEGIN
 NEW.identity_hash:=encode(digest(NEW.identity_canonical,'sha256'),'hex');
 PERFORM pg_advisory_xact_lock(hashtextextended(NEW.tenant_id::text||':'||NEW.category_id::text||':'||NEW.identity_hash,0));
 SELECT slot INTO bucket FROM material_identity_registry WHERE tenant_id=NEW.tenant_id AND category_id=NEW.category_id AND identity_hash=NEW.identity_hash AND canonical=NEW.identity_canonical;
 IF bucket IS NULL THEN
 SELECT coalesce(max(slot),-1)+1 INTO bucket FROM material_identity_registry WHERE tenant_id=NEW.tenant_id AND category_id=NEW.category_id AND identity_hash=NEW.identity_hash;
 INSERT INTO material_identity_registry VALUES(NEW.tenant_id,NEW.category_id,NEW.identity_hash,bucket,NEW.identity_canonical);
 END IF;
 NEW.identity_slot:=bucket; RETURN NEW; END; $$;
CREATE TRIGGER claim_identity BEFORE INSERT ON material_assignment FOR EACH ROW EXECUTE FUNCTION claim_full_material_identity();
