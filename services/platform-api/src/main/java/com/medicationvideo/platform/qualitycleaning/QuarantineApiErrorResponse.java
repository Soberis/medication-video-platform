package com.medicationvideo.platform.qualitycleaning;

record QuarantineApiErrorResponse(
		String errorCode,
		String errorMessage,
		boolean retryable,
		String requestId,
		String traceId) {
}
