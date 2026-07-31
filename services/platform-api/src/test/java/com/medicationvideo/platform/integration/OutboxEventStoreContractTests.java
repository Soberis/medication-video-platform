package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class OutboxEventStoreContractTests {

	@Autowired
	private OutboxEventStore store;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void clearOutbox() {
		this.jdbcTemplate.update("DELETE FROM outbox_event");
	}

	@Test
	void scrubsMediaLeaseCapabilityOnlyAfterPublisherConfirmation() {
		var now = Instant.now();
		var outboxId = UUID.randomUUID();
		this.jdbcTemplate.update("""
				INSERT INTO outbox_event (
				    outbox_event_id, aggregate_type, aggregate_id, event_type, schema_version,
				    exchange_name, routing_key, payload_json, occurred_at, available_at)
				VALUES (?, 'MediaJob', ?, 'MediaProbeJobRequestedV1', 'media-probe-job.v1',
				        'platform.media.v1', 'media.probe.requested.v1',
				        CAST(? AS JSONB), ?, ?)
				""", outboxId, UUID.randomUUID(),
				"{\"schema_version\":\"media-probe-job.v1\",\"lease_token\":\"secret\"}", Timestamp.from(now),
				Timestamp.from(now));

		var claimed = this.store.claim(1, now.plusSeconds(1), Duration.ofSeconds(30)).getFirst();
		assertEquals(1, this.jdbcTemplate.queryForObject("""
				SELECT count(*) FROM outbox_event
				WHERE outbox_event_id = ? AND jsonb_exists(payload_json, 'lease_token')
				  AND published_at IS NULL
				""", Integer.class, outboxId));

		this.store.markPublished(outboxId, claimed.claimToken(), now.plusSeconds(2));

		assertEquals(1, this.jdbcTemplate.queryForObject("""
				SELECT count(*) FROM outbox_event
				WHERE outbox_event_id = ?
				  AND NOT jsonb_exists(payload_json, 'lease_token')
				  AND published_at IS NOT NULL
				  AND payload_redacted_at IS NOT NULL
				""", Integer.class, outboxId));
	}

	@Test
	void doesNotClaimAnEventBeforeItsAvailableAtDeadline() {
		var now = Instant.now();
		var availableAt = now.plusSeconds(5);
		this.jdbcTemplate.update("""
				INSERT INTO outbox_event (
				    outbox_event_id, aggregate_type, aggregate_id, event_type, schema_version,
				    exchange_name, routing_key, payload_json, occurred_at, available_at)
				VALUES (?, 'MediaJob', ?, 'MediaProbeJobRequestedV1', 'media-probe-job.v1',
				        'platform.media.v1', 'media.probe.requested.v1',
				        CAST(? AS JSONB), ?, ?)
				""", UUID.randomUUID(), UUID.randomUUID(),
				"{\"schema_version\":\"media-probe-job.v1\",\"lease_token\":\"secret\"}", Timestamp.from(now),
				Timestamp.from(availableAt));

		assertTrue(this.store.claim(1, availableAt.minusMillis(1), Duration.ofSeconds(30)).isEmpty());
		assertEquals(1, this.store.claim(1, availableAt, Duration.ofSeconds(30)).size());
	}

}
