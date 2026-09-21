package com.medicationvideo.platform.aicandidate;

import java.util.UUID;

public record ActionCandidateSource(UUID candidateId, UUID clipAssetId, UUID labelSchemaId, String labelSchemaDigest,
		long startMs, long endMs, String label) {
}
