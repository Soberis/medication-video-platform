package com.medicationvideo.platform.aicandidate;

import java.util.List;
import java.util.UUID;

/**
 * Immutable label context captured when an action-candidate job is queued.
 *
 * <p>
 * The snapshot keeps a job tied to the annotation task's label schema even when the
 * platform publishes a newer global schema later. The schema identity is optional only
 * for legacy jobs created before V1.34 closed this provenance gap.
 * </p>
 */
public record ActionLabelSnapshot(UUID annotationTaskId, UUID labelSchemaId, String schemaDigest,
		List<String> allowedLabels, UUID primaryClipAssetId) {

	public ActionLabelSnapshot(UUID annotationTaskId, UUID labelSchemaId, String schemaDigest,
			List<String> allowedLabels) {
		this(annotationTaskId, labelSchemaId, schemaDigest, allowedLabels, null);
	}

	public ActionLabelSnapshot {
		allowedLabels = allowedLabels == null ? List.of() : List.copyOf(allowedLabels);
	}

}
