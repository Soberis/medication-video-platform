package com.medicationvideo.platform.integration;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.medicationvideo.platform.assetcapture.MultipartObjectStorage;
import com.medicationvideo.platform.assetcapture.MultipartObjectStorageException;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

@Component
class S3MultipartObjectStorage implements MultipartObjectStorage {

	private final S3Client s3Client;
	private final S3Presigner presigner;
	private final String bucket;

	S3MultipartObjectStorage(
			S3Client s3Client,
			S3Presigner presigner,
			S3ObjectStorageProperties properties) {
		this.s3Client = s3Client;
		this.presigner = presigner;
		this.bucket = properties.bucket();
	}

	@Override
	public CreatedUpload create(String objectKey, String contentType) {
		try {
			var response = this.s3Client.createMultipartUpload(CreateMultipartUploadRequest.builder()
					.bucket(this.bucket)
					.key(objectKey)
					.contentType(contentType)
					.build());
			if (response.uploadId() == null || response.uploadId().isBlank()) {
				throw new MultipartObjectStorageException(
						"Object storage returned no multipart upload identifier.",
						null);
			}
			return new CreatedUpload(this.bucket, objectKey, response.uploadId());
		}
		catch (SdkException exception) {
			throw failure("Object storage did not create the multipart upload.", exception);
		}
	}

	@Override
	public URI authorizePart(CreatedUpload upload, int partNumber, Duration lifetime) {
		try {
			var request = UploadPartRequest.builder()
					.bucket(upload.bucket())
					.key(upload.objectKey())
					.uploadId(upload.uploadId())
					.partNumber(partNumber)
					.build();
			return URI.create(this.presigner.presignUploadPart(UploadPartPresignRequest.builder()
					.signatureDuration(lifetime)
					.uploadPartRequest(request)
					.build())
					.url()
					.toExternalForm());
		}
		catch (SdkException exception) {
			throw failure("Object storage did not authorize the upload part.", exception);
		}
	}

	@Override
	public Optional<StoredPart> findPart(CreatedUpload upload, int partNumber) {
		try {
			var response = this.s3Client.listParts(ListPartsRequest.builder()
					.bucket(upload.bucket())
					.key(upload.objectKey())
					.uploadId(upload.uploadId())
					.partNumberMarker(partNumber - 1)
					.maxParts(1)
					.build());
			return response.parts().stream()
					.filter(part -> part.partNumber() == partNumber)
					.findFirst()
					.map(S3MultipartObjectStorage::storedPart);
		}
		catch (SdkException exception) {
			throw failure("Object storage did not inspect the upload part.", exception);
		}
	}

	@Override
	public List<StoredPart> listParts(CreatedUpload upload) {
		try {
			var storedParts = new ArrayList<StoredPart>();
			Integer marker = null;
			do {
				var response = this.s3Client.listParts(ListPartsRequest.builder()
						.bucket(upload.bucket())
						.key(upload.objectKey())
						.uploadId(upload.uploadId())
						.partNumberMarker(marker)
						.maxParts(1_000)
						.build());
				response.parts().stream().map(S3MultipartObjectStorage::storedPart).forEach(storedParts::add);
				if (!Boolean.TRUE.equals(response.isTruncated())) {
					break;
				}
				marker = response.nextPartNumberMarker();
				if (marker == null) {
					throw new MultipartObjectStorageException(
							"Object storage returned a truncated part list without a continuation marker.",
							null);
				}
			}
			while (true);
			return List.copyOf(storedParts);
		}
		catch (SdkException exception) {
			throw failure("Object storage did not list the upload parts.", exception);
		}
	}

