package com.medicationvideo.platform.assetcapture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class PlatformLocalCaptureSessionServiceTests {

	@Test
	void closesTheSingleViewReceiptWhenTheVerifiedLocalBatchBecomesAnEvent() {
		var projectId = UUID.randomUUID();
		var batchId = UUID.randomUUID();
		var completedAt = Instant.parse("2026-08-31T02:00:00Z");
		var batchRepository = mock(UploadBatchRepository.class);
		var clipAssetRepository = mock(ClipAssetRepository.class);
		var channelRepository = mock(CameraChannelRepository.class);
		var cameraSetRepository = mock(CameraSetConfigurationRepository.class);
		var cameraSetMemberRepository = mock(CameraSetMemberRepository.class);
		var sessionRepository = mock(CaptureSessionRepository.class);
		var snapshotRepository = mock(CameraSetSnapshotRepository.class);
		var snapshotMemberRepository = mock(CameraSetSnapshotMemberRepository.class);
		var streamRepository = mock(CameraStreamRepository.class);
		var sessionProjection = mock(CaptureSessionMediaProjectionService.class);
		var batch = mock(UploadBatch.class);
		var asset = mock(ClipAsset.class);

		when(batchRepository.findForCompletion(batchId, projectId)).thenReturn(Optional.of(batch));
		when(batch.getBatchId()).thenReturn(batchId);
		when(batch.getUploadSourceType()).thenReturn(UploadSourceType.PLATFORM_LOCAL);
		when(batch.getStatus()).thenReturn(UploadBatchStatus.COMPLETED);
		when(batch.getCompletedAt()).thenReturn(completedAt);
		when(sessionRepository.findByBatchIdAndProjectId(batchId, projectId)).thenReturn(Optional.empty());
		when(clipAssetRepository.findAllByBatchIdOrderByClientFileIdAsc(batchId)).thenReturn(List.of(asset));
		when(asset.isDeleted()).thenReturn(false);
		when(asset.getClipAssetId()).thenReturn(UUID.randomUUID());
		when(asset.getClientFileId()).thenReturn(UUID.randomUUID());
		when(asset.getStatus()).thenReturn(ClipAssetStatus.INGESTED);

		var service = new PlatformLocalCaptureSessionService(batchRepository, clipAssetRepository, channelRepository,
				cameraSetRepository, cameraSetMemberRepository, sessionRepository, snapshotRepository,
				snapshotMemberRepository, streamRepository, sessionProjection);

		var response = service.ingest(projectId, batchId);

		assertEquals(CaptureGroupReceiptState.COMPLETE, response.receiptState());
		assertEquals(List.of(1), response.receivedCameraNumbers());
		assertEquals(List.of(), response.missingCameraNumbers());
		assertEquals(completedAt, response.receiptFinalizedAt());
	}

}
