package com.medicationvideo.platform.datasetexport;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

record DatasetVersionCursor(Instant createdAt, UUID datasetVersionId) {

	private static final String VERSION = "v1";
	private static final String KIND = "dataset-version";

	static String encode(Instant createdAt, UUID datasetVersionId) {
		var value = String.join(
				"|",
				VERSION,
				KIND,
				Long.toString(createdAt.getEpochSecond()),
				Integer.toString(createdAt.getNano()),
				datasetVersionId.toString());
		return Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	static DatasetVersionCursor decode(String cursor) {
		try {
			var decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			var parts = decoded.split("\\|", -1);
			if (parts.length != 5 || !VERSION.equals(parts[0]) || !KIND.equals(parts[1])) {
				throw invalid();
			}
			var seconds = Long.parseLong(parts[2]);
			var nanos = Integer.parseInt(parts[3]);
			return new DatasetVersionCursor(Instant.ofEpochSecond(seconds, nanos), UUID.fromString(parts[4]));
		}
		catch (ArithmeticException | IllegalArgumentException | DateTimeException exception) {
			throw invalid();
		}
	}

	private static DatasetVersionException invalid() {
		return new DatasetVersionException(
				"INVALID_CURSOR",
				"The dataset-version pagination cursor is invalid or belongs to another resource.",
				400);
	}
}
