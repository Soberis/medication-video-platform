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
	public void enqueue(MediaProbeJobEnvelope envelope, Instant availableAt) {
		if (availableAt.isBefore(envelope.requestedAt())) {
			throw new IllegalArgumentException("An Outbox event cannot be available before it was requested.");
		}
		try {
			this.jdbcTemplate.update("""
					INSERT INTO outbox_event (
					    outbox_event_id, aggregate_type, aggregate_id, event_type,
					    schema_version, exchange_name, routing_key, payload_json,
					    occurred_at, available_at)
					VALUES (?, 'MediaJob', ?, 'MediaProbeJobRequestedV1', ?, ?, ?, CAST(? AS JSONB), ?, ?)
					""", UUID.randomUUID(), envelope.jobId(), envelope.schemaVersion(), MediaMessagingTopology.EXCHANGE,
					MediaMessagingTopology.PROBE_ROUTING_KEY, this.objectMapper.writeValueAsString(envelope),
					Timestamp.from(envelope.requestedAt()), Timestamp.from(availableAt));
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("The media probe job envelope could not be serialized.", exception);
		}
	}

	@Override
	public boolean hasConfirmedPublication(UUID mediaJobId, UUID messageId) {
		return Boolean.TRUE.equals(this.jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM outbox_event
				    WHERE aggregate_type = 'MediaJob'
				      AND aggregate_id = ?
				      AND event_type = 'MediaProbeJobRequestedV1'
				      AND payload_json ->> 'message_id' = ?
				      AND published_at IS NOT NULL)
				""", Boolean.class, mediaJobId, messageId.toString()));
	}

}
