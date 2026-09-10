package com.medicationvideo.platform.aicandidate;

import java.util.List;

/**
 * Outbound port for resolving the active annotation label identifiers for action
 * pre-annotation. Implemented by downstream domain modules (e.g.
 * {@code annotationreview}) to avoid circular dependencies.
 */
public interface ActionAllowedLabelsPort {

	List<String> resolveAllowedLabels();

}
