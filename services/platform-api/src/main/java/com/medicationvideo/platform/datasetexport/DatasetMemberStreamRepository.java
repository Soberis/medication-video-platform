package com.medicationvideo.platform.datasetexport;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface DatasetMemberStreamRepository extends JpaRepository<DatasetMemberStream, UUID> {

	List<DatasetMemberStream> findByDatasetVersionIdOrderByDatasetMemberSessionIdAscCameraIndexAsc(
			UUID datasetVersionId);

}
