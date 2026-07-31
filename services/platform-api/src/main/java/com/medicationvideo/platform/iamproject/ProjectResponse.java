package com.medicationvideo.platform.iamproject;

import java.time.Instant;
import java.util.UUID;

record ProjectResponse(UUID projectId, String displayName, ProjectStatus status, ProjectRole currentRole,
		Instant createdAt) {

	static ProjectResponse from(DataProject project, ProjectRole currentRole) {
		return new ProjectResponse(project.getProjectId(), project.getDisplayName(), project.getStatus(), currentRole,
				project.getCreatedAt());
	}
}
