package com.medicationvideo.platform.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/** Accepted synthetic probe facts for annotation and export contract tests. */
public final class VerifiedMediaTestData {

	private VerifiedMediaTestData() {
	}

	public static void insert(JdbcTemplate jdbc, UUID projectId, UUID clipAssetId, String sha256,
			long durationMicroseconds) {
		var mediaJobId = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO media_job (
				    media_job_id, project_id, clip_asset_id, job_type, input_digest,
				    input_size_bytes, content_type, status, idempotency_key,
				    request_fingerprint, created_at, updated_at, version,
				    probe_profile_key, recipe_digest)
				VALUES (?, ?, ?, 'MEDIA_PROBE', ?, 1234, 'video/mp4', 'SUCCEEDED', ?, ?, ?, ?, 0,
				        'media-probe-baseline-v1',
				        '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765')
				""", mediaJobId, projectId, clipAssetId, sha256, "cleaning-media-job-" + mediaJobId, sha256,
				Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")),
				Timestamp.from(Instant.parse("2026-08-01T00:00:00Z")));
		jdbc.update("""
				INSERT INTO media_report (
				    media_report_id, media_job_id, project_id, clip_asset_id,
				    report_schema_version, input_digest, recipe_digest, software_digest,
				    container_format, duration_microseconds, video_stream_count,
				    primary_video_stream_index, video_codec, width, height,
				    time_base_numerator, time_base_denominator, variable_frame_rate,
				    audio_stream_count, decode_verified, input_digest_verified,
				    warning_codes, created_at)
				VALUES (?, ?, ?, ?, 'media-report.v1', ?,
				        '8900937958d0fb81a9c9bf2d9e81ac3e57f35d838d5a4da52b58ccf0f5cd5765',
				        'sha256:207391c770bee4a8dfe3530ef5f31af07904950f9bc2dcb822fedb086222164a',
				        'mov,mp4,m4a,3gp,3g2,mj2', ?, 1, 0, 'h264', 640, 360,
				        1, 90000, false, 0, true, true, '[]'::jsonb, ?)
				""", UUID.randomUUID(), mediaJobId, projectId, clipAssetId, sha256, durationMicroseconds,
				Timestamp.from(Instant.parse("2026-08-01T00:00:00Z").plusSeconds(5)));
	}

}
