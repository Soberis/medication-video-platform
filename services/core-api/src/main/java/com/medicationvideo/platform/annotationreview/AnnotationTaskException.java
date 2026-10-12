package com.medicationvideo.platform.annotationreview;

public class AnnotationTaskException extends RuntimeException {

	private final String errorCode;

	private final int statusCode;

	public AnnotationTaskException(String errorCode, String message, int statusCode) {
		super(message);
		this.errorCode = errorCode;
		this.statusCode = statusCode;
	}

	public String getErrorCode() {
		return this.errorCode;
	}

	public int getStatusCode() {
		return this.statusCode;
	}

}
