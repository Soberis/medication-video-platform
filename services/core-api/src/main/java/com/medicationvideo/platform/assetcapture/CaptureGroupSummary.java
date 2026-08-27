package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CaptureGroupSummary(UUID captureGroupId, String captureGroupKey, Instant deviceTimeUtc,
		CaptureGroupReceiptState receiptState, int expectedCameraCount, int receivedCameraCount,
		List<Integer> missingCameraNumbers, Instant createdAt) {

	public CaptureGroupSummary {
		missingCameraNumbers = List.copyOf(missingCameraNumbers);
	}

}
