package com.medicationvideo.platform.assetcapture;

/** A trusted or untrusted observation tying source PTS to Capture time. */
public record SyncAnchor(long sourcePtsNs, long captureTimestampNs, boolean trusted) {

	public SyncAnchor(long sourcePtsNs, long captureTimestampNs) {
		this(sourcePtsNs, captureTimestampNs, true);
	}

}
