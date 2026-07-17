package com.medicationvideo.platform.iamproject;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class DataProjectWriter {

	private final JdbcTemplate jdbcTemplate;

	DataProjectWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	boolean insertIfAbsent(DataProject project) {
		return this.jdbcTemplate.update("""
				INSERT INTO project (
				    project_id, display_name, status, created_by, idempotency_key,
				    request_fingerprint, created_at, row_version)
				VALUES (?, ?, ?, ?, ?, ?, ?, 0)
				ON CONFLICT (created_by, idempotency_key) DO NOTHING
				""",
				project.getProjectId(),
				project.getDisplayName(),
				project.getStatus().name(),
				project.getCreatedBy(),
				project.getIdempotencyKey(),
				project.getRequestFingerprint(),
				Timestamp.from(project.getCreatedAt())) == 1;
	}
}
