package com.medicationvideo.platform.governance;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, UUID> {

	Optional<ApprovalRequest> findByProjectIdAndRequestTypeAndObjectId(
			UUID projectId, ApprovalRequestType requestType, UUID objectId);
}
