package com.medicationvideo.platform.integration;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.medicationvideo.platform.aicandidate.AiHandPoseJobEnvelope;
import com.medicationvideo.platform.aicandidate.AiJobDispatchPort;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Component
class JdbcAiJobOutbox implements AiJobDispatchPort {

	private final JdbcTemplate jdbcTemplate;

	private final ObjectMapper objectMapper;

	JdbcAiJobOutbox(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public void enqueue(AiHandPoseJobEnvelope envelope, Instant availableAt) {
		try {
			this.jdbcTemplate.update("""
					INSERT INTO outbox_event (
					    outbox_event_id, aggregate_type, aggregate_id, event_type,
					    schema_version, exchange_name, routing_key, payload_json,
					    occurred_at, available_at)
					VALUES (?, 'AiJob', ?, 'AiHandPoseJobRequestedV1', ?, ?, ?, CAST(? AS JSONB), ?, ?)
					""", UUID.randomUUID(), envelope.jobId(), envelope.schemaVersion(), AiMessagingTopology.EXCHANGE,
					AiMessagingTopology.HAND_POSE_ROUTING_KEY, this.objectMapper.writeValueAsString(envelope),
					Timestamp.from(envelope.requestedAt()), Timestamp.from(availableAt));
		}
		catch (JacksonException exception) {
			throw new IllegalStateException("The AI hand pose job envelope could not be serialized.", exception);
		}
	}

}
