package com.medicationvideo.platform.assetcapture;

record UploadCleanupSummary(UploadCleanupStatus status, int taskCount, int completedTaskCount, int failedTaskCount) {

	static UploadCleanupSummary fromCounts(int total, int running, int succeeded, int failed) {
		var status = total == 0 ? UploadCleanupStatus.NOT_REQUIRED
				: failed > 0 ? UploadCleanupStatus.FAILED : succeeded == total ? UploadCleanupStatus.SUCCEEDED
						: running > 0 ? UploadCleanupStatus.IN_PROGRESS : UploadCleanupStatus.PENDING;
		return new UploadCleanupSummary(status, total, succeeded, failed);
	}
}
