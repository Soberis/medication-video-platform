package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CaptureSyncJobModelTests {

	private static final String SHA = "a".repeat(64);

	private static final Instant CREATED_AT = Instant.parse("2026-08-12T10:00:00Z");

	@Test
	void failedAttemptRetryKeepsTheJobAndCreatesTheNextAttempt() {
		var input = input();
		var profile = profile();
		var job = CaptureSyncJob.queue(input, profile, "CAPTURE_SYNC", "v1", "sync-model-retry-001", SHA,
				"synthetic-admin", CREATED_AT);
		job.start(CREATED_AT);
		job.fail(CREATED_AT.plusSeconds(1));

		var firstAttempt = attempt(job, 1, "first-lease-token");
		firstAttempt.acquire("worker-1", CREATED_AT, Duration.ofSeconds(30));
		firstAttempt.fail("RETRYABLE_RESOURCE", "SOURCE_READ_TIMEOUT", "synthetic timeout", true,
				CREATED_AT.plusSeconds(2));

		var jobs = mock(CaptureSyncJobRepository.class);
		var attempts = mock(CaptureSyncJobAttemptRepository.class);
		var revisions = mock(CaptureSyncRevisionRepository.class);
		var streamRevisions = mock(CaptureSyncStreamRevisionRepository.class);
		when(jobs.findForUpdate(job.getCaptureSyncJobId())).thenReturn(Optional.of(job));
		when(attempts.findCurrentForUpdate(job.getCaptureSyncJobId())).thenReturn(Optional.of(firstAttempt));
		when(attempts.save(any(CaptureSyncJobAttempt.class)))
			.thenAnswer(invocation -> invocation.getArgument(0, CaptureSyncJobAttempt.class));

		var retried = new CaptureSyncJobService(jobs, attempts, revisions, streamRevisions).retry(input.projectId(),
				job.getCaptureSyncJobId(), "synthetic-admin");

		assertEquals(job.getCaptureSyncJobId(), retried.getCaptureSyncJobId());
		assertEquals(2, retried.getAttempt());
		assertEquals(CaptureSyncJobAttemptStatus.QUEUED, retried.getStatus());
		assertEquals(CaptureSyncJobStatus.QUEUED, job.getStatus());
	}

	@Test
	void attemptLeaseAndRecoveryFieldsFollowTheExistingStateContract() {
		var attempt = attempt(UUID.randomUUID(), 1, "lease-token");
		attempt.acquire("worker-1", CREATED_AT, Duration.ofSeconds(30));

		assertEquals(CaptureSyncJobAttemptStatus.RUNNING, attempt.getStatus());
		assertTrue(attempt.isRecoverableAt(CREATED_AT.plusSeconds(30)));
		assertFalse(attempt.isRecoverableAt(CREATED_AT.plusSeconds(29)));
		assertThrows(IllegalStateException.class,
				() -> attempt.heartbeat("worker-2", CREATED_AT.plusSeconds(1), Duration.ofSeconds(30)));

		attempt.lose(CREATED_AT.plusSeconds(31));
		assertEquals(CaptureSyncJobAttemptStatus.LOST, attempt.getStatus());
		assertTrue(attempt.getErrorRetryable());
	}

	@Test
	void acquiringAQueuedAttemptReturnsTheOnlyRawLeaseToken() {
		var input = input();
		var profile = profile();
		var job = CaptureSyncJob.queue(input, profile, "CAPTURE_SYNC", "v1", "sync-model-acquire-001", SHA,
				"synthetic-admin", CREATED_AT);
		var attempt = attempt(job, 1, "queued-placeholder-token");
		var jobs = mock(CaptureSyncJobRepository.class);
		var attempts = mock(CaptureSyncJobAttemptRepository.class);
		var revisions = mock(CaptureSyncRevisionRepository.class);
		var streamRevisions = mock(CaptureSyncStreamRevisionRepository.class);
		when(jobs.findForUpdate(job.getCaptureSyncJobId())).thenReturn(Optional.of(job));
		when(attempts.findForUpdate(job.getCaptureSyncJobId(), 1)).thenReturn(Optional.of(attempt));
		when(attempts.saveAndFlush(any(CaptureSyncJobAttempt.class)))
			.thenAnswer(invocation -> invocation.getArgument(0, CaptureSyncJobAttempt.class));

		var leaseToken = new CaptureSyncJobService(jobs, attempts, revisions, streamRevisions)
			.acquire(input.projectId(), job.getCaptureSyncJobId(), 1, "worker-1", Duration.ofSeconds(30));

		assertEquals(CaptureSyncJobAttemptStatus.RUNNING, attempt.getStatus());
		assertEquals(CaptureSyncJobStatus.RUNNING, job.getStatus());
		assertTrue(attempt.matchesLeaseHash(CaptureSyncJobAttempt.hashLeaseToken(leaseToken)));
	}

	@Test
	void revisionCanOnlyBeCreatedFromASucceededAttempt() {
		var input = input();
		var profile = profile();
		var job = CaptureSyncJob.queue(input, profile, "CAPTURE_SYNC", "v1", "sync-model-revision-001", SHA,
				"synthetic-admin", CREATED_AT);
		var queuedAttempt = attempt(job, 1, "revision-lease-token");
		var summary = new CaptureSyncRevisionSummary(1, "TEST_CLOCK", "TEST_CAPTURE_TIME", null, null, 1, 1, 0, 0, 1,
				0L, 0L, 1, SHA);

		assertThrows(IllegalArgumentException.class, () -> CaptureSyncRevision.accept(job, queuedAttempt, null, profile,
				summary, "synthetic-admin", CREATED_AT));

		queuedAttempt.acquire("worker-1", CREATED_AT, Duration.ofSeconds(30));
		queuedAttempt.succeed(CREATED_AT.plusSeconds(1));
		var revision = CaptureSyncRevision.accept(job, queuedAttempt, null, profile, summary, "synthetic-admin",
				CREATED_AT.plusSeconds(1));

		assertEquals(job.getCaptureSyncJobId(), revision.getCaptureSyncJobId());
		assertEquals(queuedAttempt.getCaptureSyncJobAttemptId(), revision.getCaptureSyncJobAttemptId());
		assertEquals(SHA, revision.getLogicalContentSha256());
	}

	@Test
	void revisionPayloadMustCoverTheFrozenCameraMatrixAndMatchItsTotals() {
		var input = input();
		var profile = profile();
		var job = CaptureSyncJob.queue(input, profile, "CAPTURE_SYNC", "v1", "sync-model-matrix-001", SHA,
				"synthetic-admin", CREATED_AT);
		var summary = new CaptureSyncRevisionSummary(1, "TEST_CLOCK", "TEST_CAPTURE_TIME", null, null, 1, 1, 0, 0, 1,
				0L, 0L, 1, SHA);
		var stream = new CaptureSyncStreamRevisionInput(input.expectedCameras().getFirst(), input.streams().getFirst(),
				CaptureSyncStreamPresenceState.PRESENT, 1, 0, 0, 0L, 0L);

		assertDoesNotThrow(() -> CaptureSyncJobService.validateRevisionPayload(job, profile, summary, List.of(stream)));
		assertThrows(IllegalArgumentException.class,
				() -> CaptureSyncJobService.validateRevisionPayload(job, profile, summary, List.of()));
		var wrongTotals = new CaptureSyncStreamRevisionInput(input.expectedCameras().getFirst(),
				input.streams().getFirst(), CaptureSyncStreamPresenceState.PRESENT, 0, 1, 0, null, null);
		assertThrows(IllegalArgumentException.class,
				() -> CaptureSyncJobService.validateRevisionPayload(job, profile, summary, List.of(wrongTotals)));
	}

	@Test
	void revisionPayloadCannotReplaceFrozenStreamProvenance() {
		var input = input();
		var profile = profile();
		var job = CaptureSyncJob.queue(input, profile, "CAPTURE_SYNC", "v1", "sync-model-provenance-001", SHA,
				"synthetic-admin", CREATED_AT);
		var frozen = input.streams().getFirst();
		var replacement = new SyncStream(1, frozen.cameraStreamId(), UUID.randomUUID(), SHA, frozen.mediaReportId(),
				SHA, frozen.timeMapping(), frozen.sourceFrames());
		var stream = new CaptureSyncStreamRevisionInput(input.expectedCameras().getFirst(), replacement,
				CaptureSyncStreamPresenceState.PRESENT, 1, 0, 0, 0L, 0L);
		var summary = new CaptureSyncRevisionSummary(1, "TEST_CLOCK", "TEST_CAPTURE_TIME", null, null, 1, 1, 0, 0, 1,
				0L, 0L, 1, SHA);

		assertThrows(IllegalArgumentException.class,
				() -> CaptureSyncJobService.validateRevisionPayload(job, profile, summary, List.of(stream)));
	}

	@Test
	void productionProfileCannotBeQueuedBeforeItIsApproved() {
		var testProfile = profile();
		var productionProfile = new SyncProfile(testProfile.profileKey(), testProfile.profileVersion(),
				testProfile.profileSha256(), testProfile.algorithmKey(), testProfile.algorithmVersion(),
				testProfile.timelineRate(), testProfile.timelineStepNs(), testProfile.roundingRule(),
				testProfile.nearestTieBreak(), testProfile.allowedToleranceNs(), testProfile.referenceClockSource(),
				testProfile.anchorDriftRule(), SyncProfileApproval.PRODUCTION_APPROVED);
		var service = new CaptureSyncJobService(mock(CaptureSyncJobRepository.class),
				mock(CaptureSyncJobAttemptRepository.class), mock(CaptureSyncRevisionRepository.class),
				mock(CaptureSyncStreamRevisionRepository.class));

		assertThrows(IllegalStateException.class,
				() -> service.create(input(), productionProfile, "sync-model-production-001", "synthetic-admin"));
	}

	private static CaptureSyncJobAttempt attempt(CaptureSyncJob job, int number, String token) {
		return attempt(job.getCaptureSyncJobId(), number, token);
	}

	private static CaptureSyncJobAttempt attempt(UUID jobId, int number, String token) {
		return CaptureSyncJobAttempt.queue(jobId, number, UUID.randomUUID(),
				CaptureSyncJobAttempt.hashLeaseToken(token), CREATED_AT);
	}

	private static SyncProfile profile() {
		return SyncProfile.testOnly("TEST/G0", "v1", SHA, "CAPTURE_SYNC", "v1", Rational.of(30, 1),
				Rational.of(1_000_000_000L, 30), 20_000_000L, "TEST_CLOCK", "TEST_ANCHOR_DRIFT");
	}

	private static CaptureSyncInput input() {
		var captureId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		var memberId = UUID.fromString("00000000-0000-0000-0000-000000000102");
		var streamId = UUID.fromString("00000000-0000-0000-0000-000000000103");
		var assetId = UUID.fromString("00000000-0000-0000-0000-000000000104");
		var reportId = UUID.fromString("00000000-0000-0000-0000-000000000105");
		var stream = new SyncStream(1, streamId, assetId, SHA, reportId, SHA,
				new StreamTimeMapping(0, 0, null, "TEST", "SYNTHETIC", 1, true),
				List.of(SourceFrame.valid(0, 0, 0, 0)));
		return new CaptureSyncInput(captureId, captureId,
				UUID.nameUUIDFromBytes("snapshot".getBytes(StandardCharsets.UTF_8)), "sync-group", SHA,
				List.of(new ExpectedCamera(memberId, 1)), List.of(stream));
	}

}
