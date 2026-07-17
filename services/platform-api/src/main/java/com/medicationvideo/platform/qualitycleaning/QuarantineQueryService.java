package com.medicationvideo.platform.qualitycleaning;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.medicationvideo.platform.assetcapture.AssetCatalogQueryService;

@Service
class QuarantineQueryService {

	private final QuarantineRecordRepository repository;
	private final AssetCatalogQueryService assetCatalog;

	QuarantineQueryService(
			QuarantineRecordRepository repository,
			AssetCatalogQueryService assetCatalog) {
		this.repository = repository;
		this.assetCatalog = assetCatalog;
	}

	@Transactional(readOnly = true)
	QuarantineStatusResponse status(UUID projectId, UUID clipAssetId) {
		var asset = this.assetCatalog.getClipAssetInputSummary(projectId, clipAssetId);
		var records = this.repository
				.findAllByProjectIdAndClipAssetIdAndInputDigestAndStatusOrderByActivatedAtDesc(
						projectId,
						asset.clipAssetId(),
						asset.inputDigest(),
						QuarantineStatus.ACTIVE)
				.stream()
				.map(QuarantineRecordResponse::from)
				.toList();
		return new QuarantineStatusResponse(
				asset.clipAssetId(),
				asset.inputDigest(),
				records.isEmpty() ? "NONE" : "ACTIVE",
				records);
	}
}
