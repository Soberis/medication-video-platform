package com.medicationvideo.platform.datasetexport;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetMemberSessionRepository extends JpaRepository<DatasetMemberSession, UUID> {

	List<DatasetMemberSession> findByDatasetVersionIdOrderByCandidateIndexAsc(UUID datasetVersionId);
}
