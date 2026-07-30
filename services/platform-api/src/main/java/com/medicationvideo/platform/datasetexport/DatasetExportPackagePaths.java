package com.medicationvideo.platform.datasetexport;

import java.util.UUID;

final class DatasetExportPackagePaths {

	private DatasetExportPackagePaths() {
	}

	static String videoPath(
			UUID captureSessionId,
			int cameraIndex,
			UUID clipAssetId,
			String contentType) {
		return "videos/raw/" + captureSessionId
				+ "/camera-" + cameraIndex
				+ "-" + clipAssetId
				+ extension(contentType);
	}

	private static String extension(String contentType) {
		return switch (contentType.toLowerCase(java.util.Locale.ROOT)) {
			case "video/mp4" -> ".mp4";
			case "video/quicktime" -> ".mov";
			case "video/x-matroska" -> ".mkv";
			default -> ".bin";
		};
	}
}
