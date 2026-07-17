package com.medicationvideo.platform.assetcapture;

record UploadCleanupBacklogSnapshot(
		long pendingTasks,
		long runningTasks,
		long failedTasks,
		long oldestActionableAgeSeconds) {

	static UploadCleanupBacklogSnapshot empty() {
		return new UploadCleanupBacklogSnapshot(0, 0, 0, 0);
	}

	long actionableTasks() {
		return this.pendingTasks + this.runningTasks;
	}
}
