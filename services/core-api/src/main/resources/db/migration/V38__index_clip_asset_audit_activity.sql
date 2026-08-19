CREATE INDEX ix_audit_event_project_clip_asset_time
    ON audit_event (
        project_id,
        (details_json ->> 'clip_asset_id'),
        occurred_at_utc DESC,
        event_id DESC
    )
    WHERE details_json ? 'clip_asset_id';
