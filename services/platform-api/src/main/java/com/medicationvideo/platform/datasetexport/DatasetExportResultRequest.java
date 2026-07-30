package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

record DatasetExportResultRequest(
		@NotBlank String schemaVersion,
		@NotNull UUID messageId,
		@NotNull UUID exportId,
		@Min(1) int attempt,
		@NotBlank @Pattern(regexp = "^[A-Za-z0-9_-]{43,128}$") String leaseToken,
		@NotNull DatasetExportResultStatus status,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String snapshotDigest,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String exportProfileDigest,
		@Pattern(regexp = "^[a-f0-9]{64}$") String packageSha256,
		Long packageSizeBytes,
		@NotNull @Size(max = 1_000) List<@Valid DatasetExportFileResult> files,
		@NotBlank @Pattern(regexp = "^sha256:[a-f0-9]{64}$") String softwareDigest,
		@NotNull Instant startedAt,
		@NotNull Instant completedAt,
		@Valid DatasetExportErrorPayload error) {

	static final String SCHEMA_VERSION = "dataset-export-result.v1";

	DatasetExportResultRequest {
		files = files == null ? null : List.copyOf(files);
	}

	@AssertTrue(message = "result shape does not match status")
	boolean isShapeValid() {
		if (status == null || files == null) {
			return false;
		}
		if (status == DatasetExportResultStatus.SUCCEEDED) {
			return packageSha256 != null
					&& packageSizeBytes != null
					&& packageSizeBytes > 0
					&& !files.isEmpty()
					&& error == null;
		}
		return packageSha256 == null
				&& packageSizeBytes == null
				&& files.isEmpty()
				&& error != null;
	}

	String fingerprint() {
		var canonical = new StringBuilder()
				.append(schemaVersion).append('\n')
				.append(messageId).append('\n')
				.append(exportId).append('\n')
				.append(attempt).append('\n')
				.append(status).append('\n')
				.append(snapshotDigest).append('\n')
				.append(exportProfileDigest).append('\n')
				.append(packageSha256).append('\n')
				.append(packageSizeBytes).append('\n')
				.append(softwareDigest).append('\n')
				.append(startedAt).append('\n')
				.append(completedAt).append('\n');
		files.stream().sorted(java.util.Comparator.comparing(DatasetExportFileResult::path))
				.forEach(file -> canonical.append(file.path()).append(':')
						.append(file.sha256()).append(':')
						.append(file.sizeBytes()).append('\n'));
		if (error != null) {
			canonical.append(error.errorClass()).append(':')
					.append(error.errorCode()).append(':')
					.append(error.safeMessage()).append(':')
					.append(error.retryable()).append('\n');
		}
		return sha256(canonical.toString());
	}

	Map<String, Object> artifactManifest() {
		return Map.of(
				"schema_version", "dataset-export-artifact-manifest.v1",
				"files", files.stream()
						.sorted(java.util.Comparator.comparing(DatasetExportFileResult::path))
						.map(file -> Map.<String, Object>of(
								"path", file.path(),
								"sha256", file.sha256(),
								"size_bytes", file.sizeBytes()))
						.toList());
	}

	String artifactManifestDigest() {
		var canonical = new StringBuilder("dataset-export-artifact-manifest.v1\n");
		files.stream().sorted(java.util.Comparator.comparing(DatasetExportFileResult::path))
				.forEach(file -> canonical.append(file.path()).append(':')
						.append(file.sha256()).append(':')
						.append(file.sizeBytes()).append('\n'));
		return sha256(canonical.toString());
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available.", exception);
		}
	}
}

record DatasetExportFileResult(
		@NotBlank
		@Size(max = 512)
		@Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._/-]*$") String path,
		@NotBlank @Pattern(regexp = "^[a-f0-9]{64}$") String sha256,
		@Min(0) long sizeBytes) {
}

record DatasetExportErrorPayload(
		@NotNull DatasetExportErrorClass errorClass,
		@NotNull DatasetExportErrorCode errorCode,
		@NotBlank @Size(max = 512) String safeMessage,
		boolean retryable) {
}

enum DatasetExportResultStatus {
	SUCCEEDED,
	FAILED
}

enum DatasetExportErrorClass {
	RETRYABLE_TRANSIENT,
	RETRYABLE_RESOURCE,
	NON_RETRYABLE_INPUT,
	NON_RETRYABLE_POLICY,
	INTERNAL_BUG
}

enum DatasetExportErrorCode {
	INPUT_DOWNLOAD_FAILED,
	INPUT_DIGEST_MISMATCH,
	PACKAGE_BUILD_FAILED,
	OUTPUT_UPLOAD_FAILED,
	OUTPUT_DIGEST_MISMATCH,
	CONTRACT_INVALID,
	INTERNAL_ERROR
}
