CREATE TABLE caller_nonce (
 tenant_id uuid NOT NULL, caller_system_id uuid NOT NULL, nonce varchar(100) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(tenant_id,caller_system_id,nonce),
 FOREIGN KEY(tenant_id,caller_system_id) REFERENCES caller_system(tenant_id,id));
CREATE INDEX nonce_cleanup ON caller_nonce(created_at);
CREATE TABLE login_bucket (client_hash text NOT NULL, minute bigint NOT NULL, attempts integer NOT NULL,
 PRIMARY KEY(client_hash,minute));
