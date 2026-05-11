CREATE INDEX idx_audit_events_occurred_at_id ON audit_events (occurred_at, id);
CREATE INDEX idx_audit_events_aggregate_occurred_at_id ON audit_events (aggregate_id, occurred_at, id);
