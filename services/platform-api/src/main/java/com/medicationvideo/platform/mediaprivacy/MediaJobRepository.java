package com.medicationvideo.platform.mediaprivacy;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface MediaJobRepository extends JpaRepository<MediaJob, UUID> {

	@Query("""
			SELECT job FROM MediaJob job
			WHERE job.projectId = :projectId
			  AND job.idempotencyKey = :idempotencyKey
			""")
	Optional<MediaJob> findByProjectIdAndIdempotencyKey(
			@Param("projectId") UUID projectId,
			@Param("idempotencyKey") String idempotencyKey);

	@Query("""
			SELECT job FROM MediaJob job
			WHERE job.projectId = :projectId
			  AND job.jobType = :jobType
			  AND job.clipAssetId = :clipAssetId
			  AND job.inputDigest = :inputDigest
			  AND job.recipeDigest = :recipeDigest
			""")
	Optional<MediaJob> findByProjectIdAndJobTypeAndClipAssetIdAndInputDigestAndRecipeDigest(
			@Param("projectId") UUID projectId,
			@Param("jobType") MediaJobType jobType,
			@Param("clipAssetId") UUID clipAssetId,
			@Param("inputDigest") String inputDigest,
			@Param("recipeDigest") String recipeDigest);
}
