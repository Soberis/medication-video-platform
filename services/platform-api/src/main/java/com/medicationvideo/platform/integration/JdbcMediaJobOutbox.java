package com.medicationvideo.platform.integration;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.medicationvideo.platform.mediaprivacy.MediaJobDispatchPort;
import com.medicationvideo.platform.mediaprivacy.MediaProbeJobEnvelope;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcMediaJobOutbox implements MediaJobDispatchPort {

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	JdbcMediaJobOutbox(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public void enqueue(MediaProbeJobEnvelope envelope) {
		try {
			this.jdbcTemplate.update("""
					INSERT INTO outbox_event (
					    outbox_event_id, aggregate_type, aggregate_id, event_type,
					    schema_version, exchange_name, routing_key, payload_json,
					    occurred_at, available_at)
					VALUES (?, 'MediaJob', ?, 'MediaProbeJobRequestedV1', ?, ?, ?, CAST(? AS JSONB), ?, ?)
					""",
					UUID.randomUUID(),
					envelope.jobId(),
					envelope.schemaVersion(),
					MediaMessagingTopology.EXCHANGE,
					MediaMessagingTopology.PROBE_ROUTING_KEY,
					this.objectMapper.writeValueAsString(envelope),
					Timestamp.from(envelope.requestedAt()),
					Timestamp.from(envelope.requestedAt()));
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("The media probe job envelope could not be serialized.", exception);
		}
	}
}
