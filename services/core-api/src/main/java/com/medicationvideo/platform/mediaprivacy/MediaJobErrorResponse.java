package com.medicationvideo.platform.mediaprivacy;

record MediaJobErrorResponse(String errorClass, String errorCode, String safeMessage, boolean retryable) {
}
