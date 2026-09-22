package com.medicationvideo.platform.iamproject;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface DataProjectRepository extends JpaRepository<DataProject, UUID> {

	Optional<DataProject> findByCreatedByAndIdempotencyKey(String createdBy, String idempotencyKey);

	Optional<DataProject> findByProjectIdAndStatus(UUID projectId, ProjectStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select project from DataProject project where project.projectId = :projectId")
	Optional<DataProject> findForUpdate(@Param("projectId") UUID projectId);

	@Query("""
			select project from DataProject project
			where project.projectId in :projectIds and project.status = :status
			order by project.createdAt, project.projectId
			""")
	List<DataProject> findVisibleProjects(@Param("projectIds") Collection<UUID> projectIds,
			@Param("status") ProjectStatus status);

	List<DataProject> findAllByStatusOrderByCreatedAtAscProjectIdAsc(ProjectStatus status);

}
