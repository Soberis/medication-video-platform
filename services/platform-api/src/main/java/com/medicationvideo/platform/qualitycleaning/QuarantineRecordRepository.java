package com.medicationvideo.platform.qualitycleaning;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface QuarantineRecordRepository extends JpaRepository<QuarantineRecord, UUID> {

	Optional<QuarantineRecord> findByProjectIdAndSourceTypeAndSourceId(
			UUID projectId, QuarantineSourceType sourceType, UUID sourceId);

	List<QuarantineRecord>
			findAllByProjectIdAndClipAssetIdAndInputDigestAndStatusOrderByActivatedAtDesc(
					UUID projectId,
					UUID clipAssetId,
					String inputDigest,
					QuarantineStatus status);
}
