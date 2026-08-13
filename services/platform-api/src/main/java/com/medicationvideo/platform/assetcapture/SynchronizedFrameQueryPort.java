package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface SynchronizedFrameQueryPort {

	FrameQueryPage query(CaptureSyncArtifact artifact, FrameQueryRequest request);

	record FrameQueryRequest(UUID syncRevisionId, Long fromSyncFrameIndex, List<Integer> cameraNumbers, String cursor,
			int limit) {
	}

	record FrameQueryPage(List<SynchronizedFrameSlotResponse> items, String nextCursor) {
	}

	record SynchronizedFrameSlotResponse(long syncFrameIndex, long captureTimestampNs, int cameraNumber,
			UUID cameraStreamId, UUID videoId, Long sourceFrameIndex, Long sourcePtsNs, Integer sourcePtsOrdinal,
			String frameState, String frameReasonCode, Long syncErrorNs) {
	}

}
