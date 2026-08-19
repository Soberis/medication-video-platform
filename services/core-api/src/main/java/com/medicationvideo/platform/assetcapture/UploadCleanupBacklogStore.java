package com.medicationvideo.platform.assetcapture;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class UploadCleanupBacklogStore {

	private final JdbcTemplate jdbcTemplate;

	UploadCleanupBacklogStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	UploadCleanupBacklogSnapshot snapshot(Instant observedAt) {
		return this.jdbcTemplate.queryForObject("""
				SELECT count(*) FILTER (WHERE status = 'PENDING') AS pending_tasks,
				       count(*) FILTER (WHERE status = 'RUNNING') AS running_tasks,
				       count(*) FILTER (WHERE status = 'FAILED') AS failed_tasks,
				       min(created_at) FILTER (WHERE status IN ('PENDING', 'RUNNING'))
				           AS oldest_actionable_created_at
				FROM upload_cleanup_task
				""", (resultSet, rowNumber) -> {
			var oldest = resultSet.getObject("oldest_actionable_created_at", OffsetDateTime.class);
			var ageSeconds = oldest == null ? 0
					: Math.max(0, Duration.between(oldest.toInstant(), observedAt).toSeconds());
			return new UploadCleanupBacklogSnapshot(resultSet.getLong("pending_tasks"),
					resultSet.getLong("running_tasks"), resultSet.getLong("failed_tasks"), ageSeconds);
		});
	}

}
