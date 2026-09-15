package com.medicationvideo.platform.aicandidate;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port for resolving the active annotation label identifiers for action
 * pre-annotation. Implemented by downstream domain modules (e.g.
 * {@code annotationreview}) to avoid circular dependencies.
 */
public interface ActionAllowedLabelsPort {

	List<String> resolveAllowedLabels();

	/**
	 * Resolves the immutable label context attached to the CaptureGroup's annotation
	 * task. New scoped jobs require a task-bound snapshot; absent context must not be
	 * replaced with a newer global schema. The default supports legacy unscoped callers.
	 */
	default ActionLabelSnapshot resolveLabelSnapshot(UUID projectId, UUID captureGroupId) {
		return new ActionLabelSnapshot(null, null, null, this.resolveAllowedLabels());
	}

}
