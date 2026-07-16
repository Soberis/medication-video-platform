package com.medicationvideo.platform.assetcapture;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UploadPartRepository extends JpaRepository<UploadPart, UUID> {

	Optional<UploadPart> findByUploadFileIdAndPartNumber(UUID uploadFileId, int partNumber);

	List<UploadPart> findAllByUploadFileIdOrderByPartNumber(UUID uploadFileId);

	@Query("""
			select part.uploadFileId as uploadFileId,
				count(part) as trackedParts,
				coalesce(sum(case when part.status = com.medicationvideo.platform.assetcapture.UploadPartStatus.UPLOADED
					then 1 else 0 end), 0) as uploadedParts,
				coalesce(sum(case when part.status = com.medicationvideo.platform.assetcapture.UploadPartStatus.UPLOADED
					then part.uploadedSizeBytes else 0 end), 0) as uploadedSizeBytes
			from UploadPart part
			where part.uploadFileId in :uploadFileIds
			group by part.uploadFileId
			""")
	List<UploadPartProgressSummary> summarizeByUploadFileIds(
			@Param("uploadFileIds") Collection<UUID> uploadFileIds);
}
