package com.medicationvideo.platform.assetcapture;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public interface MultipartObjectStorage {

	CreatedUpload create(String objectKey, String contentType);

	URI authorizePart(CreatedUpload upload, int partNumber, Duration lifetime);

	URI authorizeRead(ObjectReference object, Duration lifetime);

	/**
	 * Issues a read authorization for a Worker that runs on another machine. Deployments
	 * whose object storage is published on an address the host itself cannot hairpin to
	 * configure that endpoint separately; the default keeps the primary endpoint.
	 */
	default URI authorizeWorkerRead(ObjectReference object, Duration lifetime) {
		return authorizeRead(object, lifetime);
	}

	Optional<StoredPart> findPart(CreatedUpload upload, int partNumber);

	List<StoredPart> listParts(CreatedUpload upload);

	CompletedObject complete(CreatedUpload upload, List<StoredPart> parts);

	Optional<CompletedObject> findCompletedObject(CreatedUpload upload);

	String calculateSha256(CreatedUpload upload);

	byte[] readCompletedObject(ObjectReference object, int maxBytes);

	void abort(CreatedUpload upload);

	void delete(ObjectReference object);

	record ObjectReference(String bucket, String objectKey) {

		public ObjectReference {
			Objects.requireNonNull(bucket, "bucket");
			Objects.requireNonNull(objectKey, "objectKey");
			if (bucket.isBlank() || objectKey.isBlank()) {
				throw new IllegalArgumentException("Object storage identifiers must not be blank.");
			}
		}
	}

	record CreatedUpload(String bucket, String objectKey, String uploadId) {

		public CreatedUpload {
			Objects.requireNonNull(bucket, "bucket");
			Objects.requireNonNull(objectKey, "objectKey");
			Objects.requireNonNull(uploadId, "uploadId");
			if (bucket.isBlank() || objectKey.isBlank() || uploadId.isBlank()) {
				throw new IllegalArgumentException("Multipart storage identifiers must not be blank.");
			}
		}
	}

	record StoredPart(int partNumber, long sizeBytes, String eTag) {

		public StoredPart {
			if (partNumber < 1 || partNumber > 10_000) {
				throw new IllegalArgumentException("Stored part number must be between 1 and 10000.");
			}
			if (sizeBytes < 1) {
				throw new IllegalArgumentException("Stored part size must be positive.");
			}
			Objects.requireNonNull(eTag, "eTag");
			if (eTag.isBlank()) {
				throw new IllegalArgumentException("Stored part ETag must not be blank.");
			}
		}
	}

	record CompletedObject(long sizeBytes, String eTag) {

		public CompletedObject {
			if (sizeBytes < 1) {
				throw new IllegalArgumentException("Completed object size must be positive.");
			}
			Objects.requireNonNull(eTag, "eTag");
			if (eTag.isBlank()) {
				throw new IllegalArgumentException("Completed object ETag must not be blank.");
			}
		}
	}

}
