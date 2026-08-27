package com.medicationvideo.platform.qualitycleaning;

public enum CaptureGroupQualityResult {

	QUALIFIED, QUALIFIED_WITH_EXCLUSIONS, NEEDS_REVIEW, REJECTED

	;

	public boolean isAdmitting() {
		return this == QUALIFIED || this == QUALIFIED_WITH_EXCLUSIONS;
	}

}
