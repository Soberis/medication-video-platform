package com.medicationvideo.platform.annotationreview;

import com.medicationvideo.platform.qualitycleaning.CaptureGroupQualityQualifiedEvent;

/**
 * Retained as a compatibility marker while the quality-qualified event is being retired.
 * Explicit human-label assignment is the only task creation path.
 */
@Deprecated(forRemoval = false)
class CaptureGroupQualityQualifiedTaskListener {

	void assign(CaptureGroupQualityQualifiedEvent event) {
		// Deliberately not a Spring bean: quality admission must not create tasks.
	}

}
