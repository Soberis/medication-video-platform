package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.medicationvideo.platform.iamproject.ProjectAccessException;
import com.medicationvideo.platform.iamproject.ProjectAccessService;
import com.medicationvideo.platform.iamproject.ProjectRole;

@Service
class AssetCaptureProjectAuthorizer {

	private final ProjectAccessService projectAccessService;

	private final UploadBatchRepository batchRepository;

	AssetCaptureProjectAuthorizer(ProjectAccessService projectAccessService, UploadBatchRepository batchRepository) {
		this.projectAccessService = projectAccessService;
		this.batchRepository = batchRepository;
	}

	ProjectRole requireMember(UUID projectId, String actorId) {
		return this.projectAccessService.requireMember(projectId, actorId);
	}

	void requireAdministrator(UUID projectId, String actorId) {
		if (requireMember(projectId, actorId) != ProjectRole.ADMIN) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED", "Project administrator membership is required.",
					403);
		}
	}

	BatchAccess requireBatchMember(UUID batchId, String actorId) {
		var batch = this.batchRepository.findById(batchId).orElseThrow(UploadBatchNotFoundException::new);
		final ProjectRole role;
		try {
			role = requireMember(batch.getProjectId(), actorId);
		}
		catch (ProjectAccessException ignored) {
			throw new UploadBatchNotFoundException();
		}
		return new BatchAccess(batch.getProjectId(), role, batch.getUploadSourceType());
	}

	BatchAccess requireUploadOperator(UUID batchId, String actorId) {
		var access = requireBatchMember(batchId, actorId);
		if (access.uploadSourceType() == UploadSourceType.PLATFORM_LOCAL && access.role() != ProjectRole.ADMIN) {
			throw new ProjectAccessException("PROJECT_ACCESS_DENIED",
					"Project administrator membership is required for platform-local uploads.", 403);
		}
		return access;
	}

	record BatchAccess(UUID projectId, ProjectRole role, UploadSourceType uploadSourceType) {
	}

}
