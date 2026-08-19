package com.medicationvideo.platform.iamproject;

record ProjectApiErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId,
		String traceId) {
}
