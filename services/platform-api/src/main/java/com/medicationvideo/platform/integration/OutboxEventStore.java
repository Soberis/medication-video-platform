package com.medicationvideo.platform.integration;

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
class OutboxEventStore {

	private final JdbcTemplate jdbcTemplate;

	OutboxEventStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Transactional
	List<ClaimedOutboxEvent> claim(int batchSize, Instant now, Duration claimDuration) {
		var rows = this.jdbcTemplate.query("""
				SELECT outbox_event_id, exchange_name, routing_key, payload_json::text
				FROM outbox_event
				WHERE published_at IS NULL
				  AND available_at <= ?
				  AND (claimed_until IS NULL OR claimed_until < ?)
				ORDER BY occurred_at, outbox_event_id
				LIMIT ?
				FOR UPDATE SKIP LOCKED
				""",
				this::map,
				Timestamp.from(now), Timestamp.from(now), batchSize);
		if (rows.isEmpty()) {
			return List.of();
		}

		return rows.stream().map(row -> {
			var claimToken = UUID.randomUUID();
			var updated = this.jdbcTemplate.update("""
					UPDATE outbox_event
					SET claim_token = ?, claimed_until = ?, publish_attempts = publish_attempts + 1
					WHERE outbox_event_id = ? AND published_at IS NULL
					""",
					claimToken,
					Timestamp.from(now.plus(claimDuration)),
					row.outboxEventId());
			if (updated != 1) {
				throw new IllegalStateException("The claimed Outbox row changed unexpectedly.");
			}
			return row.withClaimToken(claimToken);
		}).toList();
	}

	@Transactional
	void markPublished(UUID outboxEventId, UUID claimToken, Instant publishedAt) {
		var updated = this.jdbcTemplate.update("""
				UPDATE outbox_event
				SET published_at = ?, claim_token = NULL, claimed_until = NULL, last_error_code = NULL
				WHERE outbox_event_id = ? AND claim_token = ? AND published_at IS NULL
				""",
				Timestamp.from(publishedAt), outboxEventId, claimToken);
		if (updated != 1) {
			throw new IllegalStateException("The Outbox publish claim is no longer current.");
		}
	}

	@Transactional
	void release(UUID outboxEventId, UUID claimToken, Instant availableAt, String errorCode) {
		this.jdbcTemplate.update("""
				UPDATE outbox_event
				SET available_at = ?, claim_token = NULL, claimed_until = NULL, last_error_code = ?
				WHERE outbox_event_id = ? AND claim_token = ? AND published_at IS NULL
				""",
				Timestamp.from(availableAt), errorCode, outboxEventId, claimToken);
	}

	private ClaimedOutboxEvent map(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ClaimedOutboxEvent(
				resultSet.getObject("outbox_event_id", UUID.class),
				null,
				resultSet.getString("exchange_name"),
				resultSet.getString("routing_key"),
				resultSet.getString("payload_json"));
	}

	record ClaimedOutboxEvent(
			UUID outboxEventId,
			UUID claimToken,
			String exchangeName,
			String routingKey,
			String payloadJson) {

		ClaimedOutboxEvent withClaimToken(UUID token) {
			return new ClaimedOutboxEvent(
					this.outboxEventId, token, this.exchangeName, this.routingKey, this.payloadJson);
		}
	}
}
