package com.medicationvideo.platform.aicandidate;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only query boundary for verifying action-candidate provenance in manual
 * annotations.
 */
public interface ActionCandidateSourceQueryPort {

	Map<UUID, ActionCandidateSource> findEligible(UUID projectId, UUID captureGroupId, UUID annotationTaskId,
			UUID clipAssetId, UUID labelSchemaId, String labelSchemaDigest, Set<UUID> candidateIds);

}
