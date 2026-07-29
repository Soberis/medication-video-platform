package com.medicationvideo.platform.datasetexport;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetSplitAssignmentRepository extends JpaRepository<DatasetSplitAssignment, UUID> {

	List<DatasetSplitAssignment> findByDatasetSplitPlanIdOrderByCandidateIndexAsc(UUID datasetSplitPlanId);
}
