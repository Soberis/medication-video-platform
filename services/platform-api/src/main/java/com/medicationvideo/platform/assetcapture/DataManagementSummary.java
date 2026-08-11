package com.medicationvideo.platform.assetcapture;

import java.util.Map;

public record DataManagementSummary(long totalVideos, long totalDuration, long storageBytes,
		Map<String, Long> countsBySource, Map<String, Long> countsByCamera, Map<String, Long> countsByStatus,
		long pendingAnnotation, long pendingReview, long pendingCleaning, long usableCount) {
}
