package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface ClipAssetRepository extends JpaRepository<ClipAsset, UUID> {

	@Query("""
			SELECT asset FROM ClipAsset asset
			WHERE asset.status = com.medicationvideo.platform.assetcapture.ClipAssetStatus.INGESTED
			  AND asset.deletedAt IS NULL AND asset.contentType LIKE 'video/%'
			  AND EXISTS (SELECT stream.cameraStreamId FROM CameraStream stream
			              WHERE stream.clipAssetId = asset.clipAssetId)
			ORDER BY asset.createdAt, asset.clipAssetId
			""")
	List<ClipAsset> findActionProcessingInputs(org.springframework.data.domain.Pageable pageable);

	List<ClipAsset> findAllByBatchIdOrderByClientFileIdAsc(UUID batchId);

	Optional<ClipAsset> findByClipAssetIdAndProjectId(UUID clipAssetId, UUID projectId);

	List<ClipAsset> findAllByClipAssetIdInAndProjectId(List<UUID> clipAssetIds, UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			SELECT asset FROM ClipAsset asset
			WHERE asset.clipAssetId = :clipAssetId AND asset.projectId = :projectId
			""")
	Optional<ClipAsset> findForUpdate(@Param("clipAssetId") UUID clipAssetId, @Param("projectId") UUID projectId);

}
