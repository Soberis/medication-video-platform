package com.medicationvideo.platform.qualitycleaning;

import java.time.Instant;
import java.util.UUID;

record QuarantineRecordResponse(
		UUID quarantineRecordId,
		QuarantineReasonCode reasonCode,
		QuarantineSourceType sourceType,
		UUID sourceId,
		int sourceVersion,
		String activatedBy,
		Instant activatedAt) {

	static QuarantineRecordResponse from(QuarantineRecord record) {
		return new QuarantineRecordResponse(
				record.getQuarantineRecordId(),
				record.getReasonCode(),
				record.getSourceType(),
				record.getSourceId(),
				record.getSourceVersion(),
				record.getActivatedBy(),
				record.getActivatedAt());
	}
}
