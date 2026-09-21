package com.medicationvideo.platform.annotationreview;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface AnnotationTaskViewRepository extends JpaRepository<AnnotationTaskView, UUID> {

	List<AnnotationTaskView> findAllByAnnotationTaskIdInOrderByCameraNumberAsc(Collection<UUID> taskIds);

	List<AnnotationTaskView> findAllByAnnotationTaskIdOrderByCameraNumberAsc(UUID taskId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select view from AnnotationTaskView view where view.projectId = :projectId "
			+ "and view.annotationTaskId = :taskId and view.cameraNumber = :cameraNumber")
	Optional<AnnotationTaskView> findForUpdate(@Param("projectId") UUID projectId, @Param("taskId") UUID taskId,
			@Param("cameraNumber") int cameraNumber);

}
