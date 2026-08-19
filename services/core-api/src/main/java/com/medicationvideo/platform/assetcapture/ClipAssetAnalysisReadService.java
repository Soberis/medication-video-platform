package com.medicationvideo.platform.assetcapture;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues a short-lived input grant for an approved background analysis without changing
 * media-ingest state.
 */
@Service
public class ClipAssetAnalysisReadService {

	private final ClipAssetRepository repository;

	private final MultipartObjectStorage objectStorage;

	ClipAssetAnalysisReadService(ClipAssetRepository repository, MultipartObjectStorage objectStorage) {
		this.repository = repository;
		this.objectStorage = objectStorage;
	}

	@Transactional(readOnly = true)
	public ClipAssetAnalysisReadGrant authorize(UUID projectId, UUID clipAssetId, String inputSha256, Duration lifetime,
			Instant authorizedAt) {
		var asset = this.repository.findByClipAssetIdAndProjectId(clipAssetId, projectId)
			.orElseThrow(() -> unavailable());
		if (asset.isDeleted() || asset.getStatus() != ClipAssetStatus.INGESTED
				|| !asset.getSha256().equals(inputSha256)) {
			throw unavailable();
		}
		try {
			return new ClipAssetAnalysisReadGrant(
					this.objectStorage.authorizeRead(asset.getObjectReference(), lifetime),
					authorizedAt.plus(lifetime));
		}
		catch (MultipartObjectStorageException exception) {
			throw new AssetCatalogQueryException("OBJECT_STORAGE_UNAVAILABLE",
					"Object storage could not authorize the analysis input.");
		}
	}

	private static AssetCatalogQueryException unavailable() {
		return new AssetCatalogQueryException("CLIP_ASSET_ANALYSIS_EVIDENCE_CHANGED",
				"The approved analysis input is no longer available.");
	}

	public record ClipAssetAnalysisReadGrant(URI inputUrl, Instant expiresAt) {
	}

}
