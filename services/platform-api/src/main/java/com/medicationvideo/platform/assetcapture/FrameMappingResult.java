package com.medicationvideo.platform.assetcapture;

/** Result of mapping one Capture target to one source stream. */
public record FrameMappingResult(FrameState frameState, SourceFrame selectedFrame, String reasonCode,
		Long syncErrorNs) {

	public FrameMappingResult {
		if (frameState == null) {
			throw new IllegalArgumentException("frameState is required.");
		}
		if (frameState == FrameState.PRESENT && (selectedFrame == null || syncErrorNs == null || reasonCode != null)) {
			throw new IllegalArgumentException("PRESENT requires a selected frame and signed sync error.");
		}
		if (frameState == FrameState.MISSING && (reasonCode == null || syncErrorNs != null)) {
			throw new IllegalArgumentException("MISSING requires a reason and no sync error.");
		}
		if (frameState == FrameState.INVALID && (selectedFrame == null || reasonCode == null || syncErrorNs != null)) {
			throw new IllegalArgumentException("INVALID requires the selected source frame and reason.");
		}
	}

	public static FrameMappingResult missing(String reasonCode) {
		return new FrameMappingResult(FrameState.MISSING, null, reasonCode, null);
	}

	public static FrameMappingResult present(SourceFrame frame, long syncErrorNs) {
		return new FrameMappingResult(FrameState.PRESENT, frame, null, syncErrorNs);
	}

	public static FrameMappingResult invalid(SourceFrame frame, String reasonCode) {
		return new FrameMappingResult(FrameState.INVALID, frame, reasonCode, null);
	}

}
