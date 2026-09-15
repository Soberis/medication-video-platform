-- Nullable context preserves historical jobs without inventing their schema.
ALTER TABLE ai_job
    ADD COLUMN annotation_task_id UUID,
    ADD COLUMN label_schema_id UUID,
    ADD COLUMN label_schema_digest VARCHAR(64),
    ADD COLUMN allowed_labels JSONB,
    ADD COLUMN execution_metadata JSONB;

ALTER TABLE action_candidate
    ADD COLUMN annotation_task_id UUID,
    ADD COLUMN label_schema_id UUID,
    ADD COLUMN label_schema_digest VARCHAR(64),
    ALTER COLUMN confidence DROP NOT NULL;
