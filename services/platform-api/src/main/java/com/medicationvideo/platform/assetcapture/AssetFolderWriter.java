package com.medicationvideo.platform.assetcapture;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class AssetFolderWriter {

	private final JdbcTemplate jdbcTemplate;

	AssetFolderWriter(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	int unclassifyAssets(UUID projectId, UUID folderId, Instant changedAt) {
		return this.jdbcTemplate.update("""
				UPDATE clip_asset
				SET folder_id = NULL, updated_at = ?, version = version + 1
				WHERE project_id = ? AND folder_id = ?
				""",
				Timestamp.from(changedAt),
				projectId,
				folderId);
	}
}
