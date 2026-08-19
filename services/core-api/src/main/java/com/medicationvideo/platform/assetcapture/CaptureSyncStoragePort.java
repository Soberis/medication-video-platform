package com.medicationvideo.platform.assetcapture;

import java.util.Objects;

import org.apache.parquet.io.InputFile;

public interface CaptureSyncStoragePort {

	VerifiedArtifact verify(ObjectReference object, long expectedSizeBytes, String expectedSha256,
			String expectedMediaType);

	/** Opens the immutable artifact for bounded Parquet row-group reads. */
	InputFile open(ObjectReference object);

	record ObjectReference(String bucket, String objectKey) {

		public ObjectReference {
			requireText(bucket, "bucket");
			requireText(objectKey, "objectKey");
		}
	}

	record VerifiedArtifact(ObjectReference object, long sizeBytes, String sha256, String mediaType) {

		public VerifiedArtifact {
			Objects.requireNonNull(object, "object");
			if (sizeBytes < 1) {
				throw new IllegalArgumentException("Artifact size must be positive.");
			}
			requireSha256(sha256, "sha256");
			requireText(mediaType, "mediaType");
		}
	}

	private static void requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank.");
		}
	}

	private static void requireSha256(String value, String name) {
		requireText(value, name);
		if (!value.matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException(name + " must be a lowercase SHA-256 digest.");
		}
	}

}
