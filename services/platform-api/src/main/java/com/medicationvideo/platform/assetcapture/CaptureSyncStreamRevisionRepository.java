package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CaptureSyncStreamRevisionRepository extends JpaRepository<CaptureSyncStreamRevision, UUID> {

	List<CaptureSyncStreamRevision> findAllByCaptureSyncRevisionIdOrderByCameraNumberAsc(UUID revisionId);

}
