package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record ClipAssetFolderResponse(
		UUID clipAssetId,
		UUID projectId,
		UUID folderId,
		long version,
		Instant updatedAt) {

	static ClipAssetFolderResponse from(AssetClassificationCommand command) {
		return new ClipAssetFolderResponse(
				command.getResultClipAssetId(),
				command.getProjectId(),
				command.getResultFolderId(),
				command.getResultResourceVersion(),
				command.getResultUpdatedAt());
	}
}
