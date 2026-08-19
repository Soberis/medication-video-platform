package com.medicationvideo.platform.assetcapture;

import java.util.Objects;

/** A source video frame and its PTS provenance. */
public record SourceFrame(long sourceFrameIndex, long rawPts, long sourcePtsNs, int sourcePtsOrdinal,
		String invalidReasonCode) {

	public SourceFrame {
		if (sourceFrameIndex < 0) {
			throw new IllegalArgumentException("sourceFrameIndex must not be negative.");
		}
		if (sourcePtsOrdinal < 0) {
			throw new IllegalArgumentException("sourcePtsOrdinal must not be negative.");
		}
		if (invalidReasonCode != null && !invalidReasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
			throw new IllegalArgumentException("invalidReasonCode must be a stable uppercase reason code.");
		}
	}

	public static SourceFrame valid(long sourceFrameIndex, long rawPts, long sourcePtsNs, int sourcePtsOrdinal) {
		return new SourceFrame(sourceFrameIndex, rawPts, sourcePtsNs, sourcePtsOrdinal, null);
	}

	public static SourceFrame invalid(long sourceFrameIndex, long rawPts, long sourcePtsNs, int sourcePtsOrdinal,
			String reasonCode) {
		return new SourceFrame(sourceFrameIndex, rawPts, sourcePtsNs, sourcePtsOrdinal,
				Objects.requireNonNull(reasonCode, "reasonCode"));
	}

	public boolean isValid() {
		return this.invalidReasonCode == null;
	}

}
