package com.medicationvideo.platform.assetcapture;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * PostgreSQL-safe insert for the natural-key idempotency boundary. A duplicate is
 * deliberately represented as zero affected rows instead of a failed transaction, so the
 * caller can resolve the existing job in the same request.
 */
@Repository
class CaptureSyncJobWriter {

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	CaptureSyncJobWriter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	boolean insertIfAbsent(CaptureSyncJob job) {
		try {
			return this.jdbcTemplate.update("""
					INSERT INTO capture_sync_job (
					    capture_sync_job_id, project_id, capture_session_id, camera_set_snapshot_id,
					    expected_camera_count, input_manifest_sha256, input_manifest,
					    sync_profile_key, sync_profile_version, sync_profile_sha256,
					    algorithm_key, algorithm_version, status, idempotency_key,
					    request_fingerprint, requested_by, created_at, updated_at)
					VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
					ON CONFLICT DO NOTHING
					""", job.getCaptureSyncJobId(), job.getProjectId(), job.getCaptureSessionId(),
					job.getCameraSetSnapshotId(), job.getExpectedCameraCount(), job.getInputManifestSha256(),
					this.objectMapper.writeValueAsString(job.getInputManifest()), job.getSyncProfileKey(),
					job.getSyncProfileVersion(), job.getSyncProfileSha256(), job.getAlgorithmKey(),
					job.getAlgorithmVersion(), job.getStatus().name(), job.getIdempotencyKey(),
					job.getRequestFingerprint(), job.getRequestedBy(), Timestamp.from(job.getCreatedAt()),
					Timestamp.from(job.getUpdatedAt())) == 1;
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("The frozen synchronization input manifest could not be serialized.",
					exception);
		}
	}

}
