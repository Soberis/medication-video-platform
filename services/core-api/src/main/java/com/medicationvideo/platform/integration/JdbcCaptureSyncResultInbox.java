package com.medicationvideo.platform.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.medicationvideo.platform.assetcapture.CaptureSyncInboxReceipt;
import com.medicationvideo.platform.assetcapture.CaptureSyncResultInboxPort;

@Component
class JdbcCaptureSyncResultInbox implements CaptureSyncResultInboxPort {

	private final JdbcTemplate jdbcTemplate;

	JdbcCaptureSyncResultInbox(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<CaptureSyncInboxReceipt> find(UUID messageId) {
		return this.jdbcTemplate.query("""
				SELECT message_id, aggregate_id, attempt, result_fingerprint,
				       result_status, processed_at
				FROM inbox_message
				WHERE message_id = ?
				""", this::mapReceipt, messageId).stream().findFirst();
	}

	@Override
	public CaptureSyncInboxReceipt record(UUID messageId, UUID jobId, int attempt, String resultFingerprint,
			String resultStatus, java.time.Instant processedAt) {
		this.jdbcTemplate.update("""
				INSERT INTO inbox_message (
				    message_id, consumer_name, message_type, aggregate_id, attempt,
				    result_fingerprint, result_status, processed_at)
				VALUES (?, 'capture-sync-result-v1', 'CaptureSyncResultV1', ?, ?, ?, ?, ?)
				ON CONFLICT (message_id) DO NOTHING
				""", messageId, jobId, attempt, resultFingerprint, resultStatus, Timestamp.from(processedAt));
		return find(messageId)
			.orElseThrow(() -> new IllegalStateException("The capture sync result Inbox receipt was not persisted."));
	}

	private CaptureSyncInboxReceipt mapReceipt(ResultSet resultSet, int rowNumber) throws SQLException {
		return new CaptureSyncInboxReceipt(resultSet.getObject("message_id", UUID.class),
				resultSet.getObject("aggregate_id", UUID.class), resultSet.getInt("attempt"),
				resultSet.getString("result_fingerprint"), resultSet.getString("result_status"),
				resultSet.getObject("processed_at", OffsetDateTime.class).toInstant());
	}

}
