package com.medicationvideo.platform.integration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.medicationvideo.platform.datasetexport.DatasetExportDispatchPort;
import com.medicationvideo.platform.datasetexport.DatasetExportJobEnvelope;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcDatasetExportOutbox implements DatasetExportDispatchPort {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	JdbcDatasetExportOutbox(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public void enqueue(DatasetExportJobEnvelope envelope, Instant availableAt) {
		if (availableAt.isBefore(envelope.requestedAt())) {
			throw new IllegalArgumentException(
					"An Outbox event cannot be available before it was requested.");
		}
		try {
			this.jdbcTemplate.update("""
					INSERT INTO outbox_event (
					    outbox_event_id, aggregate_type, aggregate_id, event_type,
					    schema_version, exchange_name, routing_key, payload_json,
					    occurred_at, available_at)
					VALUES (?, 'DatasetExport', ?, 'DatasetExportRequestedV1', ?, ?, ?,
					        CAST(? AS JSONB), ?, ?)
					""",
					UUID.randomUUID(),
					envelope.exportId(),
					envelope.schemaVersion(),
					DatasetExportMessagingTopology.EXCHANGE,
					DatasetExportMessagingTopology.ROUTING_KEY,
					this.objectMapper.writeValueAsString(envelope),
					Timestamp.from(envelope.requestedAt()),
					Timestamp.from(availableAt));
		}
		catch (JacksonException exception) {
			throw new IllegalStateException(
					"The dataset export job envelope could not be serialized.", exception);
		}
	}
}
