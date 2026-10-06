ALTER TABLE integration_system ADD COLUMN next_send_at timestamptz NOT NULL DEFAULT now();
