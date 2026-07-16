package com.medicationvideo.platform.assetcapture;

record ApiErrorResponse(
		String errorCode,
		String errorMessage,
		boolean retryable,
		String requestId,
		String traceId) {
}
