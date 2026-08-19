package com.medicationvideo.platform.assetcapture;

public final class AssetCatalogQueryException extends RuntimeException {

	private final String errorCode;

	public AssetCatalogQueryException(String errorCode, String message) {
		super(message);
		this.errorCode = errorCode;
	}

	public String getErrorCode() {
		return this.errorCode;
	}

}
