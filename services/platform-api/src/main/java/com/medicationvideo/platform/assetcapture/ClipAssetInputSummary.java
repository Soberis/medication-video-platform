package com.medicationvideo.platform.assetcapture;

import java.time.Instant;
import java.util.UUID;

/**
 * Frozen, cross-module read-only snapshot of a clip asset's input surface. Intentionally omits
 * storage bucket, object key and ETag so that downstream modules cannot reach object bytes
 * without going through the assetcapture public read model and the platform object storage
 * adapter. The {@code inputDigest} matches the SHA-256 that the edge device verified at upload
 * completion.
 */
public record ClipAssetInputSummary(
		UUID clipAssetId,
		UUID projectId,
		String contentType,
		long inputSizeBytes,
		String inputDigest,
		ClipAssetStatus status,
		Instant createdAt,
		Instant updatedAt) {
}
