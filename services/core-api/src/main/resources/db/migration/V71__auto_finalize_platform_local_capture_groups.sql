UPDATE capture_session session
SET receipt_state = 'COMPLETE',
    receipt_finalized_at = COALESCE(batch.completed_at, session.created_at),
    updated_at = GREATEST(session.updated_at, COALESCE(batch.completed_at, session.created_at))
FROM upload_batch batch
WHERE batch.batch_id = session.batch_id
  AND batch.project_id = session.project_id
  AND batch.status = 'COMPLETED'
  AND session.capture_group_workflow = TRUE
  AND session.metadata_schema_version = 'platform-local-upload-v1'
  AND session.expected_camera_count = 1
  AND session.receipt_state = 'RECEIVING'
  AND (
      SELECT COUNT(*)
      FROM camera_stream stream
      WHERE stream.capture_session_id = session.capture_session_id
  ) = 1;
