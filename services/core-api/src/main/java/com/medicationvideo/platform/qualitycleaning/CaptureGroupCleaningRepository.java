package com.medicationvideo.platform.qualitycleaning;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaptureGroupCleaningRepository extends JpaRepository<CaptureGroupCleaning, UUID> {

	List<CaptureGroupCleaning> findAllByAnnotationRevisionIdIn(Collection<UUID> annotationRevisionIds);

}
