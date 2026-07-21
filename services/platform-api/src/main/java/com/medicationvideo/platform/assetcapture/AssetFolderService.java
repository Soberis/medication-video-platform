package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.governance.AuditActorType;
import com.medicationvideo.platform.governance.AuditEventCommand;
import com.medicationvideo.platform.governance.AuditEventRecorder;
import com.medicationvideo.platform.governance.AuditOutcome;
import com.medicationvideo.platform.iamproject.ProjectAccessService;

@Service
class AssetFolderService {

	private final ProjectAccessService projectAccessService;
	private final AssetFolderRepository folderRepository;
	private final AssetClassificationCommandRepository commandRepository;
	private final ClipAssetRepository clipAssetRepository;
	private final AssetFolderWriter folderWriter;
	private final AuditEventRecorder auditRecorder;

	AssetFolderService(
			ProjectAccessService projectAccessService,
			AssetFolderRepository folderRepository,
			AssetClassificationCommandRepository commandRepository,
			ClipAssetRepository clipAssetRepository,
			AssetFolderWriter folderWriter,
			AuditEventRecorder auditRecorder) {
		this.projectAccessService = projectAccessService;
		this.folderRepository = folderRepository;
		this.commandRepository = commandRepository;
		this.clipAssetRepository = clipAssetRepository;
		this.folderWriter = folderWriter;
		this.auditRecorder = auditRecorder;
	}

	@Transactional(readOnly = true)
	AssetFolderListResponse list(UUID projectId, String actorId) {
		this.projectAccessService.requireMember(projectId, actorId);
		return new AssetFolderListResponse(this.folderRepository
				.findAllByProjectIdOrderByDisplayNameAscFolderIdAsc(projectId)
				.stream()
				.map(AssetFolderResponse::from)
				.toList());
	}

	@Transactional
	AssetFolderResponse create(
			UUID projectId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			AssetFolderRequest request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var displayName = request.displayName().trim();
		var commandType = AssetClassificationCommandType.CREATE_FOLDER;
		var requestFingerprint = fingerprint(
				commandType.name(), projectId.toString(), displayName);
		var replay = findReplay(
				projectId, actorId, idempotencyKey, requestFingerprint, commandType);
		if (replay != null) {
			return AssetFolderResponse.from(replay);
		}
		requireAvailableName(projectId, displayName, null);

		var now = now();
		var folder = this.folderRepository.saveAndFlush(
				AssetFolder.create(projectId, displayName, actorId, now));
		recordFolderAudit(
				folder,
				folder.getUpdatedAt(),
				actorId,
				"ASSET_FOLDER_CREATED",
				"FOLDER_CREATED",
				requestId,
				traceId,
				Map.of("display_name", folder.getDisplayName()));
		var command = this.commandRepository.saveAndFlush(
				AssetClassificationCommand.forFolder(
						folder,
						actorId,
						idempotencyKey,
						requestFingerprint,
						commandType,
						now));
		return AssetFolderResponse.from(command);
	}

	@Transactional
	AssetFolderResponse rename(
			UUID projectId,
			UUID folderId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			AssetFolderRequest request) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var displayName = request.displayName().trim();
		var commandType = AssetClassificationCommandType.RENAME_FOLDER;
		var requestFingerprint = fingerprint(
				commandType.name(), projectId.toString(), folderId.toString(), displayName);
		var replay = findReplay(
				projectId, actorId, idempotencyKey, requestFingerprint, commandType);
		if (replay != null) {
			return AssetFolderResponse.from(replay);
		}

