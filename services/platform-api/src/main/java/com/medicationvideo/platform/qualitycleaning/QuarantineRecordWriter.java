package com.medicationvideo.platform.qualitycleaning;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Atomic insert boundary for quarantine evidence. A uniqueness conflict is an expected
 * idempotency outcome and must not abort the surrounding PostgreSQL transaction.
 */
@Repository
class QuarantineRecordWriter {

	private final JdbcTemplate jdbcTemplate;

	QuarantineRecordWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	boolean insertIfAbsent(QuarantineRecord record) {
		return this.jdbcTemplate.update("""
				INSERT INTO quarantine_record (
				    quarantine_record_id,
				    project_id,
				    clip_asset_id,
				    input_digest,
				    reason_code,
				    source_type,
				    source_id,
				    source_version,
				    status,
				    activated_by,
				    activated_at,
				    row_version)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
				ON CONFLICT (project_id, source_type, source_id) DO NOTHING
				""",
				record.getQuarantineRecordId(),
				record.getProjectId(),
				record.getClipAssetId(),
				record.getInputDigest(),
				record.getReasonCode().name(),
				record.getSourceType().name(),
				record.getSourceId(),
				record.getSourceVersion(),
				record.getStatus().name(),
				record.getActivatedBy(),
				Timestamp.from(record.getActivatedAt())) == 1;
	}
}
