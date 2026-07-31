package com.medicationvideo.platform.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.medicationvideo.platform.datasetexport.DatasetExportInboxReceipt;
import com.medicationvideo.platform.datasetexport.DatasetExportResultInboxPort;

@Component
class JdbcDatasetExportResultInbox implements DatasetExportResultInboxPort {

	private final JdbcTemplate jdbcTemplate;

	JdbcDatasetExportResultInbox(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<DatasetExportInboxReceipt> find(UUID messageId) {
		return this.jdbcTemplate.query("""
				SELECT message_id, aggregate_id, attempt, result_fingerprint,
				       result_status, processed_at
				FROM inbox_message
				WHERE message_id = ? AND consumer_name = 'dataset-export-result-v1'
				""", this::map, messageId).stream().findFirst();
	}

	@Override
	public DatasetExportInboxReceipt record(UUID messageId, UUID exportId, int attempt, String resultFingerprint,
			String resultStatus, java.time.Instant processedAt) {
		this.jdbcTemplate.update("""
				INSERT INTO inbox_message (
				    message_id, consumer_name, message_type, aggregate_id, attempt,
				    result_fingerprint, result_status, processed_at)
				VALUES (?, 'dataset-export-result-v1', 'DatasetExportResultV1', ?, ?, ?, ?, ?)
				ON CONFLICT (message_id) DO NOTHING
				""", messageId, exportId, attempt, resultFingerprint, resultStatus, Timestamp.from(processedAt));
		return find(messageId)
			.orElseThrow(() -> new IllegalStateException("The dataset export result Inbox receipt was not persisted."));
	}

	private DatasetExportInboxReceipt map(ResultSet resultSet, int rowNumber) throws SQLException {
		return new DatasetExportInboxReceipt(resultSet.getObject("message_id", UUID.class),
				resultSet.getObject("aggregate_id", UUID.class), resultSet.getInt("attempt"),
				resultSet.getString("result_fingerprint"), resultSet.getString("result_status"),
				resultSet.getObject("processed_at", OffsetDateTime.class).toInstant());
	}

}
