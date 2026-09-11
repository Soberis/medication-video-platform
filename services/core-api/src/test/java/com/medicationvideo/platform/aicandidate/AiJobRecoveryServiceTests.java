package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

class AiJobRecoveryServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID CLIP_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final Instant NOW = Instant.parse("2026-08-11T12:00:00Z");

	@Test
	void recoversOnlyTheCurrentRunningAttemptAfterLeaseAndGraceExpire() {
		var fixture = fixture(3);
		var job = queuedJob(NOW.minus(Duration.ofMinutes(20)));
		var attempt = queuedAttempt(job, 1, NOW.minus(Duration.ofMinutes(20)));
		job.start(NOW.minus(Duration.ofMinutes(15)));
		attempt.acquire("synthetic-ai-worker", NOW.minus(Duration.ofMinutes(15)), Duration.ofMinutes(1));
		fixture.prepare(job, attempt, true);

		assertEquals(1, fixture.service.recoverExpiredAttempts(NOW));
		assertEquals(AiCandidateJobStatus.QUEUED, job.getStatus());
		assertEquals(AiCandidateJobAttemptStatus.FAILED, attempt.getStatus());
		assertEquals("AI_HAND_POSE_WORKER_LEASE_LOST", attempt.getFailureReason());
		assertTrue(attempt.isRetryable());
		verify(fixture.queue).enqueue(job, 2, NOW);
	}

	@Test
	void rechecksFreshCurrentAttemptUnderLockBeforeRecoveringAStaleCandidateId() {
		var fixture = fixture(3);
		var job = queuedJob(NOW.minus(Duration.ofMinutes(10)));
		var freshAttempt = queuedAttempt(job, 2, NOW.minus(Duration.ofMinutes(1)));
		job.start(NOW.minus(Duration.ofMinutes(1)));
		freshAttempt.acquire("synthetic-ai-worker", NOW.minus(Duration.ofMinutes(1)), Duration.ofMinutes(10));
		fixture.prepare(job, freshAttempt, true);

		assertEquals(0, fixture.service.recoverExpiredAttempts(NOW));
		assertEquals(AiCandidateJobAttemptStatus.RUNNING, freshAttempt.getStatus());
		verify(fixture.queue, never()).enqueue(any(), any(Integer.class), any());
	}

	@Test
	void failsTheJobWhenRecoveryWouldExceedTheAttemptLimit() {
		var fixture = fixture(3);
		var job = queuedJob(NOW.minus(Duration.ofMinutes(20)));
		var attempt = queuedAttempt(job, 3, NOW.minus(Duration.ofMinutes(20)));
		job.start(NOW.minus(Duration.ofMinutes(15)));
		attempt.acquire("synthetic-ai-worker", NOW.minus(Duration.ofMinutes(15)), Duration.ofMinutes(1));
		fixture.prepare(job, attempt, true);

		assertEquals(1, fixture.service.recoverExpiredAttempts(NOW));
		assertEquals(AiCandidateJobStatus.FAILED, job.getStatus());
		assertEquals(AiCandidateJobAttemptStatus.FAILED, attempt.getStatus());
		verify(fixture.queue, never()).enqueue(any(), any(Integer.class), any());
	}

	@Test
	void republishesAQueuedAttemptWhosePublishedMessageWasLost() {
		var fixture = fixture(3);
		var job = queuedJob(NOW.minus(Duration.ofMinutes(20)));
		var attempt = queuedAttempt(job, 1, NOW.minus(Duration.ofMinutes(20)));
		fixture.prepareQueued(job, attempt);

		assertEquals(1, fixture.service.recoverExpiredAttempts(NOW));
		assertEquals(AiCandidateJobStatus.QUEUED, job.getStatus());
		assertEquals(AiCandidateJobAttemptStatus.FAILED, attempt.getStatus());
		assertEquals("AI_WORKER_MESSAGE_UNDELIVERED", attempt.getFailureReason());
		assertTrue(attempt.isRetryable());
		verify(fixture.queue).enqueue(job, 2, NOW);
	}

	private static AiCandidateJob queuedJob(Instant createdAt) {
		return AiCandidateJob.queue(PROJECT_ID, CLIP_ASSET_ID, "a".repeat(64), "b".repeat(64), 128, "video/mp4",
				"idempotency-key-0001", "c".repeat(64), "synthetic", createdAt);
	}

	private static AiCandidateJobAttempt queuedAttempt(AiCandidateJob job, int attempt, Instant createdAt) {
		return AiCandidateJobAttempt.queue(job.getAiJobId(), attempt, UUID.randomUUID(), "d".repeat(64), createdAt);
	}

	private static Fixture fixture(int maxAttempts) {
		var jobs = mock(AiCandidateJobRepository.class);
		var attempts = mock(AiCandidateJobAttemptRepository.class);
		var queue = mock(AiCandidateJobAttemptQueueService.class);
		var transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
		var service = new AiJobRecoveryService(jobs, attempts, queue, transactionManager, Duration.ofMinutes(5),
				maxAttempts, 20);
		return new Fixture(service, jobs, attempts, queue);
	}

	private record Fixture(AiJobRecoveryService service, AiCandidateJobRepository jobs,
			AiCandidateJobAttemptRepository attempts, AiCandidateJobAttemptQueueService queue) {

		void prepare(AiCandidateJob job, AiCandidateJobAttempt attempt, boolean expiredLeaseCandidate) {
			when(this.attempts.findRecoverableJobIds(any(), any(Integer.class)))
				.thenReturn(expiredLeaseCandidate ? List.of(job.getAiJobId()) : List.of());
			when(this.attempts.findUndeliveredQueuedJobIds(any(), any(Integer.class))).thenReturn(List.of());
			when(this.jobs.findForUpdate(job.getAiJobId())).thenReturn(Optional.of(job));
			when(this.attempts.findCurrentForUpdate(job.getAiJobId())).thenReturn(Optional.of(attempt));
		}

		void prepareQueued(AiCandidateJob job, AiCandidateJobAttempt attempt) {
			when(this.attempts.findRecoverableJobIds(any(), any(Integer.class))).thenReturn(List.of());
			when(this.attempts.findUndeliveredQueuedJobIds(any(), any(Integer.class)))
				.thenReturn(List.of(job.getAiJobId()));
			when(this.jobs.findForUpdate(job.getAiJobId())).thenReturn(Optional.of(job));
			when(this.attempts.findCurrentForUpdate(job.getAiJobId())).thenReturn(Optional.of(attempt));
		}
	}

}
