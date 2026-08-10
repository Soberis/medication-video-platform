package com.medicationvideo.platform.aicandidate;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AiCandidateJobAttemptRepository extends JpaRepository<AiCandidateJobAttempt, UUID> {

}
