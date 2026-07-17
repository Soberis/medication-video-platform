package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

record AssetFolderResponse(
		UUID folderId,
		UUID projectId,
		String displayName,
		long version,
		Instant createdAt,
		Instant updatedAt) {

	static AssetFolderResponse from(AssetFolder folder) {
		return new AssetFolderResponse(
				folder.getFolderId(),
				folder.getProjectId(),
				folder.getDisplayName(),
				folder.getRowVersion(),
				folder.getCreatedAt(),
				folder.getUpdatedAt());
	}

	static AssetFolderResponse from(AssetClassificationCommand command) {
		return new AssetFolderResponse(
				command.getResultFolderId(),
				command.getProjectId(),
				command.getResultDisplayName(),
				command.getResultResourceVersion(),
				command.getResultCreatedAt(),
				command.getResultUpdatedAt());
	}
}
