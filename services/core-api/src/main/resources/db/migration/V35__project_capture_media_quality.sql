UPDATE camera_stream
SET quality_status = CASE stream_status
        WHEN 'INGESTED' THEN 'PASS'
        WHEN 'INGEST_FAILED' THEN 'REJECT'
        ELSE 'PENDING'
    END,
    updated_at = CURRENT_TIMESTAMP
WHERE quality_status = 'PENDING'
  AND stream_status IN ('INGESTED', 'INGEST_FAILED');

WITH stream_projection AS (
    SELECT
        session.capture_session_id,
        count(stream.camera_stream_id)
            FILTER (WHERE stream.stream_status = 'INGESTED')::integer AS received_count,
        count(stream.camera_stream_id)::integer AS bound_count,
        bool_or(stream.quality_status = 'REJECT') AS has_reject,
        bool_or(stream.quality_status = 'WARNING') AS has_warning,
        bool_or(stream.quality_status = 'PENDING') AS has_pending,
        bool_or(stream.stream_status = 'UNAVAILABLE') AS has_unavailable
    FROM capture_session session
    LEFT JOIN camera_stream stream
        ON stream.capture_session_id = session.capture_session_id
    GROUP BY session.capture_session_id
)
UPDATE capture_session session
SET received_camera_count = projection.received_count,
    assembly_status = CASE
        WHEN session.assembly_status IN ('INCOMPLETE', 'CLOSED') THEN session.assembly_status
        WHEN coalesce(projection.has_unavailable, false) THEN 'INCOMPLETE'
        WHEN projection.received_count = session.expected_camera_count
             AND projection.bound_count = session.expected_camera_count THEN 'COMPLETE'
        ELSE 'ASSEMBLING'
    END,
    quality_status = CASE
        WHEN coalesce(projection.has_reject, false) THEN 'REJECT'
        WHEN projection.bound_count <> session.expected_camera_count
             OR projection.received_count <> session.expected_camera_count
             OR coalesce(projection.has_pending, true) THEN 'PENDING'
        WHEN coalesce(projection.has_warning, false) THEN 'WARNING'
        ELSE 'PASS'
    END,
    updated_at = CURRENT_TIMESTAMP
FROM stream_projection projection
WHERE projection.capture_session_id = session.capture_session_id;
