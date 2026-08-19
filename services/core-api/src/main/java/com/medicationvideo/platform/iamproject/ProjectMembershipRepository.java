package com.medicationvideo.platform.iamproject;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ProjectMembershipRepository extends JpaRepository<ProjectMembership, UUID> {

	Optional<ProjectMembership> findByProjectIdAndActorId(UUID projectId, String actorId);

	List<ProjectMembership> findAllByActorIdAndStatusOrderByCreatedAtAscMembershipIdAsc(String actorId,
			MembershipStatus status);

	List<ProjectMembership> findAllByProjectIdAndStatusOrderByCreatedAtAscMembershipIdAsc(UUID projectId,
			MembershipStatus status);

	long countByProjectIdAndStatusAndRole(UUID projectId, MembershipStatus status, ProjectRole role);

}
