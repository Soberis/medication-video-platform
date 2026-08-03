package com.medicationvideo.platform.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(OutboxEventStore.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
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

	@Test
	void reclaimsUnmarkedEventAfterLeaseExpiryAndRedactsAfterFinalMark() {
		var now = Instant.parse("2026-01-01T00:00:00Z");
		var outboxId = UUID.randomUUID();
		var envelopeMessageId = UUID.randomUUID();
		var payload = mediaProbePayload(envelopeMessageId);
		this.jdbcTemplate.update("""
				INSERT INTO outbox_event (
				    outbox_event_id, aggregate_type, aggregate_id, event_type, schema_version,
				    exchange_name, routing_key, payload_json, occurred_at, available_at)
				VALUES (?, 'MediaJob', ?, 'MediaProbeJobRequestedV1', 'media-probe-job.v1',
				        'platform.media.v1', 'media.probe.requested.v1', CAST(? AS JSONB), ?, ?)
				""", outboxId, UUID.randomUUID(), payload, Timestamp.from(now), Timestamp.from(now));

		var firstClaim = this.store.claim(1, now.plusSeconds(1), Duration.ofSeconds(30)).getFirst();
		assertTrue(this.store.claim(1, now.plusSeconds(2), Duration.ofSeconds(30)).isEmpty());

		var secondClaim = this.store.claim(1, now.plusSeconds(32), Duration.ofSeconds(30)).getFirst();
		assertEquals(outboxId, secondClaim.outboxEventId());
		assertNotEquals(firstClaim.claimToken(), secondClaim.claimToken());
		assertEquals(firstClaim.payloadJson(), secondClaim.payloadJson());

		this.store.markPublished(outboxId, secondClaim.claimToken(), now.plusSeconds(33));

		assertEquals(1, this.jdbcTemplate.queryForObject("""
				SELECT count(*) FROM outbox_event
				WHERE outbox_event_id = ?
				  AND published_at IS NOT NULL
				  AND claim_token IS NULL
				  AND claimed_until IS NULL
				  AND publish_attempts = 2
				  AND NOT jsonb_exists(payload_json, 'lease_token')
				  AND payload_redacted_at IS NOT NULL
				""", Integer.class, outboxId));
	}

	private static String mediaProbePayload(UUID envelopeMessageId) {
		return """
				{"schema_version":"media-probe-job.v1","message_id":"%s","job_id":"00000000-0000-0000-0000-000000000001","job_type":"MEDIA_PROBE","attempt":1,"lease_token":"synthetic-lease-token-012345678901234567890123","project_id":"00000000-0000-0000-0000-000000000002","clip_asset_id":"00000000-0000-0000-0000-000000000003","input_sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","input_size_bytes":1024,"content_type":"video/mp4","probe_profile_key":"ffprobe-default","recipe_digest":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","requested_at":"2026-01-01T00:00:00Z"}
				"""
			.formatted(envelopeMessageId);
	}

}
