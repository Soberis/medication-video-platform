package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "asset_classification_command")
class AssetClassificationCommand {

	@Id
	@Column(name = "command_id", nullable = false, updatable = false)
	private UUID commandId;

	@Column(name = "project_id", nullable = false, updatable = false)
	private UUID projectId;

	@Column(name = "performed_by", nullable = false, length = 128, updatable = false)
	private String performedBy;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_fingerprint", nullable = false, length = 64, updatable = false)
	private String requestFingerprint;

	@Enumerated(EnumType.STRING)
	@Column(name = "command_type", nullable = false, length = 32, updatable = false)
	private AssetClassificationCommandType commandType;

	@Column(name = "result_folder_id", updatable = false)
	private UUID resultFolderId;

	@Column(name = "result_display_name", length = 128, updatable = false)
	private String resultDisplayName;

	@Column(name = "result_clip_asset_id", updatable = false)
	private UUID resultClipAssetId;

	@Column(name = "result_resource_version", nullable = false, updatable = false)
	private long resultResourceVersion;

	@Column(name = "result_created_at", updatable = false)
	private Instant resultCreatedAt;

	@Column(name = "result_updated_at", nullable = false, updatable = false)
	private Instant resultUpdatedAt;

	@Column(name = "completed_at", nullable = false, updatable = false)
	private Instant completedAt;

	protected AssetClassificationCommand() {
	}

	private AssetClassificationCommand(UUID projectId, String performedBy, String idempotencyKey,
			String requestFingerprint, AssetClassificationCommandType commandType, UUID resultFolderId,
			String resultDisplayName, UUID resultClipAssetId, long resultResourceVersion, Instant resultCreatedAt,
			Instant resultUpdatedAt, Instant completedAt) {
		this.commandId = UUID.randomUUID();
		this.projectId = projectId;
		this.performedBy = performedBy;
		this.idempotencyKey = idempotencyKey;
		this.requestFingerprint = requestFingerprint;
		this.commandType = commandType;
		this.resultFolderId = resultFolderId;
		this.resultDisplayName = resultDisplayName;
		this.resultClipAssetId = resultClipAssetId;
		this.resultResourceVersion = resultResourceVersion;
		this.resultCreatedAt = resultCreatedAt;
		this.resultUpdatedAt = resultUpdatedAt;
		this.completedAt = completedAt;
	}

	static AssetClassificationCommand forFolder(AssetFolder folder, String performedBy, String idempotencyKey,
			String requestFingerprint, AssetClassificationCommandType commandType, Instant completedAt) {
		return new AssetClassificationCommand(folder.getProjectId(), performedBy, idempotencyKey, requestFingerprint,
				commandType, folder.getFolderId(), folder.getDisplayName(), null, folder.getRowVersion(),
				folder.getCreatedAt(), folder.getUpdatedAt(), completedAt);
	}

	static AssetClassificationCommand forClipAsset(ClipAsset asset, String performedBy, String idempotencyKey,
			String requestFingerprint, AssetClassificationCommandType commandType, Instant completedAt) {
		return new AssetClassificationCommand(asset.getProjectId(), performedBy, idempotencyKey, requestFingerprint,
				commandType, asset.getFolderId(), null, asset.getClipAssetId(), asset.getVersion(), null,
				asset.getUpdatedAt(), completedAt);
	}

	String getRequestFingerprint() {
		return this.requestFingerprint;
	}

	AssetClassificationCommandType getCommandType() {
		return this.commandType;
	}

	UUID getProjectId() {
		return this.projectId;
	}

	UUID getResultFolderId() {
		return this.resultFolderId;
	}

	String getResultDisplayName() {
		return this.resultDisplayName;
	}

	UUID getResultClipAssetId() {
		return this.resultClipAssetId;
	}

	long getResultResourceVersion() {
		return this.resultResourceVersion;
	}

	Instant getResultCreatedAt() {
		return this.resultCreatedAt;
	}

	Instant getResultUpdatedAt() {
		return this.resultUpdatedAt;
	}

}
