-- Reconcile local schemas that ran the pre-commit draft of V35 before its
-- final projection rules were committed. Fresh schemas are already in the
-- desired state after V35, so this migration leaves them unchanged.
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
        session.expected_camera_count,
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
), desired_projection AS (
    SELECT
        capture_session_id,
        received_count,
        CASE
            WHEN coalesce(has_unavailable, false) THEN 'INCOMPLETE'
            WHEN received_count = expected_camera_count
                 AND bound_count = expected_camera_count THEN 'COMPLETE'
            ELSE 'ASSEMBLING'
        END AS assembly_status,
        CASE
            WHEN coalesce(has_reject, false) THEN 'REJECT'
            WHEN bound_count <> expected_camera_count
                 OR received_count <> expected_camera_count
                 OR coalesce(has_pending, true) THEN 'PENDING'
            WHEN coalesce(has_warning, false) THEN 'WARNING'
            ELSE 'PASS'
        END AS quality_status
    FROM stream_projection
)
UPDATE capture_session session
SET received_camera_count = projection.received_count,
    assembly_status = CASE
        WHEN session.assembly_status IN ('INCOMPLETE', 'CLOSED') THEN session.assembly_status
        ELSE projection.assembly_status
    END,
    quality_status = projection.quality_status,
    updated_at = CURRENT_TIMESTAMP
FROM desired_projection projection
WHERE projection.capture_session_id = session.capture_session_id
  AND (
      session.received_camera_count,
      session.assembly_status,
      session.quality_status
  ) IS DISTINCT FROM (
      projection.received_count,
      CASE
          WHEN session.assembly_status IN ('INCOMPLETE', 'CLOSED') THEN session.assembly_status
          ELSE projection.assembly_status
      END,
      projection.quality_status
  );
