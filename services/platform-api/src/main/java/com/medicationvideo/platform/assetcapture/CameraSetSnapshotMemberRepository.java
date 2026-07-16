package com.medicationvideo.platform.assetcapture;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface CameraSetSnapshotMemberRepository extends JpaRepository<CameraSetSnapshotMember, UUID> {

	List<CameraSetSnapshotMember> findAllByCameraSetSnapshotIdOrderByCameraIndexAsc(UUID cameraSetSnapshotId);
}
