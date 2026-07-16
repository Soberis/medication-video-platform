CREATE TABLE audit_event (
    event_id UUID PRIMARY KEY,
    occurred_at_utc TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_type VARCHAR(32) NOT NULL,
    actor_id VARCHAR(128) NOT NULL,
    project_id UUID,
    action VARCHAR(128) NOT NULL,
    object_type VARCHAR(64) NOT NULL,
    object_id VARCHAR(128) NOT NULL,
    object_version VARCHAR(64),
    outcome VARCHAR(32) NOT NULL,
    reason_code VARCHAR(64) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    trace_id VARCHAR(128),
    authorization_reference VARCHAR(128),
    details_json JSONB NOT NULL,
    CONSTRAINT ck_audit_event_actor_type
        CHECK (actor_type IN ('USER', 'SERVICE', 'SYSTEM')),
    CONSTRAINT ck_audit_event_outcome
        CHECK (outcome IN ('SUCCEEDED', 'DENIED', 'FAILED')),
    CONSTRAINT ck_audit_event_actor_id
        CHECK (actor_id ~ '^[A-Za-z0-9][A-Za-z0-9._:@-]{0,127}$'),
    CONSTRAINT ck_audit_event_action
        CHECK (action ~ '^[A-Z][A-Z0-9_]{1,127}$'),
    CONSTRAINT ck_audit_event_object_type
        CHECK (object_type ~ '^[A-Za-z][A-Za-z0-9_]{0,63}$'),
    CONSTRAINT ck_audit_event_reason_code
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    CONSTRAINT ck_audit_event_request_id
        CHECK (request_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_audit_event_details_object
        CHECK (jsonb_typeof(details_json) = 'object')
);

CREATE INDEX ix_audit_event_project_time
    ON audit_event (project_id, occurred_at_utc DESC, event_id DESC);

CREATE INDEX ix_audit_event_object_time
    ON audit_event (object_type, object_id, occurred_at_utc DESC, event_id DESC);

CREATE FUNCTION reject_audit_event_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only';
END;
$$;

CREATE TRIGGER trg_audit_event_append_only
BEFORE UPDATE OR DELETE ON audit_event
FOR EACH ROW
EXECUTE FUNCTION reject_audit_event_mutation();
