package com.medicationvideo.platform.mediaprivacy;

record ApiErrorResponse(String errorCode, String errorMessage, boolean retryable, String requestId, String traceId) {
}
