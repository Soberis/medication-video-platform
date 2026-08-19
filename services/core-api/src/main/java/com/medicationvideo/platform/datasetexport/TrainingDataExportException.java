package com.medicationvideo.platform.datasetexport;

final class TrainingDataExportException extends RuntimeException {

	private final String errorCode;

	private final int statusCode;

	TrainingDataExportException(String errorCode, String message, int statusCode) {
		super(message);
		this.errorCode = errorCode;
		this.statusCode = statusCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}

	int getStatusCode() {
		return this.statusCode;
	}

}
