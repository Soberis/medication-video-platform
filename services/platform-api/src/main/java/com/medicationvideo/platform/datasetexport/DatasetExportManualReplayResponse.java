package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.UUID;

record DatasetExportManualReplayResponse(UUID replayId, UUID datasetExportId, UUID deadLetterId, int sourceAttempt,
		int newAttempt, DatasetExportStatus resultingExportStatus, Instant createdAt) {

	static DatasetExportManualReplayResponse from(DatasetExportManualReplay replay) {
		return new DatasetExportManualReplayResponse(replay.getReplayId(), replay.getDatasetExportId(),
				replay.getDeadLetterId(), replay.getSourceAttempt(), replay.getNewAttempt(), DatasetExportStatus.QUEUED,
				replay.getCreatedAt());
	}

}
