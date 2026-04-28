ALTER TABLE audit_events
    ADD COLUMN archived_at TIMESTAMPTZ;

CREATE INDEX idx_audit_events_archived_at ON audit_events (archived_at);
