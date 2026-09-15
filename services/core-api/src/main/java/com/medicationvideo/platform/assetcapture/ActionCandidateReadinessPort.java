package com.medicationvideo.platform.assetcapture;

import java.util.Optional;
import java.util.UUID;

/** Read-only task and quality facts used by the action-candidate scheduler. */
public interface ActionCandidateReadinessPort {

	Optional<ActionCandidateReadiness> find(UUID projectId, UUID captureGroupId);

}

record ActionCandidateReadiness(UUID annotationTaskId, String taskStatus, boolean groupQualityAdmitting) {

	boolean eligibleForAutomaticActionCandidate() {
		return groupQualityAdmitting && ("ASSIGNED".equals(taskStatus) || "SUBMITTED".equals(taskStatus));
	}

}
