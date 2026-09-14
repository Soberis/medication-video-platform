package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;
import com.medicationvideo.platform.assetcapture.ClipAssetInputSummary;
import com.medicationvideo.platform.assetcapture.ClipAssetStatus;
import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;
import com.medicationvideo.platform.mediaprivacy.MediaDurationQueryService;

class HandPoseJobServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID CLIP_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final String INPUT_SHA = "b".repeat(64);

	private static final String AUTO_ACTOR = "AUTO_AFTER_MEDIA_INGESTED";

	@Test
	void refusesQueueingBeforeThePhaseGateIsExplicitlyEnabled() {
		var fixture = fixture(3, false);

		var exception = assertThrows(HandPoseJobException.class, () -> fixture.service.queue(PROJECT_ID, CLIP_ASSET_ID,
				"actor", "idempotency-key-0001", "request", "trace"));

		assertEquals("AI_HAND_POSE_FEATURE_NOT_ENABLED", exception.getErrorCode());
		verifyNoInteractions(fixture.access);
	}

	@Test
	void automaticallyQueuesAHandPoseJobAsASystemActionAfterMediaIngest() {
		var fixture = fixture(3, true);
		fixture.prepareReadyForQueue();

		var response = fixture.service.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);

		assertEquals("QUEUED", response.status());
		var job = fixture.savedJob.get();
		assertNotNull(job);
		assertEquals(AUTO_ACTOR, job.getRequestedBy());
		assertEquals("auto-after-media-ingested-" + CLIP_ASSET_ID, job.getIdempotencyKey());
		var audit = fixture.auditEvent.get();
		assertNotNull(audit);
		assertEquals(AuditActorType.SYSTEM, audit.actorType());
		assertEquals(AUTO_ACTOR, audit.actorId());
		assertEquals("SYSTEM/AUTO_AFTER_MEDIA_INGESTED", audit.authorizationReference());
		assertEquals("AI_HAND_POSE_JOB_AUTO_QUEUED", audit.action());
		verify(fixture.dispatch).enqueue(any(), any());
	}

	@Test
	void repeatedAutomaticCompletionReusesItsIdempotencyReceipt() {
		var fixture = fixture(3, true);
		fixture.prepareReadyForQueue();

		var first = fixture.service.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);
		var second = fixture.service.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);

		assertEquals(first.aiJobId(), second.aiJobId());
		verify(fixture.repository, times(1)).save(any(AiCandidateJob.class));
		verify(fixture.attemptRepository, times(1)).save(any(AiCandidateJobAttempt.class));
		verify(fixture.dispatch, times(1)).enqueue(any(), any());
		verify(fixture.auditRecorder, times(1)).record(any(AuditEventCommand.class));
	}

	@Test
	void automaticQueueReusesAnExistingEquivalentNaturalKeyJob() {
		var fixture = fixture(3, true);
		fixture.prepareReadyForQueue();
		var existing = queuedJob("human-actor");
		when(fixture.repository
			.findByProjectIdAndClipAssetIdAndInputSha256AndAlgorithmKeyAndAlgorithmVersionAndModelSha256AndSamplingFpsAndProfileSha256(
					any(), any(), any(), any(), any(), any(), eq(HandPoseProfile.SAMPLING_FPS), any()))
			.thenReturn(Optional.of(existing));

		var response = fixture.service.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);

		assertEquals(existing.getAiJobId(), response.aiJobId());
		verify(fixture.repository, times(0)).save(any(AiCandidateJob.class));
		verify(fixture.attemptRepository, times(0)).save(any(AiCandidateJobAttempt.class));
		verify(fixture.dispatch, times(0)).enqueue(any(), any());
	}

	@Test
	void manualQueueReusesAnAutomaticallyCreatedEquivalentNaturalKeyJob() {
		var fixture = fixture(3, true);
		fixture.prepareReadyForQueue();
		var automatic = fixture.service.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);
		var job = fixture.savedJob.get();
		when(fixture.repository
			.findByProjectIdAndClipAssetIdAndInputSha256AndAlgorithmKeyAndAlgorithmVersionAndModelSha256AndSamplingFpsAndProfileSha256(
					any(), any(), any(), any(), any(), any(), eq(HandPoseProfile.SAMPLING_FPS), any()))
			.thenReturn(Optional.of(job));
		when(fixture.access.lockAndRequireMember(PROJECT_ID, "actor")).thenReturn(ProjectRole.MEMBER);

		var manual = fixture.service.queue(PROJECT_ID, CLIP_ASSET_ID, "actor", "manual-key-0001", "request", "trace");

		assertEquals(automatic.aiJobId(), manual.aiJobId());
		verify(fixture.repository, times(1)).save(any(AiCandidateJob.class));
		verify(fixture.dispatch, times(1)).enqueue(any(), any());
	}

	@Test
	void cameraHandDetectionDisabledSkipsAutomaticQueueWithoutChangingTheMediaPath() {
		var fixture = fixture(3, true);
		fixture.prepareReadyForQueue(false);

		var exception = assertThrows(HandPoseJobException.class,
				() -> fixture.service.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID));

		assertEquals("CLIP_ASSET_CAMERA_HAND_DETECTION_DISABLED", exception.getErrorCode());
		verifyNoInteractions(fixture.dispatch, fixture.auditRecorder);
	}

	@Test
	void disabledFeatureDoesNotEnterAutomaticQueue() {
		var fixture = fixture(3, false);

		var exception = assertThrows(HandPoseJobException.class,
				() -> fixture.service.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID));

		assertEquals("AI_HAND_POSE_FEATURE_NOT_ENABLED", exception.getErrorCode());
		verifyNoInteractions(fixture.assetCatalog, fixture.repository, fixture.dispatch);
	}

	@Test
	void missingApprovedModelDoesNotEnterAutomaticQueue() {
		var fixture = fixture(3, true, "");

		var exception = assertThrows(HandPoseJobException.class,
				() -> fixture.service.queueAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID));

		assertEquals("AI_HAND_POSE_MODEL_NOT_CONFIGURED", exception.getErrorCode());
		verifyNoInteractions(fixture.assetCatalog, fixture.repository, fixture.dispatch);
	}

	@Test
	void retriesAttemptOneOnTheSameJobAndQueuesAttemptTwo() {
		var fixture = fixture(3, true);
		var job = failedJob();
		var failedAttempt = failedAttempt(1, "AI_HAND_POSE_INFERENCE_FAILED", true);
		fixture.prepare(job, failedAttempt);

		var response = fixture.service.retry(PROJECT_ID, CLIP_ASSET_ID, "actor", "retry-key-0001", "request", "trace");

		assertEquals(job.getAiJobId(), response.aiJobId());
		assertEquals("QUEUED", response.status());
		assertEquals(2, fixture.latestAttempt.get().getAttempt());
		assertEquals(AiCandidateJobAttemptStatus.QUEUED, fixture.latestAttempt.get().getStatus());
		verify(fixture.dispatch).enqueue(any(), any());
		verify(fixture.repository).findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(PROJECT_ID,
				CLIP_ASSET_ID, HandPoseProfile.ALGORITHM_KEY);
	}

	@Test
	void retryFindsNoHandPoseJobWhenOnlyAnActionJobExists() {
		var fixture = fixture(3, true);
		fixture.prepare(failedJob(), failedAttempt(1, "AI_HAND_POSE_INFERENCE_FAILED", true));
		when(fixture.repository.findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(PROJECT_ID,
				CLIP_ASSET_ID, HandPoseProfile.ALGORITHM_KEY))
			.thenReturn(Optional.empty());

		var exception = assertThrows(HandPoseJobException.class, () -> fixture.service.retry(PROJECT_ID, CLIP_ASSET_ID,
				"actor", "retry-key-action-only", "request", "trace"));

		assertEquals("AI_HAND_POSE_JOB_NOT_FOUND", exception.getErrorCode());
		verify(fixture.dispatch, org.mockito.Mockito.never()).enqueue(any(), any());
	}

	@Test
	void reusesTheSameRetryIdempotencyReceiptWithoutCreatingAnotherAttempt() {
		var fixture = fixture(3, true);
		var job = failedJob();
		fixture.prepare(job, failedAttempt(1, "AI_HAND_POSE_INFERENCE_FAILED", true));

		fixture.service.retry(PROJECT_ID, CLIP_ASSET_ID, "actor", "retry-key-0002", "request-1", "trace-1");
		var secondResponse = fixture.service.retry(PROJECT_ID, CLIP_ASSET_ID, "actor", "retry-key-0002", "request-2",
				"trace-2");

		assertEquals(job.getAiJobId(), secondResponse.aiJobId());
		assertEquals("QUEUED", secondResponse.status());
		assertEquals(2, fixture.latestAttempt.get().getAttempt());
		verify(fixture.attemptRepository, times(1)).save(any(AiCandidateJobAttempt.class));
		verify(fixture.dispatch, times(1)).enqueue(any(), any());
	}

	@Test
	void rejectsAFailedAttemptWhosePersistedRetryabilityIsFalse() {
		var fixture = fixture(3, true);
		var job = failedJob();
		fixture.prepare(job, failedAttempt(1, "AI_HAND_POSE_INPUT_EXCEEDS_P0_RESULT_LIMIT", false));

		var exception = assertThrows(HandPoseJobException.class,
				() -> fixture.service.retry(PROJECT_ID, CLIP_ASSET_ID, "actor", "retry-key-0003", "request", "trace"));

		assertEquals("AI_HAND_POSE_RETRY_NOT_ALLOWED", exception.getErrorCode());
		assertEquals(1, fixture.latestAttempt.get().getAttempt());
	}

	@Test
	void rejectsRetryWhenTheConfiguredAttemptLimitHasBeenReached() {
		var fixture = fixture(2, true);
		var job = failedJob();
		fixture.prepare(job, failedAttempt(2, "AI_HAND_POSE_INFERENCE_FAILED", true));

		var exception = assertThrows(HandPoseJobException.class,
				() -> fixture.service.retry(PROJECT_ID, CLIP_ASSET_ID, "actor", "retry-key-0004", "request", "trace"));

		assertEquals("AI_HAND_POSE_RETRY_LIMIT_REACHED", exception.getErrorCode());
		assertEquals(2, fixture.latestAttempt.get().getAttempt());
	}

	@Test
	void exposesOnlyWorkerFailuresAsRetryableAndStillAppliesTheConfiguredLimit() {
		assertTrue(HandPoseRetryPolicy.isRetryable("AI_HAND_POSE_INFERENCE_FAILED"));
		assertTrue(HandPoseRetryPolicy.isRetryable("AI_HAND_POSE_INVALID_OUTPUT"));
		assertTrue(HandPoseRetryPolicy.isRetryable("AI_HAND_POSE_INVALID_OUTPUT", 1, 3));
		assertFalse(HandPoseRetryPolicy.isRetryable("AI_HAND_POSE_INVALID_OUTPUT", 3, 3));
		assertFalse(HandPoseRetryPolicy.isRetryable("AI_HAND_POSE_INPUT_EXCEEDS_P0_RESULT_LIMIT"));
		assertFalse(HandPoseRetryPolicy.isRetryable("AI_HAND_POSE_MODEL_NOT_CONFIGURED"));
	}

	@Test
	void readsAJobWithTheNonLockingAttemptQuery() {
		var fixture = fixture(3, true);
		var job = failedJob();
		var latestAttempt = failedAttempt(1, "AI_HAND_POSE_INFERENCE_FAILED", true);
		when(fixture.access.requireMember(PROJECT_ID, "actor")).thenReturn(ProjectRole.MEMBER);
		when(fixture.repository.findById(job.getAiJobId())).thenReturn(Optional.of(job));
		when(fixture.attemptRepository.findFirstByAiJobIdOrderByAttemptDesc(job.getAiJobId()))
			.thenReturn(Optional.of(latestAttempt));

		var response = fixture.service.get(PROJECT_ID, job.getAiJobId(), "actor");

		assertEquals(job.getAiJobId(), response.aiJobId());
		assertEquals("FAILED", response.status());
		verify(fixture.attemptRepository).findFirstByAiJobIdOrderByAttemptDesc(job.getAiJobId());
		verify(fixture.attemptRepository, org.mockito.Mockito.never()).findLatestForUpdate(job.getAiJobId());
	}

	private static AiCandidateJob failedJob() {
		var job = AiCandidateJob.queue(PROJECT_ID, CLIP_ASSET_ID, "a".repeat(64), INPUT_SHA, 128, "video/mp4",
				"initial-key-0001", "c".repeat(64), "actor", Instant.EPOCH);
		job.fail(Instant.EPOCH.plusSeconds(1));
		return job;
	}

	private static AiCandidateJobAttempt failedAttempt(int attempt, String failureReason, boolean retryable) {
		var value = mock(AiCandidateJobAttempt.class);
		when(value.getAttempt()).thenReturn(attempt);
		when(value.getStatus()).thenReturn(AiCandidateJobAttemptStatus.FAILED);
		when(value.getFailureReason()).thenReturn(failureReason);
		when(value.isRetryable()).thenReturn(retryable);
		when(value.getStartedAt()).thenReturn(null);
		return value;
	}

	private static Fixture fixture(int maxAttempts, boolean enabled) {
		return fixture(maxAttempts, enabled, "a".repeat(64));
	}

	private static Fixture fixture(int maxAttempts, boolean enabled, String modelSha256) {
		var access = mock(ProjectAccessService.class);
		var assetCatalog = mock(AssetCatalogQueryService.class);
		var repository = mock(AiCandidateJobRepository.class);
		var receiptRepository = mock(AiCandidateJobIdempotencyReceiptRepository.class);
		var auditRecorder = mock(AuditEventRecorder.class);
		var attemptRepository = mock(AiCandidateJobAttemptRepository.class);
		var dispatch = mock(AiJobDispatchPort.class);
		var durationQueryService = mock(MediaDurationQueryService.class);
		var queue = new AiCandidateJobAttemptQueueService(attemptRepository, dispatch, new AiLeaseCapabilityFactory(),
				new ActionJobEnvelopeFactory(assetCatalog, null));
		var service = new HandPoseJobService(access, assetCatalog, repository, receiptRepository, auditRecorder, queue,
				durationQueryService, modelSha256, enabled, maxAttempts);
		return new Fixture(service, access, assetCatalog, repository, receiptRepository, attemptRepository, dispatch,
				auditRecorder, durationQueryService);
	}

	private static AiCandidateJob queuedJob(String requestedBy) {
		var fingerprint = HandPoseProfile.sha256(String.join("\n", PROJECT_ID.toString(), CLIP_ASSET_ID.toString(),
				"a".repeat(64), HandPoseProfile.PROFILE_SHA256));
		return AiCandidateJob.queue(PROJECT_ID, CLIP_ASSET_ID, "a".repeat(64), INPUT_SHA, 128, "video/mp4",
				"existing-key-0001", fingerprint, requestedBy, Instant.EPOCH);
	}

	private static final class Fixture {

		private final HandPoseJobService service;

		private final ProjectAccessService access;

		private final AssetCatalogQueryService assetCatalog;

		private final AiCandidateJobRepository repository;

		private final AiCandidateJobIdempotencyReceiptRepository receiptRepository;

		private final AiCandidateJobAttemptRepository attemptRepository;

		private final AiJobDispatchPort dispatch;

		private final AuditEventRecorder auditRecorder;

		private final MediaDurationQueryService durationQueryService;

		private final AtomicReference<AiCandidateJobAttempt> latestAttempt = new AtomicReference<>();

		private final AtomicReference<AiCandidateJob> savedJob = new AtomicReference<>();

		private final AtomicReference<AuditEventCommand> auditEvent = new AtomicReference<>();

		private Fixture(HandPoseJobService service, ProjectAccessService access, AssetCatalogQueryService assetCatalog,
				AiCandidateJobRepository repository, AiCandidateJobIdempotencyReceiptRepository receiptRepository,
				AiCandidateJobAttemptRepository attemptRepository, AiJobDispatchPort dispatch,
				AuditEventRecorder auditRecorder, MediaDurationQueryService durationQueryService) {
			this.service = service;
			this.access = access;
			this.assetCatalog = assetCatalog;
			this.repository = repository;
			this.receiptRepository = receiptRepository;
			this.attemptRepository = attemptRepository;
			this.dispatch = dispatch;
			this.auditRecorder = auditRecorder;
			this.durationQueryService = durationQueryService;
			doAnswer(invocation -> {
				this.auditEvent.set(invocation.getArgument(0));
				return null;
			}).when(this.auditRecorder).record(any(AuditEventCommand.class));
		}

		void prepare(AiCandidateJob job, AiCandidateJobAttempt failedAttempt) {
			latestAttempt.set(failedAttempt);
			when(this.access.lockAndRequireMember(PROJECT_ID, "actor")).thenReturn(ProjectRole.MEMBER);
			when(this.assetCatalog.getClipAssetInputSummary(PROJECT_ID, CLIP_ASSET_ID))
				.thenReturn(new ClipAssetInputSummary(CLIP_ASSET_ID, PROJECT_ID, "video/mp4", 128, "b".repeat(64),
						ClipAssetStatus.INGESTED, "OVERVIEW", true, Instant.EPOCH, Instant.EPOCH));
			when(this.durationQueryService.findLatestVerifiedDurationFact(PROJECT_ID, CLIP_ASSET_ID))
				.thenReturn(Optional.of(new MediaDurationQueryService.VerifiedDurationFact(UUID.randomUUID(), INPUT_SHA,
						"c".repeat(64), 1_000_000, Instant.EPOCH)));
			when(this.repository.findTopByProjectIdAndClipAssetIdAndAlgorithmKeyOrderByCreatedAtDesc(PROJECT_ID,
					CLIP_ASSET_ID, HandPoseProfile.ALGORITHM_KEY))
				.thenReturn(Optional.of(job));
			when(this.repository.findById(job.getAiJobId())).thenReturn(Optional.of(job));
			var receipt = new AtomicReference<AiCandidateJobIdempotencyReceipt>();
			when(this.receiptRepository.findByProjectIdAndIdempotencyKey(eq(PROJECT_ID), any()))
				.thenAnswer(invocation -> Optional.ofNullable(receipt.get()));
			doAnswer(invocation -> {
				receipt.set(invocation.getArgument(0));
				return invocation.getArgument(0);
			}).when(this.receiptRepository).save(any(AiCandidateJobIdempotencyReceipt.class));
			when(this.attemptRepository.findLatestForUpdate(job.getAiJobId()))
				.thenAnswer(invocation -> List.of(this.latestAttempt.get()));
			doAnswer(invocation -> {
				this.latestAttempt.set(invocation.getArgument(0));
				return invocation.getArgument(0);
			}).when(this.attemptRepository).save(any(AiCandidateJobAttempt.class));
		}

		void prepareReadyForQueue() {
			prepareReadyForQueue(true);
		}

		void prepareReadyForQueue(boolean enabledForHandDetection) {
			var receipt = new AtomicReference<AiCandidateJobIdempotencyReceipt>();
			var autoReceiptKey = "queue:auto-after-media-ingested-" + CLIP_ASSET_ID;
			when(this.receiptRepository.findByProjectIdAndIdempotencyKey(eq(PROJECT_ID), any()))
				.thenAnswer(invocation -> autoReceiptKey.equals(invocation.getArgument(1))
						? Optional.ofNullable(receipt.get()) : Optional.empty());
			doAnswer(invocation -> {
				receipt.set(invocation.getArgument(0));
				return invocation.getArgument(0);
			}).when(this.receiptRepository).save(any(AiCandidateJobIdempotencyReceipt.class));
			when(this.assetCatalog.getClipAssetInputSummary(PROJECT_ID, CLIP_ASSET_ID))
				.thenReturn(new ClipAssetInputSummary(CLIP_ASSET_ID, PROJECT_ID, "video/mp4", 128, INPUT_SHA,
						ClipAssetStatus.INGESTED, "OVERVIEW", enabledForHandDetection, Instant.EPOCH, Instant.EPOCH));
			when(this.durationQueryService.findLatestVerifiedDurationFact(PROJECT_ID, CLIP_ASSET_ID))
				.thenReturn(Optional.of(new MediaDurationQueryService.VerifiedDurationFact(UUID.randomUUID(), INPUT_SHA,
						"c".repeat(64), 1_000_000, Instant.EPOCH)));
			when(this.repository
				.findByProjectIdAndClipAssetIdAndInputSha256AndAlgorithmKeyAndAlgorithmVersionAndModelSha256AndSamplingFpsAndProfileSha256(
						any(), any(), any(), any(), any(), any(), eq(HandPoseProfile.SAMPLING_FPS), any()))
				.thenReturn(Optional.empty());
			doAnswer(invocation -> {
				var job = invocation.getArgument(0, AiCandidateJob.class);
				this.savedJob.set(job);
				return job;
			}).when(this.repository).save(any(AiCandidateJob.class));
			when(this.repository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(this.savedJob.get()));
			when(this.attemptRepository.findLatestForUpdate(any())).thenReturn(List.of());
		}

	}

}
