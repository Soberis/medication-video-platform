package com.medicationvideo.platform.mediaprivacy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;
import com.medicationvideo.platform.support.CaptureMediaProjectionTestData;
import com.medicationvideo.platform.support.ProjectMembershipTestData;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MediaJobExecutionContractTests {

	private static final String USER_TOKEN = "synthetic-test-bearer-token-do-not-reuse";

	private static final String ADMIN_TOKEN = "synthetic-test-admin-bearer-token-do-not-reuse";

	private static final String WORKER_TOKEN = "synthetic-media-worker-bearer-token-do-not-reuse";

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final String SHA256 = "a".repeat(64);

	private static final String SOFTWARE_DIGEST = "sha256:207391c770bee4a8dfe3530ef5f31af07904950f9bc2dcb822fedb086222164a";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MediaJobRecoveryService recoveryService;

	@BeforeEach
	void clearMediaState() {
		ProjectMembershipTestData.ensureProject(this.jdbcTemplate, PROJECT_ID);
		this.jdbcTemplate.update("DELETE FROM media_job_delivery_recovery");
		this.jdbcTemplate.update("DELETE FROM media_job_manual_replay");
		this.jdbcTemplate.update("DELETE FROM inbox_message");
		this.jdbcTemplate.update("DELETE FROM outbox_event");
		this.jdbcTemplate.update("DELETE FROM media_job_attempt");
		this.jdbcTemplate.update("DELETE FROM media_report");
		this.jdbcTemplate.update("DELETE FROM media_job");
	}

	@AfterEach
	void clearReplayState() {
		this.jdbcTemplate.update("DELETE FROM media_job_delivery_recovery");
		this.jdbcTemplate.update("DELETE FROM media_job_manual_replay");
	}

	@Test
	void administratorReplaysRetainedDiagnosticWithNewAttemptAndAppendOnlyAudit() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());
		var bodySha256 = "d".repeat(64);
		var deadLetterId = UUID.fromString("b682e7d9-a8ab-5ca1-80c6-7308a617be4e");
		assertEquals(deadLetterId,
				MediaDeadLetterIdentity.from(MediaDeadLetterReason.MEDIA_JOB_ACQUIRE_REJECTED, bodySha256));
		var request = replayRequest(created, deadLetterId, bodySha256);
		var idempotencyKey = "media-replay-" + UUID.randomUUID();

		this.mockMvc
			.perform(post("/api/v1/media-jobs/{jobId}/replays", created.jobId())
				.header("Authorization", "Bearer " + USER_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("PROJECT_ACCESS_DENIED"));

		var response = this.mockMvc
			.perform(post("/api/v1/media-jobs/{jobId}/replays", created.jobId())
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "replay-contract-request-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.media_job_id").value(created.jobId().toString()))
			.andExpect(jsonPath("$.source_attempt").value(1))
			.andExpect(jsonPath("$.new_attempt").value(2))
			.andExpect(jsonPath("$.resulting_job_status").value("QUEUED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		var replayId = JsonPath.<String>read(response, "$.replay_id");

		this.mockMvc
			.perform(post("/api/v1/media-jobs/{jobId}/replays", created.jobId())
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", idempotencyKey)
				.header("X-Request-Id", "replay-contract-request-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.replay_id").value(replayId));

		assertEquals("FAILED", this.jdbcTemplate.queryForObject("""
				SELECT status FROM media_job_attempt
				WHERE media_job_id = ? AND attempt = 1
				""", String.class, created.jobId()));
		assertEquals("MEDIA_JOB_ACQUIRE_REJECTED", this.jdbcTemplate.queryForObject("""
				SELECT error_code FROM media_job_attempt
				WHERE media_job_id = ? AND attempt = 1
				""", String.class, created.jobId()));
		assertEquals("QUEUED", this.jdbcTemplate.queryForObject("""
				SELECT status FROM media_job_attempt
				WHERE media_job_id = ? AND attempt = 2
				""", String.class, created.jobId()));
		assertEquals("QUEUED", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("INGESTING",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));
		assertEquals(1, count("SELECT count(*) FROM media_job_manual_replay WHERE media_job_id = ?", created.jobId()));
		assertEquals(2, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", created.jobId()));
		var replayedAttempt = loadAttempt(created, 2);
		acquire(replayedAttempt).andExpect(status().isOk());
		assertEquals("RUNNING", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));

		var auditEventId = this.jdbcTemplate.queryForObject("""
				SELECT event_id FROM audit_event
				WHERE action = 'MEDIA_JOB_MANUAL_REPLAY' AND object_id = ?
				ORDER BY occurred_at_utc DESC LIMIT 1
				""", UUID.class, created.jobId().toString());
		assertEquals("local-synthetic-admin", this.jdbcTemplate.queryForObject("""
				SELECT actor_id FROM audit_event WHERE event_id = ?
				""", String.class, auditEventId));
		assertEquals("replay-contract-request-1", this.jdbcTemplate.queryForObject("""
				SELECT request_id FROM audit_event WHERE event_id = ?
				""", String.class, auditEventId));
		assertThrows(DataAccessException.class, () -> this.jdbcTemplate
			.update("UPDATE audit_event SET action = 'TAMPERED' WHERE event_id = ?", auditEventId));
		assertThrows(DataAccessException.class,
				() -> this.jdbcTemplate.update("DELETE FROM audit_event WHERE event_id = ?", auditEventId));
	}

	@Test
	void replayRequiresProjectAdministratorMembership() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());
		var bodySha256 = "d".repeat(64);
		var deadLetterId = MediaDeadLetterIdentity.from(MediaDeadLetterReason.MEDIA_JOB_ACQUIRE_REJECTED, bodySha256);
		var request = replayRequest(created, deadLetterId, bodySha256);
		this.jdbcTemplate.update("""
				UPDATE membership
				SET status = 'REMOVED', removed_by = ?, removed_at = ?
				WHERE project_id = ? AND actor_id = ?
				""", ProjectMembershipTestData.ADMIN_ACTOR, Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")),
				PROJECT_ID, ProjectMembershipTestData.ADMIN_ACTOR);

		this.mockMvc
			.perform(post("/api/v1/media-jobs/{jobId}/replays", created.jobId())
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", "media-replay-membership-001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("PROJECT_NOT_FOUND"));

		assertEquals(0, count("SELECT count(*) FROM media_job_manual_replay WHERE media_job_id = ?", created.jobId()));
	}

	@Test
	void rejectsForgedDeadLetterIdentityWithoutChangingTheCurrentAttempt() throws Exception {
		var created = createQueuedJob();
		var request = replayRequest(created, UUID.fromString("99999999-9999-5999-8999-999999999999"), "d".repeat(64));

		this.mockMvc
			.perform(post("/api/v1/media-jobs/{jobId}/replays", created.jobId())
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", "media-replay-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content(request))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("MEDIA_JOB_REPLAY_DIAGNOSTIC_MISMATCH"));

		assertEquals("QUEUED", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals(1, count("SELECT count(*) FROM media_job_attempt WHERE media_job_id = ?", created.jobId()));
		assertEquals(0, count("SELECT count(*) FROM media_job_manual_replay WHERE media_job_id = ?", created.jobId()));
	}

	@Test
	void acquiresInputAcceptsSuccessIdempotentlyAndExposesImmutableReport() throws Exception {
		var created = createQueuedJob();
		var projection = CaptureMediaProjectionTestData.bindPending(this.jdbcTemplate, PROJECT_ID,
				created.clipAssetId(), Instant.now().minusSeconds(30));
		acquire(created).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.job_id").value(created.jobId().toString()))
			.andExpect(jsonPath("$.attempt").value(1))
			.andExpect(jsonPath("$.input_url").isString())
			.andExpect(jsonPath("$.lease_until").isString())
			.andExpect(jsonPath("$.heartbeat_interval_seconds").value(300))
			.andExpect(jsonPath("$.input_sha256").value(SHA256));

		assertEquals("RUNNING", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("RUNNING", scalar("SELECT status FROM media_job_attempt WHERE media_job_id = ?", created.jobId()));
		assertEquals("INGESTING",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));

		var result = successResult(created, SOFTWARE_DIGEST);
		var firstAcceptedAt = JsonPath.<String>read(submitResult(created, result).andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.accepted_at");

		var replayAcceptedAt = JsonPath.<String>read(submitResult(created, result).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("SUCCEEDED"))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.accepted_at");
		assertEquals(firstAcceptedAt, replayAcceptedAt);
		assertEquals(1, count("SELECT count(*) FROM inbox_message"));
		assertEquals(1, count("SELECT count(*) FROM media_report"));
		assertEquals("SUCCEEDED", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("INGESTED",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));
		assertEquals("INGESTED", scalar("SELECT stream_status FROM camera_stream WHERE camera_stream_id = ?",
				projection.cameraStreamId()));
		assertEquals("PASS", scalar("SELECT quality_status FROM camera_stream WHERE camera_stream_id = ?",
				projection.cameraStreamId()));
		assertEquals(1, count("SELECT received_camera_count FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId()));
		assertEquals("COMPLETE", scalar("SELECT assembly_status FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId()));
		assertEquals("PASS", scalar("SELECT quality_status FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId()));

		this.mockMvc
			.perform(get("/api/v1/media-jobs/{jobId}/report", created.jobId()).header("Authorization",
					"Bearer " + USER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.media_job_id").value(created.jobId().toString()))
			.andExpect(jsonPath("$.clip_asset_id").value(created.clipAssetId().toString()))
			.andExpect(jsonPath("$.software_digest").value(SOFTWARE_DIGEST))
			.andExpect(jsonPath("$.duration_microseconds").value(2_000_000))
			.andExpect(jsonPath("$.time_base_numerator").value(1))
			.andExpect(jsonPath("$.time_base_denominator").value(15_360))
			.andExpect(jsonPath("$.decode_verified").value(true))
			.andExpect(jsonPath("$.input_digest_verified").value(true))
			.andExpect(jsonPath("$.has_b_frames").value(true))
			.andExpect(jsonPath("$.has_bframes").doesNotExist())
			.andExpect(jsonPath("$.warning_codes[0]").value("AUDIO_PRESENT"))
			.andExpect(jsonPath("$.input_url").doesNotExist())
			.andExpect(jsonPath("$.object_key").doesNotExist());

		this.mockMvc
			.perform(get("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}/media-report", PROJECT_ID,
					created.clipAssetId())
				.header("Authorization", "Bearer " + USER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.media_job_id").value(created.jobId().toString()))
			.andExpect(jsonPath("$.clip_asset_id").value(created.clipAssetId().toString()))
			.andExpect(jsonPath("$.duration_microseconds").value(2_000_000))
			.andExpect(jsonPath("$.decode_verified").value(true))
			.andExpect(jsonPath("$.input_url").doesNotExist())
			.andExpect(jsonPath("$.object_key").doesNotExist());

		this.mockMvc
			.perform(delete("/api/v1/projects/{projectId}/clip-assets/{clipAssetId}", PROJECT_ID, created.clipAssetId())
				.header("Authorization", "Bearer " + ADMIN_TOKEN)
				.header("Idempotency-Key", "media-delete-projection-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"reason":"Synthetic media projection verification"}
						"""))
			.andExpect(status().isNoContent());
		assertEquals("UNAVAILABLE", scalar("SELECT stream_status FROM camera_stream WHERE camera_stream_id = ?",
				projection.cameraStreamId()));
		assertEquals(0, count("SELECT received_camera_count FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId()));
		assertEquals("INCOMPLETE", scalar("SELECT assembly_status FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId()));
	}

	@Test
	void renewsOnlyTheCurrentWorkersActiveLease() throws Exception {
		var created = createQueuedJob();
		var initialLeaseUntil = JsonPath.<String>read(
				acquire(created).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
				"$.lease_until");

		var renewedLeaseUntil = JsonPath.<String>read(heartbeat(created, "media-worker-test").andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.job_id").value(created.jobId().toString()))
			.andExpect(jsonPath("$.attempt").value(1))
			.andExpect(jsonPath("$.heartbeat_interval_seconds").value(300))
			.andReturn()
			.getResponse()
			.getContentAsString(), "$.lease_until");
		assertTrue(Instant.parse(renewedLeaseUntil).isAfter(Instant.parse(initialLeaseUntil)));

		heartbeat(created, "different-worker").andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("MEDIA_JOB_LEASE_CONFLICT"));

		expireAttempt(created, Instant.now());
		heartbeat(created, "media-worker-test").andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("MEDIA_JOB_LEASE_EXPIRED"));
	}

	@Test
	void recoversExpiredLeaseWithNewAttemptCapabilityAndOutboxMessage() throws Exception {
		var original = createQueuedJob();
		acquire(original).andExpect(status().isOk());
		var now = Instant.now();
		expireAttempt(original, now);

		assertEquals(1, this.recoveryService.recoverExpiredAttempts(now));
		var retried = loadAttempt(original, 2);
		assertNotEquals(original.messageId(), retried.messageId());
		assertNotEquals(original.leaseToken(), retried.leaseToken());
		assertEquals("LOST", scalar("SELECT status FROM media_job_attempt WHERE media_job_id = ? AND attempt = 1",
				original.jobId()));
		assertEquals("QUEUED", scalar("SELECT status FROM media_job WHERE media_job_id = ?", original.jobId()));
		assertEquals("INGESTING",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", original.clipAssetId()));
		assertEquals(2, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", original.jobId()));

		acquire(retried).andExpect(status().isOk());
		submitResult(original, successResult(original, SOFTWARE_DIGEST)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("MEDIA_JOB_LEASE_EXPIRED"));
		this.mockMvc
			.perform(
					get("/api/v1/media-jobs/{jobId}", original.jobId()).header("Authorization", "Bearer " + USER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RUNNING"))
			.andExpect(jsonPath("$.current_attempt.attempt").value(2))
			.andExpect(jsonPath("$.current_attempt.status").value("RUNNING"));
	}

	@Test
	void failsJobAndAssetAfterLeaseRecoveryAttemptsAreExhausted() throws Exception {
		var attempt = createQueuedJob();
		for (var attemptNumber = 1; attemptNumber <= 3; attemptNumber++) {
			acquire(attempt).andExpect(status().isOk());
			var now = Instant.now();
			expireAttempt(attempt, now);
			assertEquals(1, this.recoveryService.recoverExpiredAttempts(now));
			if (attemptNumber < 3) {
				attempt = loadAttempt(attempt, attemptNumber + 1);
			}
		}

		assertEquals("FAILED", scalar("SELECT status FROM media_job WHERE media_job_id = ?", attempt.jobId()));
		assertEquals("LOST",
				scalar("SELECT status FROM media_job_attempt WHERE media_job_id = ? AND attempt = 3", attempt.jobId()));
		assertEquals("INGEST_FAILED",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", attempt.clipAssetId()));
		assertEquals(3, count("SELECT count(*) FROM media_job_attempt WHERE media_job_id = ?", attempt.jobId()));
	}

	@Test
	void persistsStableFailureWithoutCreatingMediaReport() throws Exception {
		var created = createQueuedJob();
		var projection = CaptureMediaProjectionTestData.bindPending(this.jdbcTemplate, PROJECT_ID,
				created.clipAssetId(), Instant.now().minusSeconds(30));
		acquire(created).andExpect(status().isOk());

		submitResult(created, failedResult(created)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"));

		assertEquals(0, count("SELECT count(*) FROM media_report"));
		assertEquals("FAILED", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("MEDIA_DECODE_FAILED",
				scalar("SELECT error_code FROM media_job_attempt WHERE media_job_id = ?", created.jobId()));
		assertEquals("INGEST_FAILED",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));
		assertEquals("INGEST_FAILED", scalar("SELECT stream_status FROM camera_stream WHERE camera_stream_id = ?",
				projection.cameraStreamId()));
		assertEquals("REJECT", scalar("SELECT quality_status FROM camera_stream WHERE camera_stream_id = ?",
				projection.cameraStreamId()));
		assertEquals(0, count("SELECT received_camera_count FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId()));
		assertEquals("ASSEMBLING", scalar("SELECT assembly_status FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId()));
		assertEquals("REJECT", scalar("SELECT quality_status FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId()));

		this.mockMvc
			.perform(get("/api/v1/media-jobs/{jobId}", created.jobId()).header("Authorization", "Bearer " + USER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"))
			.andExpect(jsonPath("$.current_attempt.status").value("FAILED"))
			.andExpect(jsonPath("$.current_attempt.error.error_class").value("NON_RETRYABLE_INPUT"))
			.andExpect(jsonPath("$.current_attempt.error.error_code").value("MEDIA_DECODE_FAILED"))
			.andExpect(jsonPath("$.current_attempt.error.retryable").value(false));

		this.mockMvc
			.perform(get("/api/v1/media-jobs/{jobId}/report", created.jobId()).header("Authorization",
					"Bearer " + USER_TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error_code").value("MEDIA_REPORT_NOT_FOUND"));
	}

	@Test
	void retriesRetryableWorkerFailureWithDelayedNewAttempt() throws Exception {
		var original = createQueuedJob();
		acquire(original).andExpect(status().isOk());

		submitResult(original, retryableFailedResult(original)).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("FAILED"));

		var retried = loadAttempt(original, 2);
		assertNotEquals(original.messageId(), retried.messageId());
		assertNotEquals(original.leaseToken(), retried.leaseToken());
		assertEquals("FAILED", this.jdbcTemplate.queryForObject("""
				SELECT status FROM media_job_attempt
				WHERE media_job_id = ? AND attempt = 1
				""", String.class, original.jobId()));
		assertEquals("OBJECT_STORAGE_UNAVAILABLE", this.jdbcTemplate.queryForObject("""
				SELECT error_code FROM media_job_attempt
				WHERE media_job_id = ? AND attempt = 1
				""", String.class, original.jobId()));
		assertEquals("RETRY_WAIT", scalar("SELECT status FROM media_job WHERE media_job_id = ?", original.jobId()));
		assertEquals("INGESTING",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", original.clipAssetId()));
		assertEquals(2, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", original.jobId()));
		var retryDelaySeconds = this.jdbcTemplate.queryForObject("""
				SELECT EXTRACT(EPOCH FROM (available_at - occurred_at))
				FROM outbox_event
				WHERE aggregate_id = ? AND (payload_json ->> 'attempt')::integer = 2
				""", Double.class, original.jobId());
		assertTrue(retryDelaySeconds >= 1.0 && retryDelaySeconds <= 1.25);

		this.mockMvc
			.perform(
					get("/api/v1/media-jobs/{jobId}", original.jobId()).header("Authorization", "Bearer " + USER_TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("RETRY_WAIT"))
			.andExpect(jsonPath("$.current_attempt.attempt").value(2))
			.andExpect(jsonPath("$.current_attempt.status").value("QUEUED"));

		acquire(retried).andExpect(status().isOk());
		assertEquals("RUNNING", scalar("SELECT status FROM media_job WHERE media_job_id = ?", original.jobId()));
	}

	@Test
	void failsJobAndAssetAfterRetryableWorkerResultsExhaustAttempts() throws Exception {
		var attempt = createQueuedJob();
		for (var attemptNumber = 1; attemptNumber <= 3; attemptNumber++) {
			acquire(attempt).andExpect(status().isOk());
			submitResult(attempt, retryableFailedResult(attempt)).andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("FAILED"));
			if (attemptNumber < 3) {
				assertEquals("RETRY_WAIT",
						scalar("SELECT status FROM media_job WHERE media_job_id = ?", attempt.jobId()));
				attempt = loadAttempt(attempt, attemptNumber + 1);
			}
		}

		assertEquals("FAILED", scalar("SELECT status FROM media_job WHERE media_job_id = ?", attempt.jobId()));
		assertEquals("INGEST_FAILED",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", attempt.clipAssetId()));
		assertEquals(3, count("SELECT count(*) FROM media_job_attempt WHERE media_job_id = ?", attempt.jobId()));
		assertEquals(3, count("SELECT count(*) FROM outbox_event WHERE aggregate_id = ?", attempt.jobId()));
	}

	@Test
	void rejectsUnapprovedWorkerSoftwareWithoutAcceptingResult() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());

		submitResult(created, successResult(created, "sha256:" + "f".repeat(64))).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error_code").value("MEDIA_WORKER_SOFTWARE_UNAPPROVED"));

		assertEquals(0, count("SELECT count(*) FROM inbox_message"));
		assertEquals(0, count("SELECT count(*) FROM media_report"));
		assertEquals("RUNNING", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
	}

	@Test
	void rejectsInvalidLeaseAndWorkerIdentityWithoutChangingJob() throws Exception {
		var created = createQueuedJob();

		this.mockMvc
			.perform(post("/api/v1/internal/media-jobs/{jobId}/attempts/1/lease", created.jobId())
				.header("Authorization", "Bearer " + USER_TOKEN)
				.header("X-Media-Lease-Token", created.leaseToken())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"worker_id\":\"media-worker-test\"}"))
			.andExpect(status().isUnauthorized());

		this.mockMvc
			.perform(post("/api/v1/internal/media-jobs/{jobId}/attempts/1/lease", created.jobId())
				.header("Authorization", "Bearer " + WORKER_TOKEN)
				.header("X-Media-Lease-Token", "Z".repeat(43))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"worker_id\":\"media-worker-test\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("MEDIA_JOB_LEASE_INVALID"));

		assertEquals("QUEUED", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals("UPLOADED",
				scalar("SELECT asset_status FROM clip_asset WHERE clip_asset_id = ?", created.clipAssetId()));
	}

	/**
	 * Synthetic cancelled-state guard only; cancellation protocol/API is not implemented.
	 * Late Worker deliveries must not mutate a persisted CANCELLED job or its
	 * projections.
	 */
	@Test
	void syntheticCancelledStateGuardRejectsLateDeliveriesWithoutMutation() throws Exception {
		var created = createQueuedJob();
		var projection = CaptureMediaProjectionTestData.bindPending(this.jdbcTemplate, PROJECT_ID,
				created.clipAssetId(), Instant.now().minusSeconds(30));
		this.jdbcTemplate.update("UPDATE media_job SET status = 'CANCELLED' WHERE media_job_id = ?", created.jobId());
		this.jdbcTemplate.update("""
				UPDATE media_job_attempt SET status = 'CANCELLED'
				WHERE media_job_id = ? AND attempt = ?
				""", created.jobId(), created.attempt());

		var jobBefore = this.jdbcTemplate.queryForMap("SELECT * FROM media_job WHERE media_job_id = ?",
				created.jobId());
		var attemptBefore = this.jdbcTemplate.queryForMap("""
				SELECT * FROM media_job_attempt WHERE media_job_id = ? AND attempt = ?
				""", created.jobId(), created.attempt());
		assertEquals("CANCELLED", jobBefore.get("status"));
		assertEquals("CANCELLED", attemptBefore.get("status"));
		var assetBefore = this.jdbcTemplate.queryForMap("SELECT * FROM clip_asset WHERE clip_asset_id = ?",
				created.clipAssetId());
		var streamBefore = this.jdbcTemplate.queryForMap("SELECT * FROM camera_stream WHERE camera_stream_id = ?",
				projection.cameraStreamId());
		var sessionBefore = this.jdbcTemplate.queryForMap("SELECT * FROM capture_session WHERE capture_session_id = ?",
				projection.captureSessionId());
		var outboxCountBefore = count("SELECT count(*) FROM outbox_event");
		var auditCountBefore = count("SELECT count(*) FROM audit_event");

		acquire(created).andExpect(status().isConflict()).andExpect(jsonPath("$.error_code").value("STALE_ATTEMPT"));
		heartbeat(created, "media-worker-test").andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("STALE_ATTEMPT"));
		submitResult(created, successResult(created, SOFTWARE_DIGEST)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("MEDIA_JOB_LEASE_EXPIRED"));

		assertEquals(jobBefore,
				this.jdbcTemplate.queryForMap("SELECT * FROM media_job WHERE media_job_id = ?", created.jobId()));
		assertEquals(attemptBefore, this.jdbcTemplate.queryForMap("""
				SELECT * FROM media_job_attempt WHERE media_job_id = ? AND attempt = ?
				""", created.jobId(), created.attempt()));
		assertEquals(assetBefore, this.jdbcTemplate.queryForMap("SELECT * FROM clip_asset WHERE clip_asset_id = ?",
				created.clipAssetId()));
		assertEquals(streamBefore, this.jdbcTemplate
			.queryForMap("SELECT * FROM camera_stream WHERE camera_stream_id = ?", projection.cameraStreamId()));
		assertEquals(sessionBefore, this.jdbcTemplate
			.queryForMap("SELECT * FROM capture_session WHERE capture_session_id = ?", projection.captureSessionId()));
		assertEquals(outboxCountBefore, count("SELECT count(*) FROM outbox_event"));
		assertEquals(auditCountBefore, count("SELECT count(*) FROM audit_event"));
		assertEquals(0, count("SELECT count(*) FROM inbox_message"));
		assertEquals(0, count("SELECT count(*) FROM media_report"));
	}

	@Test
	void rejectsResultMessageIdReuseWithDifferentContent() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());
		var result = successResult(created, SOFTWARE_DIGEST);
		submitResult(created, result).andExpect(status().isOk());

		var modified = result.replace("\"duration_microseconds\":2000000", "\"duration_microseconds\":2000001");
		submitResult(created, modified).andExpect(status().isConflict())
			.andExpect(jsonPath("$.error_code").value("INBOX_MESSAGE_REUSED"));
		assertEquals(1, count("SELECT count(*) FROM media_report"));
	}

	@Test
	void rejectsUnknownResultFieldsInsteadOfSilentlyIgnoringContractDrift() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());
		var resultWithUnknownField = successResult(created, SOFTWARE_DIGEST).replace("\"error\":null",
				"\"unexpected_field\":true,\n  \"error\":null");

		submitResult(created, resultWithUnknownField).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));

		assertEquals(0, count("SELECT count(*) FROM inbox_message"));
		assertEquals(0, count("SELECT count(*) FROM media_report"));
		assertEquals("RUNNING", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
	}

	@Test
	void rejectsSuccessfulReportWithoutCompletedDigestAndDecodeVerification() throws Exception {
		var created = createQueuedJob();
		acquire(created).andExpect(status().isOk());
		var unverifiedResult = successResult(created, SOFTWARE_DIGEST)
			.replace("\"decode_verified\":true", "\"decode_verified\":false")
			.replace("\"input_digest_verified\":true", "\"input_digest_verified\":false");

		submitResult(created, unverifiedResult).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error_code").value("INVALID_REQUEST"));

		assertEquals(0, count("SELECT count(*) FROM inbox_message"));
		assertEquals(0, count("SELECT count(*) FROM media_report"));
		assertEquals("RUNNING", scalar("SELECT status FROM media_job WHERE media_job_id = ?", created.jobId()));
	}

	private org.springframework.test.web.servlet.ResultActions acquire(CreatedJob created) throws Exception {
		return this.mockMvc.perform(
				post("/api/v1/internal/media-jobs/{jobId}/attempts/{attempt}/lease", created.jobId(), created.attempt())
					.header("Authorization", "Bearer " + WORKER_TOKEN)
					.header("X-Media-Lease-Token", created.leaseToken())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"worker_id\":\"media-worker-test\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions heartbeat(CreatedJob created, String workerId)
			throws Exception {
		return this.mockMvc.perform(post("/api/v1/internal/media-jobs/{jobId}/attempts/{attempt}/heartbeat",
				created.jobId(), created.attempt())
			.header("Authorization", "Bearer " + WORKER_TOKEN)
			.header("X-Media-Lease-Token", created.leaseToken())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"worker_id\":\"" + workerId + "\"}"));
	}

	private org.springframework.test.web.servlet.ResultActions submitResult(CreatedJob created, String result)
			throws Exception {
		return this.mockMvc.perform(
				put("/api/v1/internal/media-jobs/{jobId}/attempts/{attempt}/result", created.jobId(), created.attempt())
					.header("Authorization", "Bearer " + WORKER_TOKEN)
					.header("X-Media-Lease-Token", created.leaseToken())
					.contentType(MediaType.APPLICATION_JSON)
					.content(result));
	}

	private CreatedJob createQueuedJob() throws Exception {
		var clipAssetId = insertUploadedClipAsset();
		var response = this.mockMvc
			.perform(post("/api/v1/media-jobs").header("Authorization", "Bearer " + USER_TOKEN)
				.header("Idempotency-Key", "media-execution-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "clip_asset_id": "%s",
						  "job_type": "MEDIA_PROBE"
						}
						""".formatted(clipAssetId)))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.current_attempt.status").value("QUEUED"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		var jobId = UUID.fromString(JsonPath.read(response, "$.media_job_id"));
		var leaseToken = this.jdbcTemplate.queryForObject("""
				SELECT payload_json ->> 'lease_token'
				FROM outbox_event
				WHERE aggregate_id = ?
				""", String.class, jobId);
		var messageId = this.jdbcTemplate.queryForObject("""
				SELECT message_id FROM media_job_attempt
				WHERE media_job_id = ? AND attempt = 1
				""", UUID.class, jobId);
		return new CreatedJob(jobId, clipAssetId, messageId, leaseToken, 1);
	}

	private CreatedJob loadAttempt(CreatedJob job, int attemptNumber) {
		var messageId = this.jdbcTemplate.queryForObject("""
				SELECT message_id FROM media_job_attempt
				WHERE media_job_id = ? AND attempt = ?
				""", UUID.class, job.jobId(), attemptNumber);
		var leaseToken = this.jdbcTemplate.queryForObject("""
				SELECT payload_json ->> 'lease_token'
				FROM outbox_event
				WHERE aggregate_id = ? AND (payload_json ->> 'attempt')::integer = ?
				""", String.class, job.jobId(), attemptNumber);
		return new CreatedJob(job.jobId(), job.clipAssetId(), messageId, leaseToken, attemptNumber);
	}

	private void expireAttempt(CreatedJob job, Instant now) {
		this.jdbcTemplate.update("""
				UPDATE media_job_attempt
				SET lease_started_at = ?, last_heartbeat_at = ?, lease_until = ?
				WHERE media_job_id = ? AND attempt = ?
				""", Timestamp.from(now.minusSeconds(3)), Timestamp.from(now.minusSeconds(3)),
				Timestamp.from(now.minusSeconds(2)), job.jobId(), job.attempt());
	}

	private UUID insertUploadedClipAsset() {
		var batchId = UUID.randomUUID();
		var uploadFileId = UUID.randomUUID();
		var clientFileId = UUID.randomUUID();
		var clipAssetId = UUID.randomUUID();
		var createdAt = Instant.now().minusSeconds(60);
		this.jdbcTemplate.update("""
				INSERT INTO upload_batch (
				    batch_id, project_id, idempotency_key, request_fingerprint, device_id,
				    initiated_by_actor_id,
				    file_count, total_size_bytes, manifest_sha256, status, expires_at,
				    created_at, updated_at, verified_manifest_sha256, completed_at)
				VALUES (?, ?, ?, ?, 'synthetic-device', 'synthetic-test-actor',
				        1, 1234, ?, 'COMPLETED', ?, ?, ?, ?, ?)
				""", batchId, PROJECT_ID, "execution-batch-" + batchId, SHA256, SHA256,
				Timestamp.from(createdAt.plusSeconds(3600)), Timestamp.from(createdAt), Timestamp.from(createdAt),
				SHA256, Timestamp.from(createdAt));
		this.jdbcTemplate.update("""
				INSERT INTO upload_file (
				    upload_file_id, batch_id, client_file_id, idempotency_key, request_fingerprint,
				    file_kind, content_type, size_bytes, sha256, part_size_bytes, total_parts,
				    status, created_at, updated_at, storage_bucket, object_key, storage_upload_id,
				    multipart_created_at, completion_started_at, completion_finished_at,
				    verified_sha256, completed_storage_etag)
				VALUES (?, ?, ?, ?, ?, 'VIDEO', 'video/mp4', 1234, ?, 8388608, 1, 'UPLOADED',
				        ?, ?, 'synthetic-bucket', ?, ?, ?, ?, ?, ?, 'synthetic-etag')
				""", uploadFileId, batchId, clientFileId, "execution-file-" + uploadFileId, SHA256, SHA256,
				Timestamp.from(createdAt), Timestamp.from(createdAt), "objects/" + uploadFileId,
				"multipart-" + uploadFileId, Timestamp.from(createdAt), Timestamp.from(createdAt),
				Timestamp.from(createdAt), SHA256);
		this.jdbcTemplate.update("""
				INSERT INTO clip_asset (
				    clip_asset_id, project_id, batch_id, source_upload_file_id, client_file_id,
				    content_type, size_bytes, sha256, storage_bucket, object_key, storage_etag,
				    asset_status, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'video/mp4', 1234, ?, 'synthetic-bucket', ?,
				        'synthetic-etag', 'UPLOADED', ?, ?)
				""", clipAssetId, PROJECT_ID, batchId, uploadFileId, clientFileId, SHA256, "objects/" + clipAssetId,
				Timestamp.from(createdAt), Timestamp.from(createdAt));
		return clipAssetId;
	}

	private String successResult(CreatedJob created, String softwareDigest) {
		var startedAt = Instant.now().minusSeconds(2);
		var completedAt = Instant.now().minusSeconds(1);
		return """
				{
				  "schema_version":"media-probe-result.v1",
				  "message_id":"%s",
				  "job_id":"%s",
				  "attempt":%d,
				  "lease_token":"%s",
				  "status":"SUCCEEDED",
				  "input_sha256":"%s",
				  "recipe_digest":"%s",
				  "software_digest":"%s",
				  "started_at":"%s",
				  "completed_at":"%s",
				  "report":{
				    "container_format":"mov",
				    "duration_microseconds":2000000,
				    "container_start_time_microseconds":0,
				    "video_stream_count":1,
				    "primary_video_stream_index":0,
				    "video_codec":"h264",
				    "video_profile":"High",
				    "width":1280,
				    "height":720,
				    "pixel_format":"yuv420p",
				    "color_space":"bt709",
				    "primary_video_bitrate_bps":2000000,
				    "has_b_frames":true,
				    "stream_start_time_microseconds":0,
				    "time_base_numerator":1,
				    "time_base_denominator":15360,
				    "first_pts":0,
				    "last_pts":30208,
				    "nominal_frame_rate_numerator":30,
				    "nominal_frame_rate_denominator":1,
				    "average_frame_rate_numerator":30,
				    "average_frame_rate_denominator":1,
				    "frame_count":60,
				    "variable_frame_rate":false,
				    "audio_stream_count":1,
				    "sample_aspect_ratio_numerator":1,
				    "sample_aspect_ratio_denominator":1,
				    "decode_verified":true,
				    "input_digest_verified":true,
				    "warning_codes":["AUDIO_PRESENT"]
				  },
				  "error":null
				}
				""".formatted(created.messageId(), created.jobId(), created.attempt(), created.leaseToken(), SHA256,
				MediaProbeProfile.RECIPE_DIGEST, softwareDigest, startedAt, completedAt);
	}

	private String failedResult(CreatedJob created) {
		var startedAt = Instant.now().minusSeconds(2);
		var completedAt = Instant.now().minusSeconds(1);
		return """
				{
				  "schema_version":"media-probe-result.v1",
				  "message_id":"%s",
				  "job_id":"%s",
				  "attempt":%d,
				  "lease_token":"%s",
				  "status":"FAILED",
				  "input_sha256":"%s",
				  "recipe_digest":"%s",
				  "software_digest":"%s",
				  "started_at":"%s",
				  "completed_at":"%s",
				  "report":null,
				  "error":{
				    "error_class":"NON_RETRYABLE_INPUT",
				    "error_code":"MEDIA_DECODE_FAILED",
				    "safe_message":"Synthetic video failed complete decode verification",
				    "retryable":false
				  }
				}
				""".formatted(created.messageId(), created.jobId(), created.attempt(), created.leaseToken(), SHA256,
				MediaProbeProfile.RECIPE_DIGEST, SOFTWARE_DIGEST, startedAt, completedAt);
	}

	private String retryableFailedResult(CreatedJob created) {
		var startedAt = Instant.now().minusSeconds(2);
		var completedAt = Instant.now().minusSeconds(1);
		return """
				{
				  "schema_version":"media-probe-result.v1",
				  "message_id":"%s",
				  "job_id":"%s",
				  "attempt":%d,
				  "lease_token":"%s",
				  "status":"FAILED",
				  "input_sha256":"%s",
				  "recipe_digest":"%s",
				  "software_digest":"%s",
				  "started_at":"%s",
				  "completed_at":"%s",
				  "report":null,
				  "error":{
				    "error_class":"RETRYABLE_TRANSIENT",
				    "error_code":"OBJECT_STORAGE_UNAVAILABLE",
				    "safe_message":"Synthetic object storage is temporarily unavailable",
				    "retryable":true
				  }
				}
				""".formatted(created.messageId(), created.jobId(), created.attempt(), created.leaseToken(), SHA256,
				MediaProbeProfile.RECIPE_DIGEST, SOFTWARE_DIGEST, startedAt, completedAt);
	}

	private String replayRequest(CreatedJob created, UUID deadLetterId, String bodySha256) {
		return """
				{
				  "dead_letter_id":"%s",
				  "source_message_id":"%s",
				  "source_attempt":%d,
				  "dead_letter_reason":"MEDIA_JOB_ACQUIRE_REJECTED",
				  "body_sha256":"%s",
				  "operator_reason_code":"CONFIGURATION_FIXED",
				  "change_reference":"commit-1ce0b03"
				}
				""".formatted(deadLetterId, created.messageId(), created.attempt(), bodySha256);
	}

	private int count(String sql, Object... arguments) {
		return this.jdbcTemplate.queryForObject(sql, Integer.class, arguments);
	}

	private String scalar(String sql, UUID id) {
		return this.jdbcTemplate.queryForObject(sql, String.class, id);
	}

	private record CreatedJob(UUID jobId, UUID clipAssetId, UUID messageId, String leaseToken, int attempt) {
	}

}
