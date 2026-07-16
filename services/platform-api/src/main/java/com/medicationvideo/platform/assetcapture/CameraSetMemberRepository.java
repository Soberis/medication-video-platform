package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CameraSetMemberRepository extends JpaRepository<CameraSetMember, UUID> {

	List<CameraSetMember> findAllByCameraSetIdOrderByCameraIndexAsc(UUID cameraSetId);
}
