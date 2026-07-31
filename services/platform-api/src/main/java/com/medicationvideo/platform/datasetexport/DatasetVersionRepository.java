package com.medicationvideo.platform.datasetexport;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DatasetVersionRepository extends JpaRepository<DatasetVersion, UUID> {

	Optional<DatasetVersion> findByProjectIdAndCreatedByAndIdempotencyKey(UUID projectId, String createdBy,
			String idempotencyKey);

	Optional<DatasetVersion> findByProjectIdAndBuildStartedByAndBuildStartIdempotencyKey(UUID projectId,
			String buildStartedBy, String buildStartIdempotencyKey);

	Optional<DatasetVersion> findByProjectIdAndFrozenByAndFreezeIdempotencyKey(UUID projectId, String frozenBy,
			String freezeIdempotencyKey);

	Optional<DatasetVersion> findByProjectIdAndDatasetVersionId(UUID projectId, UUID datasetVersionId);

	@Query(value = """
			SELECT *
			FROM dataset_version
			WHERE project_id = :projectId
			ORDER BY created_at DESC, dataset_version_id DESC
			LIMIT :limit
			""", nativeQuery = true)
	List<DatasetVersion> findFirstPage(@Param("projectId") UUID projectId, @Param("limit") int limit);

	@Query(value = """
			SELECT *
			FROM dataset_version
			WHERE project_id = :projectId
			  AND (created_at < :createdAt
			       OR (created_at = :createdAt AND dataset_version_id < :datasetVersionId))
			ORDER BY created_at DESC, dataset_version_id DESC
			LIMIT :limit
			""", nativeQuery = true)
	List<DatasetVersion> findPageAfter(@Param("projectId") UUID projectId, @Param("createdAt") Instant createdAt,
			@Param("datasetVersionId") UUID datasetVersionId, @Param("limit") int limit);

}
