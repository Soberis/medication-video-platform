package com.medicationvideo.platform.assetcapture;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClipAssetMediaIngestService {

	private final ClipAssetRepository assetRepository;
	private final CameraStreamRepository streamRepository;
	private final MultipartObjectStorage objectStorage;

	public ClipAssetMediaIngestService(
			ClipAssetRepository assetRepository,
			CameraStreamRepository streamRepository,
			MultipartObjectStorage objectStorage) {
		this.assetRepository = assetRepository;
		this.streamRepository = streamRepository;
		this.objectStorage = objectStorage;
	}

	@Transactional
	public ClipAssetReadGrant begin(
			UUID projectId,
			UUID clipAssetId,
			Duration authorizationLifetime,
			Instant changedAt) {
		var asset = lock(projectId, clipAssetId);
		asset.beginMediaIngest(changedAt);
		try {
			var inputUrl = this.objectStorage.authorizeRead(
					asset.getObjectReference(), authorizationLifetime);
			return new ClipAssetReadGrant(inputUrl, changedAt.plus(authorizationLifetime));
		}
		catch (MultipartObjectStorageException exception) {
			throw new ClipAssetMediaIngestException(
					"OBJECT_STORAGE_UNAVAILABLE",
					"Object storage could not authorize the media input.",
					true,
					exception);
		}
	}

	@Transactional
	public void complete(
			UUID projectId,
			UUID clipAssetId,
			boolean succeeded,
			Instant changedAt) {
		var asset = lock(projectId, clipAssetId);
		asset.completeMediaIngest(succeeded, changedAt);
		this.streamRepository.findByClipAssetId(clipAssetId)
				.ifPresent(stream -> stream.completeMediaIngest(succeeded, changedAt));
	}

	private ClipAsset lock(UUID projectId, UUID clipAssetId) {
		return this.assetRepository.findForUpdate(clipAssetId, projectId)
				.orElseThrow(() -> new ClipAssetMediaIngestException(
						"CLIP_ASSET_NOT_FOUND",
						"The clip asset was not found in the current project.",
						false));
	}
}