	@Override
	public CompletedObject complete(CreatedUpload upload, List<StoredPart> parts) {
		if (parts.isEmpty()) {
			throw new IllegalArgumentException("Multipart completion requires at least one part.");
		}
		try {
			var completedParts = parts.stream()
					.map(part -> CompletedPart.builder()
							.partNumber(part.partNumber())
							.eTag(part.eTag())
							.build())
					.toList();
			this.s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
					.bucket(upload.bucket())
					.key(upload.objectKey())
					.uploadId(upload.uploadId())
					.multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
					.build());
			return findCompletedObject(upload).orElseThrow(() -> new MultipartObjectStorageException(
					"Object storage completed the multipart upload but returned no final object.",
					null));
		}
		catch (SdkException exception) {
			throw failure("Object storage did not complete the multipart upload.", exception);
		}
	}

	@Override
	public Optional<CompletedObject> findCompletedObject(CreatedUpload upload) {
		try {
			var response = this.s3Client.headObject(HeadObjectRequest.builder()
					.bucket(upload.bucket())
					.key(upload.objectKey())
					.build());
			return Optional.of(completedObject(response.contentLength(), response.eTag()));
		}
		catch (S3Exception exception) {
			if (exception.statusCode() == 404) {
				return Optional.empty();
			}
			throw failure("Object storage did not inspect the completed object.", exception);
		}
		catch (SdkException exception) {
			throw failure("Object storage did not inspect the completed object.", exception);
		}
	}

	@Override
	public String calculateSha256(CreatedUpload upload) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			try (var input = this.s3Client.getObject(GetObjectRequest.builder()
					.bucket(upload.bucket())
					.key(upload.objectKey())
					.build())) {
				var buffer = new byte[1024 * 1024];
				int count;
				while ((count = input.read(buffer)) >= 0) {
					if (count > 0) {
						digest.update(buffer, 0, count);
					}
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable in the Java runtime.", exception);
		}
		catch (IOException | SdkException exception) {
			throw new MultipartObjectStorageException(
					"Object storage did not stream the completed object for integrity verification.",
					exception);
		}
	}

	@Override
	public byte[] readCompletedObject(ObjectReference object, int maxBytes) {
		if (maxBytes < 1) {
			throw new IllegalArgumentException("Maximum object size must be positive.");
		}
		try (var input = this.s3Client.getObject(GetObjectRequest.builder()
				.bucket(object.bucket())
				.key(object.objectKey())
				.build())) {
			var declaredLength = input.response().contentLength();
			if (declaredLength != null && declaredLength > maxBytes) {
				throw new MultipartObjectStorageException(
						"Completed object exceeds the permitted read size.",
						null);
			}
			var output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
			var buffer = new byte[64 * 1024];
			int count;
			while ((count = input.read(buffer)) >= 0) {
				if (count == 0) {
					continue;
				}
				if (output.size() + count > maxBytes) {
					throw new MultipartObjectStorageException(
							"Completed object exceeds the permitted read size.",
							null);
				}
				output.write(buffer, 0, count);
			}
			return output.toByteArray();
		}
		catch (MultipartObjectStorageException exception) {
			throw exception;
		}
		catch (IOException | SdkException exception) {
			throw new MultipartObjectStorageException(
					"Object storage did not read the completed object.",
					exception);
		}
	}

	@Override
	public void abort(CreatedUpload upload) {
		try {
			this.s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
					.bucket(upload.bucket())
					.key(upload.objectKey())
					.uploadId(upload.uploadId())
					.build());
		}
		catch (SdkException exception) {
			throw failure("Object storage did not abort the multipart upload.", exception);
		}
	}

	private static MultipartObjectStorageException failure(String message, SdkException cause) {
		return new MultipartObjectStorageException(message, cause);
	}

	private static StoredPart storedPart(software.amazon.awssdk.services.s3.model.Part part) {
		try {
			return new StoredPart(part.partNumber(), part.size(), part.eTag());
		}
		catch (IllegalArgumentException exception) {
			throw new MultipartObjectStorageException(
					"Object storage returned invalid upload part metadata.",
					exception);
		}
	}

	private static CompletedObject completedObject(long sizeBytes, String eTag) {
		try {
			return new CompletedObject(sizeBytes, eTag);
		}
		catch (IllegalArgumentException exception) {
			throw new MultipartObjectStorageException(
					"Object storage returned invalid completed object metadata.",
					exception);
		}
	}
}
