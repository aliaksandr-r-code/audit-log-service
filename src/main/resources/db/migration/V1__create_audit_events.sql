CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    aggregate_id VARCHAR(255) NOT NULL,
    action VARCHAR(120) NOT NULL,
    actor VARCHAR(255) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_audit_events_aggregate_id ON audit_events (aggregate_id);
CREATE INDEX idx_audit_events_action ON audit_events (action);
CREATE INDEX idx_audit_events_actor ON audit_events (actor);
CREATE INDEX idx_audit_events_occurred_at ON audit_events (occurred_at);
