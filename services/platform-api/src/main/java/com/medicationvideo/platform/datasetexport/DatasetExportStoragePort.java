package com.medicationvideo.platform.datasetexport;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface DatasetExportStoragePort {

	CreatedUpload create(String objectKey, String contentType);

	URI authorizePart(CreatedUpload upload, int partNumber, Duration lifetime);

	List<StoredPart> listParts(CreatedUpload upload);

	Optional<CompletedObject> findCompletedObject(CreatedUpload upload);

	CompletedObject complete(CreatedUpload upload, List<StoredPart> parts);

	String calculateSha256(CreatedUpload upload);

	URI authorizeRead(ObjectReference object, Duration lifetime);

	void abort(CreatedUpload upload);

	void delete(ObjectReference object);

	record ObjectReference(String bucket, String objectKey) {

		public ObjectReference {
			requireText(bucket, "bucket");
			requireText(objectKey, "objectKey");
		}
	}

	record CreatedUpload(String bucket, String objectKey, String uploadId) {

		public CreatedUpload {
			requireText(bucket, "bucket");
			requireText(objectKey, "objectKey");
			requireText(uploadId, "uploadId");
		}
	}

	record StoredPart(int partNumber, long sizeBytes, String eTag) {

		public StoredPart {
			if (partNumber < 1 || partNumber > 10_000 || sizeBytes < 1) {
				throw new IllegalArgumentException("Dataset export part metadata is invalid.");
			}
			requireText(eTag, "eTag");
		}
	}

	record CompletedObject(long sizeBytes, String eTag) {

		public CompletedObject {
			if (sizeBytes < 1) {
				throw new IllegalArgumentException("Dataset export object size must be positive.");
			}
			requireText(eTag, "eTag");
		}
	}

	private static void requireText(String value, String name) {
		Objects.requireNonNull(value, name);
		if (value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank.");
		}
	}

}
