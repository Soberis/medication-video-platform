package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClipAssetReviewQueryService {

	private final ClipAssetRepository repository;

	ClipAssetReviewQueryService(ClipAssetRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public ReviewFacts getReviewFacts(UUID projectId, UUID clipAssetId) {
		var asset = this.repository.findByClipAssetIdAndProjectId(clipAssetId, projectId)
				.filter(candidate -> !candidate.isDeleted())
				.orElseThrow(() -> new AssetCatalogQueryException(
						"CLIP_ASSET_NOT_FOUND",
						"The clip asset was not found in the current project."));
		return new ReviewFacts(asset.getStatus() == ClipAssetStatus.INGESTED, asset.getStatus().name());
	}

	public record ReviewFacts(boolean reviewable, String assetStatus) {
	}
}
