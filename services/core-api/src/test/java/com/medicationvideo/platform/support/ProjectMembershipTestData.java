package com.medicationvideo.platform.support;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

public final class ProjectMembershipTestData {

	public static final String ADMIN_ACTOR = "local-synthetic-admin";

	public static final String MEMBER_ACTOR = "local-synthetic-uploader";

	private ProjectMembershipTestData() {
	}

	public static void ensureProject(JdbcTemplate jdbcTemplate, UUID projectId) {
		var now = Timestamp.from(Instant.parse("2026-01-01T00:00:00Z"));
		jdbcTemplate.update("""
				INSERT INTO project (
				    project_id, display_name, status, created_by, idempotency_key,
				    request_fingerprint, created_at, row_version)
				VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
				ON CONFLICT (project_id) DO UPDATE SET status = 'ACTIVE'
				""", projectId, "Synthetic contract project " + projectId, ADMIN_ACTOR, "test-project-" + projectId,
				"0".repeat(64), now);
		ensureMembership(jdbcTemplate, projectId, ADMIN_ACTOR, "ADMIN", now);
		ensureMembership(jdbcTemplate, projectId, MEMBER_ACTOR, "MEMBER", now);
	}

	private static void ensureMembership(JdbcTemplate jdbcTemplate, UUID projectId, String actorId, String role,
			Timestamp now) {
		jdbcTemplate.update("""
				INSERT INTO membership (
				    membership_id, project_id, actor_id, role, status,
				    created_by, created_at, updated_by, updated_at, row_version)
				VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, 0)
				ON CONFLICT (project_id, actor_id) DO UPDATE SET
				    role = EXCLUDED.role,
				    status = 'ACTIVE',
				    updated_by = EXCLUDED.updated_by,
				    updated_at = EXCLUDED.updated_at,
				    removed_by = NULL,
				    removed_at = NULL
				""", UUID.randomUUID(), projectId, actorId, role, ADMIN_ACTOR, now, ADMIN_ACTOR, now);
	}

}
