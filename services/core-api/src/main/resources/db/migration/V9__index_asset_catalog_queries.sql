CREATE INDEX ix_capture_session_project_recorded
    ON capture_session (project_id, device_time_utc DESC, capture_session_id DESC);

CREATE INDEX ix_camera_stream_recorded_asset
    ON camera_stream (record_start_utc DESC, clip_asset_id DESC);
