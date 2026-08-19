package com.medicationvideo.platform.qualitycleaning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CleaningResultRepository extends JpaRepository<CleaningResult, UUID> {

	Optional<CleaningResult> findByCleaningRunId(UUID cleaningRunId);

	List<CleaningResult> findAllByAnnotationRevisionIdIn(Collection<UUID> annotationRevisionIds);

}
