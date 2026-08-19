package com.medicationvideo.platform.qualitycleaning;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CleaningRunRepository extends JpaRepository<CleaningRun, UUID> {

	Optional<CleaningRun> findByAnnotationRevisionIdAndCleaningRuleSetIdAndInputFingerprint(UUID annotationRevisionId,
			UUID cleaningRuleSetId, String inputFingerprint);

}
