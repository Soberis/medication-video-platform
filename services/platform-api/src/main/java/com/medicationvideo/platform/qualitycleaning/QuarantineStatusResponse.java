package com.medicationvideo.platform.qualitycleaning;

import java.util.List;
import java.util.UUID;

record QuarantineStatusResponse(
		UUID clipAssetId,
		String inputDigest,
		String quarantineStatus,
		List<QuarantineRecordResponse> activeRecords) {

	QuarantineStatusResponse {
		activeRecords = List.copyOf(activeRecords);
	}
}
