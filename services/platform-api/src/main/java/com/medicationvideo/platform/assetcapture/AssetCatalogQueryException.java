package com.medicationvideo.platform.assetcapture;

final class AssetCatalogQueryException extends RuntimeException {

	private final String errorCode;

	AssetCatalogQueryException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	String getErrorCode() {
		return this.errorCode;
	}
}
