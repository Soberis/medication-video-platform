package com.medicationvideo.platform.assetcapture;

import java.util.UUID;

interface UploadPartProgressSummary {

	UUID getUploadFileId();

	long getTrackedParts();

	long getUploadedParts();

	long getUploadedSizeBytes();

}
