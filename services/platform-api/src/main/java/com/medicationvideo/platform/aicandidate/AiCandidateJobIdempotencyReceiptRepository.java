package com.medicationvideo.platform.aicandidate;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AiCandidateJobIdempotencyReceiptRepository extends JpaRepository<AiCandidateJobIdempotencyReceipt, UUID> {

	Optional<AiCandidateJobIdempotencyReceipt> findByProjectIdAndIdempotencyKey(UUID projectId, String idempotencyKey);

}
