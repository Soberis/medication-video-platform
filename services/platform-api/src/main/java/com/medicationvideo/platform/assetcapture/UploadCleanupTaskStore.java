package com.medicationvideo.platform.assetcapture;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class UploadCleanupTaskStore {

	private final JdbcTemplate jdbcTemplate;

	UploadCleanupTaskStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	void schedule(UUID batchId, UUID uploadFileId, Instant now) {
		this.jdbcTemplate.update("""
				INSERT INTO upload_cleanup_task (
				    cleanup_task_id, batch_id, upload_file_id, status,
				    attempt_count, available_at, created_at)
				VALUES (?, ?, ?, 'PENDING', 0, ?, ?)
				ON CONFLICT (upload_file_id) DO NOTHING
				""",
				UUID.randomUUID(), batchId, uploadFileId, Timestamp.from(now), Timestamp.from(now));
	}

	void reactivate(UUID uploadFileId, Instant now) {
		var updated = this.jdbcTemplate.update("""
				UPDATE upload_cleanup_task
				SET status = 'PENDING', attempt_count = 0, available_at = ?,
				    claim_token = NULL, claimed_until = NULL,
				    last_error_code = NULL, completed_at = NULL
				WHERE upload_file_id = ?
				""",
				Timestamp.from(now), uploadFileId);
		if (updated != 1) {
			throw new IllegalStateException("The upload cleanup task does not exist.");
		}
	}

	@Transactional
	List<ClaimedCleanupTask> claim(int batchSize, Instant now, Duration claimDuration) {
		var rows = this.jdbcTemplate.query("""
				SELECT task.cleanup_task_id, task.attempt_count,
				       file.storage_bucket, file.object_key, file.storage_upload_id
				FROM upload_cleanup_task task
				JOIN upload_file file ON file.upload_file_id = task.upload_file_id
				WHERE task.status IN ('PENDING', 'RUNNING')
				  AND task.available_at <= ?
				  AND (task.claimed_until IS NULL OR task.claimed_until < ?)
				ORDER BY task.available_at, task.created_at, task.cleanup_task_id
				LIMIT ?
				FOR UPDATE OF task SKIP LOCKED
				""",
				this::map,
				Timestamp.from(now), Timestamp.from(now), batchSize);
		return rows.stream().map(row -> {
			var claimToken = UUID.randomUUID();
			var updated = this.jdbcTemplate.update("""
					UPDATE upload_cleanup_task
					SET status = 'RUNNING', claim_token = ?, claimed_until = ?,
					    attempt_count = attempt_count + 1
					WHERE cleanup_task_id = ?
					  AND status IN ('PENDING', 'RUNNING')
					""",
					claimToken,
					Timestamp.from(now.plus(claimDuration)),
					row.cleanupTaskId());
			if (updated != 1) {
				throw new IllegalStateException("The upload cleanup claim changed unexpectedly.");
			}
			return row.claimed(claimToken);
		}).toList();
	}

	@Transactional
	void markSucceeded(UUID cleanupTaskId, UUID claimToken, Instant completedAt) {
		var updated = this.jdbcTemplate.update("""
				UPDATE upload_cleanup_task
				SET status = 'SUCCEEDED', claim_token = NULL, claimed_until = NULL,
				    last_error_code = NULL, completed_at = ?
				WHERE cleanup_task_id = ? AND claim_token = ? AND status = 'RUNNING'
				""",
				Timestamp.from(completedAt), cleanupTaskId, claimToken);
		if (updated != 1) {
			throw new IllegalStateException("The upload cleanup claim is no longer current.");
		}
	}

	@Transactional
	void releaseOrFail(
			ClaimedCleanupTask task,
			Instant now,
			Instant availableAt,
			int maxAttempts,
			String errorCode) {
		var terminal = task.attemptCount() >= maxAttempts;
		var updated = this.jdbcTemplate.update("""
				UPDATE upload_cleanup_task
				SET status = ?, available_at = ?, claim_token = NULL, claimed_until = NULL,
				    last_error_code = ?, completed_at = ?
				WHERE cleanup_task_id = ? AND claim_token = ? AND status = 'RUNNING'
				""",
				terminal ? "FAILED" : "PENDING",
				Timestamp.from(availableAt),
				errorCode,
				terminal ? Timestamp.from(now) : null,
				task.cleanupTaskId(),
				task.claimToken());
		if (updated != 1) {
			throw new IllegalStateException("The upload cleanup failure claim is no longer current.");
		}
	}

	UploadCleanupSummary summarize(UUID batchId) {
		return this.jdbcTemplate.queryForObject("""
				SELECT count(*) AS total,
				       count(*) FILTER (WHERE status = 'RUNNING') AS running,
				       count(*) FILTER (WHERE status = 'SUCCEEDED') AS succeeded,
				       count(*) FILTER (WHERE status = 'FAILED') AS failed
				FROM upload_cleanup_task
				WHERE batch_id = ?
				""",
				(resultSet, rowNumber) -> UploadCleanupSummary.fromCounts(
						resultSet.getInt("total"),
						resultSet.getInt("running"),
						resultSet.getInt("succeeded"),
						resultSet.getInt("failed")),
				batchId);
	}

	private ClaimedCleanupTask map(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ClaimedCleanupTask(
				resultSet.getObject("cleanup_task_id", UUID.class),
				null,
				resultSet.getInt("attempt_count") + 1,
				new MultipartObjectStorage.CreatedUpload(
						resultSet.getString("storage_bucket"),
						resultSet.getString("object_key"),
						resultSet.getString("storage_upload_id")));
	}

	record ClaimedCleanupTask(
			UUID cleanupTaskId,
			UUID claimToken,
			int attemptCount,
			MultipartObjectStorage.CreatedUpload upload) {

		ClaimedCleanupTask claimed(UUID token) {
			return new ClaimedCleanupTask(this.cleanupTaskId, token, this.attemptCount, this.upload);
		}
	}
}
