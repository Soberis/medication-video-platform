package com.medicationvideo.platform.qualitycleaning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaptureGroupCleaningRepository extends JpaRepository<CaptureGroupCleaning, UUID> {

	Optional<CaptureGroupCleaning> findByAnnotationRevisionIdAndInputFingerprint(UUID annotationRevisionId,
			String inputFingerprint);

	List<CaptureGroupCleaning> findAllByAnnotationRevisionIdIn(Collection<UUID> annotationRevisionIds);

	Optional<CaptureGroupCleaning> findTopByProjectIdAndCaptureGroupIdOrderByEvaluatedAtDescCaptureGroupCleaningIdDesc(
			UUID projectId, UUID captureGroupId);

}
