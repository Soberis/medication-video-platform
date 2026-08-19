package com.medicationvideo.platform.assetcapture;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

final class UploadBatchManifest {

	static final String FORMAT = "upload-file-manifest-v1";

	private UploadBatchManifest() {
	}

	static String sha256(List<UploadFile> files) {
		var canonical = new StringBuilder(FORMAT).append('\n');
		files.stream()
			.sorted(Comparator.comparing(file -> file.getClientFileId().toString()))
			.forEach(file -> canonical.append(file.getClientFileId())
				.append('\t')
				.append(file.getFileKind())
				.append('\t')
				.append(file.getContentType())
				.append('\t')
				.append(file.getSizeBytes())
				.append('\t')
				.append(file.getSha256())
				.append('\t')
				.append(file.getPartSizeBytes())
				.append('\n'));
		try {
			var digest = MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available in this Java runtime.", exception);
		}
	}

}
