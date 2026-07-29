package com.medicationvideo.platform.governance;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, UUID> {

	Optional<ApprovalDecision> findByApprovalRequestId(UUID approvalRequestId);
}