		var folder = requireFolder(projectId, folderId);
		var oldDisplayName = folder.getDisplayName();
		if (!oldDisplayName.equals(displayName)) {
			requireAvailableName(projectId, displayName, folderId);
			var now = now();
			folder.rename(displayName, actorId, now);
			folder = this.folderRepository.saveAndFlush(folder);
			recordFolderAudit(
					folder,
					folder.getUpdatedAt(),
					actorId,
					"ASSET_FOLDER_RENAMED",
					"FOLDER_RENAMED",
					requestId,
					traceId,
					Map.of(
							"old_display_name", oldDisplayName,
							"new_display_name", displayName));
		}
		var completedAt = now();
		var command = this.commandRepository.saveAndFlush(
				AssetClassificationCommand.forFolder(
						folder,
						actorId,
						idempotencyKey,
						requestFingerprint,
						commandType,
						completedAt));
		return AssetFolderResponse.from(command);
	}

	@Transactional
	void delete(
			UUID projectId,
			UUID folderId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var commandType = AssetClassificationCommandType.DELETE_FOLDER;
		var requestFingerprint = fingerprint(
				commandType.name(), projectId.toString(), folderId.toString());
		if (findReplay(
				projectId, actorId, idempotencyKey, requestFingerprint, commandType) != null) {
			return;
		}

		var folder = requireFolder(projectId, folderId);
		var now = now();
		var unclassifiedAssetCount = this.folderWriter.unclassifyAssets(
				projectId, folderId, now);
		this.folderRepository.delete(folder);
		this.folderRepository.flush();
		recordFolderAudit(
				folder,
				now,
				actorId,
				"ASSET_FOLDER_DELETED",
				"FOLDER_DELETED",
				requestId,
				traceId,
				Map.of(
						"display_name", folder.getDisplayName(),
						"unclassified_asset_count", Integer.toString(unclassifiedAssetCount)));
		this.commandRepository.saveAndFlush(
				AssetClassificationCommand.forFolder(
						folder,
						actorId,
						idempotencyKey,
						requestFingerprint,
						commandType,
						now));
	}

	@Transactional
	ClipAssetFolderResponse assign(
			UUID projectId,
			UUID clipAssetId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			ClipAssetFolderRequest request) {
		return changeClassification(
				projectId,
				clipAssetId,
				request.folderId(),
				actorId,
				idempotencyKey,
				requestId,
				traceId,
				AssetClassificationCommandType.ASSIGN_CLIP_ASSET);
	}

	@Transactional
	ClipAssetFolderResponse unclassify(
			UUID projectId,
			UUID clipAssetId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId) {
		return changeClassification(
				projectId,
				clipAssetId,
				null,
				actorId,
				idempotencyKey,
				requestId,
				traceId,
				AssetClassificationCommandType.UNCLASSIFY_CLIP_ASSET);
	}

	private ClipAssetFolderResponse changeClassification(
			UUID projectId,
			UUID clipAssetId,
			UUID folderId,
			String actorId,
			String idempotencyKey,
			String requestId,
			String traceId,
			AssetClassificationCommandType commandType) {
		this.projectAccessService.lockAndRequireAdministrator(projectId, actorId);
		var folderFingerprint = folderId == null ? "UNCLASSIFIED" : folderId.toString();
		var requestFingerprint = fingerprint(
				commandType.name(), projectId.toString(), clipAssetId.toString(), folderFingerprint);
		var replay = findReplay(
				projectId, actorId, idempotencyKey, requestFingerprint, commandType);
		if (replay != null) {
			return ClipAssetFolderResponse.from(replay);
		}

		if (folderId != null) {
			requireFolder(projectId, folderId);
		}
		var asset = this.clipAssetRepository.findForUpdate(clipAssetId, projectId)
				.orElseThrow(() -> new AssetFolderException(
						"CLIP_ASSET_NOT_FOUND",
						"The clip asset was not found in the project.",
						404));
		if (asset.isDeleted()) {
			throw new AssetFolderException(
					"CLIP_ASSET_NOT_FOUND",
					"The clip asset was not found in the project.",
					404);
		}
		var oldFolderId = asset.getFolderId();
		var now = now();
		if (!java.util.Objects.equals(oldFolderId, folderId)) {
			asset.classify(folderId, now);
			asset = this.clipAssetRepository.saveAndFlush(asset);
			recordAssetClassificationAudit(
					asset,
					actorId,
					requestId,
					traceId,
					oldFolderId,
					folderId);
		}
		var command = this.commandRepository.saveAndFlush(
				AssetClassificationCommand.forClipAsset(
						asset,
						actorId,
						idempotencyKey,
						requestFingerprint,
						commandType,
						now));
		return ClipAssetFolderResponse.from(command);
	}

	private AssetClassificationCommand findReplay(
			UUID projectId,
			String actorId,
			String idempotencyKey,
			String requestFingerprint,
			AssetClassificationCommandType commandType) {
		return this.commandRepository
				.findByProjectIdAndPerformedByAndIdempotencyKey(
						projectId, actorId, idempotencyKey)
				.map(command -> {
					requireSameRequest(command, requestFingerprint, commandType);
					return command;
				})
				.orElse(null);
	}

	private AssetFolder requireFolder(UUID projectId, UUID folderId) {
		return this.folderRepository.findByFolderIdAndProjectId(folderId, projectId)
				.orElseThrow(() -> new AssetFolderException(
						"ASSET_FOLDER_NOT_FOUND",
						"The asset folder was not found in the project.",
						404));
	}

	private void requireAvailableName(UUID projectId, String displayName, UUID currentFolderId) {
		var exists = currentFolderId == null
				? this.folderRepository.existsByProjectIdAndDisplayNameIgnoreCase(
						projectId, displayName)
				: this.folderRepository
						.existsByProjectIdAndDisplayNameIgnoreCaseAndFolderIdNot(
								projectId, displayName, currentFolderId);
		if (exists) {
			throw new AssetFolderException(
					"ASSET_FOLDER_NAME_CONFLICT",
					"An asset folder with the same name already exists in the project.",
					409);
		}
	}

	private void recordFolderAudit(
			AssetFolder folder,
			Instant occurredAt,
			String actorId,
			String action,
			String reasonCode,
			String requestId,
			String traceId,
			Map<String, String> details) {
		this.auditRecorder.record(new AuditEventCommand(
				occurredAt,
				AuditActorType.USER,
				actorId,
				folder.getProjectId(),
				action,
				"AssetFolder",
				folder.getFolderId().toString(),
				Long.toString(folder.getRowVersion()),
				AuditOutcome.SUCCEEDED,
				reasonCode,
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				details));
	}

	private void recordAssetClassificationAudit(
			ClipAsset asset,
			String actorId,
			String requestId,
			String traceId,
			UUID oldFolderId,
			UUID newFolderId) {
		this.auditRecorder.record(new AuditEventCommand(
				asset.getUpdatedAt(),
				AuditActorType.USER,
				actorId,
				asset.getProjectId(),
				"CLIP_ASSET_FOLDER_CHANGED",
				"ClipAsset",
				asset.getClipAssetId().toString(),
				Long.toString(asset.getVersion()),
				AuditOutcome.SUCCEEDED,
				"ASSET_CLASSIFICATION_CHANGED",
				requestId,
				traceId,
				"PROJECT_MEMBERSHIP_ADMIN",
				Map.of(
						"old_folder_id", displayFolderId(oldFolderId),
						"new_folder_id", displayFolderId(newFolderId))));
	}

	private static void requireSameRequest(
			AssetClassificationCommand command,
			String requestFingerprint,
			AssetClassificationCommandType commandType) {
		if (command.getCommandType() != commandType
				|| !MessageDigest.isEqual(
						command.getRequestFingerprint().getBytes(StandardCharsets.US_ASCII),
						requestFingerprint.getBytes(StandardCharsets.US_ASCII))) {
			throw new AssetFolderException(
					"ASSET_CLASSIFICATION_IDEMPOTENCY_KEY_REUSED",
					"The idempotency key was already used for a different request.",
					409);
		}
	}

	private static String fingerprint(String... values) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(String.join("\n", values).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private static String displayFolderId(UUID folderId) {
		return folderId == null ? "UNCLASSIFIED" : folderId.toString();
	}

	private static Instant now() {
		return Instant.now().truncatedTo(ChronoUnit.MICROS);
	}
}
