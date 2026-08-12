package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

/** One expected camera slot on an immutable Capture timeline position. */
public record FrameSlot(UUID captureId, UUID syncRevisionId, long syncFrameIndex, long captureTimestampNs,
		int cameraNumber, UUID cameraStreamId, UUID clipAssetId, Long sourceFrameIndex, Long sourcePtsNs,
		Integer sourcePtsOrdinal, FrameState frameState, String frameReasonCode, Long syncErrorNs) {

	public FrameSlot {
		if (captureId == null || syncRevisionId == null) {
			throw new IllegalArgumentException("Capture and sync revision identities are required.");
		}
		if (syncFrameIndex < 0 || captureTimestampNs < 0) {
			throw new IllegalArgumentException("Sync frame index and Capture timestamp must not be negative.");
		}
		if (cameraNumber < 1 || cameraNumber > 64) {
			throw new IllegalArgumentException("cameraNumber must be between 1 and 64.");
		}
		if (frameState == null || frameReasonCode != null && !frameReasonCode.matches("[A-Z][A-Z0-9_]{0,63}")) {
			throw new IllegalArgumentException("Frame state and stable reason code are required.");
		}
		switch (frameState) {
			case PRESENT -> {
				if (cameraStreamId == null || clipAssetId == null || sourceFrameIndex == null || sourcePtsNs == null
						|| sourcePtsOrdinal == null || frameReasonCode != null || syncErrorNs == null) {
					throw new IllegalArgumentException("PRESENT must trace its source frame and sync error.");
				}
			}
			case MISSING -> {
				if (frameReasonCode == null || sourceFrameIndex != null || sourcePtsNs != null
						|| sourcePtsOrdinal != null || syncErrorNs != null) {
					throw new IllegalArgumentException("MISSING must retain a reason and no selected source frame.");
				}
			}
			case INVALID -> {
				if (cameraStreamId == null || clipAssetId == null || frameReasonCode == null || syncErrorNs != null) {
					throw new IllegalArgumentException("INVALID must retain its source stream and reason.");
				}
			}
		}
	}

	static FrameSlot from(UUID captureId, UUID syncRevisionId, long syncFrameIndex, long captureTimestampNs,
			int cameraNumber, UUID cameraStreamId, UUID clipAssetId, FrameMappingResult result) {
		var frame = result.selectedFrame();
		return new FrameSlot(captureId, syncRevisionId, syncFrameIndex, captureTimestampNs, cameraNumber,
				cameraStreamId, clipAssetId, frame == null ? null : frame.sourceFrameIndex(),
				frame == null ? null : frame.sourcePtsNs(), frame == null ? null : frame.sourcePtsOrdinal(),
				result.frameState(), result.reasonCode(), result.syncErrorNs());
	}

	public UUID captureSessionId() {
		return this.captureId;
	}

	public UUID videoId() {
		return this.clipAssetId;
	}

}
