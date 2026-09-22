package com.medicationvideo.platform.aicandidate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;

class AiCandidateJobAttemptQueueServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");

	private static final UUID CLIP_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000013");

	private static final UUID CAPTURE_GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000014");

	private AiCandidateJobAttemptRepository attemptRepository;

	private AiJobDispatchPort dispatchPort;

	private AssetCatalogQueryService assetCatalog;

	private AiCandidateJobAttemptQueueService service;

	@BeforeEach
	void setUp() {
		this.attemptRepository = mock(AiCandidateJobAttemptRepository.class);
		this.dispatchPort = mock(AiJobDispatchPort.class);
		this.assetCatalog = mock(AssetCatalogQueryService.class);
		this.service = new AiCandidateJobAttemptQueueService(this.attemptRepository, this.dispatchPort,
				new AiLeaseCapabilityFactory(), new ActionJobEnvelopeFactory(this.assetCatalog, null));
	}

	@Test
	void retriedActionAttemptIsPublishedAsAnActionEnvelope() {
		when(this.assetCatalog.resolveCaptureGroupId(CLIP_ASSET_ID)).thenReturn(CAPTURE_GROUP_ID);
		var job = actionJob();

		this.service.enqueue(job, 2, Instant.parse("2026-09-10T00:00:00Z"));

		var captured = ArgumentCaptor.forClass(AiActionJobEnvelope.class);
		verify(this.dispatchPort).enqueueAction(captured.capture(), any(Instant.class));
		assertEquals("ai-action-job.v1", captured.getValue().schemaVersion());
		assertEquals(job.getAiJobId(), captured.getValue().jobId());
		assertEquals(2, captured.getValue().attempt());
		assertEquals(CAPTURE_GROUP_ID, captured.getValue().captureGroupId());
		verify(this.dispatchPort, never()).enqueue(any(AiHandPoseJobEnvelope.class), any(Instant.class));
	}

	@Test
	void handPoseAttemptStillUsesTheHandPoseEnvelope() {
		var job = mock(AiCandidateJob.class);
		when(job.getAlgorithmKey()).thenReturn(HandPoseProfile.ALGORITHM_KEY);

		this.service.enqueue(job, 2, Instant.parse("2026-09-10T00:00:00Z"));

		verify(this.dispatchPort).enqueue(any(AiHandPoseJobEnvelope.class), any(Instant.class));
		verify(this.dispatchPort, never()).enqueueAction(any(AiActionJobEnvelope.class), any(Instant.class));
	}

	@Test
	void actionAttemptWithoutACaptureGroupIsRejectedInsteadOfMisrouted() {
		when(this.assetCatalog.resolveCaptureGroupId(CLIP_ASSET_ID)).thenReturn(null);

		var exception = assertThrows(HandPoseJobException.class,
				() -> this.service.enqueue(actionJob(), 2, Instant.parse("2026-09-10T00:00:00Z")));

		assertEquals("AI_ACTION_SOURCE_INVALID", exception.getErrorCode());
		verify(this.dispatchPort, never()).enqueueAction(any(AiActionJobEnvelope.class), any(Instant.class));
	}

	private static AiCandidateJob actionJob() {
		return AiCandidateJob.queueAction(PROJECT_ID, CLIP_ASSET_ID, AiCandidateJob.ACTION_MODEL_SHA256, "a".repeat(64),
				2048L, "video/mp4", "retry-key-0001", "b".repeat(64), "AUTO_AFTER_MEDIA_INGESTED", Instant.EPOCH);
	}

}
