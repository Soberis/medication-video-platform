package com.medicationvideo.platform.assetcapture;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class ClipAssetMediaIngestServiceTests {

	private static final UUID PROJECT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

	private static final UUID CLIP_ASSET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

	private static final Instant CHANGED_AT = Instant.parse("2026-08-17T08:00:00Z");

	@Test
	void successfulMediaCompletionSchedulesHandPoseAfterTheStateTransition() {
		var assetRepository = mock(ClipAssetRepository.class);
		var streamRepository = mock(CameraStreamRepository.class);
		var objectStorage = mock(MultipartObjectStorage.class);
		var sessionProjection = mock(CaptureSessionMediaProjectionService.class);
		var handPoseScheduling = mock(HandPoseSchedulingPort.class);
		var asset = mock(ClipAsset.class);
		when(assetRepository.findForUpdate(CLIP_ASSET_ID, PROJECT_ID)).thenReturn(Optional.of(asset));
		when(streamRepository.findByClipAssetId(CLIP_ASSET_ID)).thenReturn(Optional.empty());

		var service = new ClipAssetMediaIngestService(assetRepository, streamRepository, objectStorage,
				sessionProjection, handPoseScheduling);

		service.complete(PROJECT_ID, CLIP_ASSET_ID, true, CHANGED_AT);

		InOrder order = inOrder(asset, handPoseScheduling);
		order.verify(asset).completeMediaIngest(true, CHANGED_AT);
		order.verify(handPoseScheduling).scheduleAfterMediaIngested(PROJECT_ID, CLIP_ASSET_ID);
	}

	@Test
	void failedMediaCompletionDoesNotScheduleHandPose() {
		var assetRepository = mock(ClipAssetRepository.class);
		var streamRepository = mock(CameraStreamRepository.class);
		var objectStorage = mock(MultipartObjectStorage.class);
		var sessionProjection = mock(CaptureSessionMediaProjectionService.class);
		var handPoseScheduling = mock(HandPoseSchedulingPort.class);
		var asset = mock(ClipAsset.class);
		when(assetRepository.findForUpdate(CLIP_ASSET_ID, PROJECT_ID)).thenReturn(Optional.of(asset));
		when(streamRepository.findByClipAssetId(CLIP_ASSET_ID)).thenReturn(Optional.empty());

		var service = new ClipAssetMediaIngestService(assetRepository, streamRepository, objectStorage,
				sessionProjection, handPoseScheduling);

		service.complete(PROJECT_ID, CLIP_ASSET_ID, false, CHANGED_AT);

		verify(asset).completeMediaIngest(false, CHANGED_AT);
		verifyNoInteractions(handPoseScheduling);
	}

}
