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
			.orElseThrow(ClipAssetReviewQueryService::assetNotFound);
		return toReviewFacts(asset);
	}

	@Transactional
	public ReviewFacts lockAndGetReviewFacts(UUID projectId, UUID clipAssetId) {
		var asset = this.repository.findForUpdate(clipAssetId, projectId)
			.orElseThrow(ClipAssetReviewQueryService::assetNotFound);
		return toReviewFacts(asset);
	}

	private static ReviewFacts toReviewFacts(ClipAsset asset) {
		if (asset.isDeleted()) {
			throw assetNotFound();
		}
		return new ReviewFacts(asset.getStatus() == ClipAssetStatus.INGESTED, asset.getStatus().name(),
				asset.getSha256());
	}

	private static AssetCatalogQueryException assetNotFound() {
		return new AssetCatalogQueryException("CLIP_ASSET_NOT_FOUND",
				"The clip asset was not found in the current project.");
	}

	public record ReviewFacts(boolean reviewable, String assetStatus, String assetSha256) {
	}

}
