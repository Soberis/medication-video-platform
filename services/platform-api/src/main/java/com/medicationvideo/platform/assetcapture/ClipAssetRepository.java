package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ClipAssetRepository extends JpaRepository<ClipAsset, UUID> {

	List<ClipAsset> findAllByBatchIdOrderByClientFileIdAsc(UUID batchId);

	Optional<ClipAsset> findByClipAssetIdAndProjectId(UUID clipAssetId, UUID projectId);
}
